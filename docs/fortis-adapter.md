# Fortis ContextStorage adapter (`:storage-fortis`)

Status: **adapter code and fake-port tests only**. It is not wired into the application, the production configuration still fails closed, and nothing here is a production-ready storage backend. Parent: [recl #3 Fortis Storage Adapter](https://github.com/kigawa-net/recl/issues/3); prerequisites in [recl #14](https://github.com/kigawa-net/recl/issues/14) and the design's Fortis production gate (`docs/design.md` section 11).

## Layout

| Path | Role |
| ---- | ---- |
| `core/.../StoragePort.kt` | `ContextStorage` (unchanged) |
| `storage-fortis/.../FortisKvPort.kt` | recl-side port over Fortis single-key, ConditionalBatch and prefix-scan operations |
| `storage-fortis/.../FortisContextStorage.kt` | `ContextStorage` implemented only on top of `FortisKvPort` |
| `storage-fortis/.../ContextAggregateCodec.kt` | deterministic, schema-versioned `ContextAggregate` <-> bytes |
| `storage-fortis/.../FortisContextKeys.kt` | key layout and fail-closed identifier validation |
| `core/src/testFixtures/.../ContextStorageContract.kt` | storage contract cases shared by every adapter |
| `storage-fortis/src/test/.../FaithfulFortisKvFake.kt` | in-process model of the Fortis contracts, used by tests only |

The shared contract runs against `InMemoryContextStorage` and `FortisContextStorage` (over the fake). Adapter-only outcomes (OutcomeUnknown, Unavailable, Unsupported, identifier rejection) are tested in `FortisContextStorageTest`.

## Fortis contracts this port mirrors

The port follows fortis `origin/feature/raft` (`docs/conditional-batch-contract.md`, `docs/revision-scan-contract.md`). It does not link to fortis code, see "Why there is no Gradle dependency on fortis" below.

- Conditional batch: `Absent(key)` and `RevisionEquals(key, rev)` conditions, `Put` mutations, all conditions evaluated atomically, a receipt keyed by `(principal, requestId)` that stores the canonical request and the result (APPLIED or REJECTED with the log index). Same requestId with the same request replays the stored result. Same requestId with a different request is `DigestMismatch`.
- Lookup never executes. `ReceiptExpiredOrUnknown` does **not** prove the batch never ran.
- Prefix scan at a pinned revision, with `nextCursor == null` meaning complete.

## Result mapping

| Situation | Result |
| ---- | ---- |
| Batch APPLIED | `Applied(StoredContext(value, logIndex))`; storageRevision = key revision (log index) |
| Batch REJECTED (create: key exists; CAS: revision differs) | `Conflict` |
| Submit unconfirmed (timeout, leader change, quorum loss) | lookup by `(principal, requestId, batch)`: `Found` gives the stored result above; anything else gives `OutcomeUnknown` |
| Submit `DigestMismatch` | `OutcomeUnknown` (safe side; the requestId belongs to a different request) |
| Lookup `DigestMismatch` / `ReceiptExpiredOrUnknown` / unavailable | `OutcomeUnknown` |
| Submit known not proposed | `Unavailable` |
| Read unavailable | `ReadResult.Unavailable` |
| Corrupt stored bytes, or bytes stored under another key identity | `ReadResult.Unavailable` / `EnumerationResult.Unavailable` |

The adapter never resubmits after an unconfirmed submit. It only reads the receipt, as the contract requires.

## Design decisions

1. **No change to the core `ContextStorage` contract.** `createIfAbsent` and `compareAndSwap` receive the new aggregate, and its `operationReceipts` already carries the requestId and digest of the request. The adapter takes the last receipt (`ContextService` appends it last) and uses that as the Fortis requestId. Fortis's canonical digest covers the whole batch, including the new value, so the digest is not the client's `payloadDigest`. The alternative, adding requestId/digest parameters, would change every adapter and the in-memory test contract. Writes without a receipt are refused with `IllegalArgumentException`.
2. **Fortis requestId is scoped to the Context**: `recl/v1/c/{contextId}/r/{requestId}`. Fortis dedup is per `(principal, requestId)`, so without scoping one client requestId reused on two Contexts would collide.
3. **Principal is the tenantId.** It is server-derived by the caller. Fortis does not authenticate it (see unverified items).
4. **Port lookup takes the batch, not a digest.** The Fortis digest is the canonical encoding produced by the Fortis codec. The port implementation computes it from the same batch, so recl never has to reproduce that encoding byte for byte. The in-process fake uses its own canonical digest.
5. **Revision 0 CAS is refused without a Fortis call.** Fortis treats revision 0 as "never written", so it would turn a CAS into a create.
6. **Synchronous port.** `ContextStorage` is synchronous and fortis's client is `suspend`. The real binding will need a blocking bridge or a suspend port. This is deliberately left open.
7. **Fail closed on identifiers.** Tenant IDs must match `[a-z0-9][a-z0-9-]{0,62}`, context IDs must be canonical lowercase UUIDv7 (`ContextIds`), and request IDs must match `[A-Za-z0-9._:-]{1,128}`. Anything else throws `IllegalArgumentException` before any Fortis call, with no trimming, lowercasing or re-encoding. Callers must validate first. Rejecting in `ContextService` or the API layer is still needed; a throw from storage is not a user-facing error.

## Keys and serialization

- Key: `recl/v1/t/{tenantId}/c/{contextId}`. Tenant prefix scans use the trailing slash, so `tenant-a` never matches `tenant-ab`.
- Value: `ContextAggregateCodec`, magic `RCLA`, `int schemaVersion = 1`, then fields in declaration order. Strings are length-prefixed UTF-8, optional values carry a presence byte, enums are encoded by name, scopes are written sorted. Verification evidence and its source-grant fence are included.
- Decode rejects bad magic, unknown versions, truncation, trailing bytes, values over 1 MiB, and anything the domain constructors reject (`CorruptContextRecord`).
- The encoded size must be at most 1 MiB, the Fortis value limit. Larger aggregates are refused with `IllegalArgumentException`.

## Enumeration

- Pages are read at one pinned revision: the first page with `readRevision = null` returns the revision, every later page passes that revision with its cursor. Writes between pages are therefore not mixed in (tested).
- `Complete` only when a page returns `nextCursor == null`. Partial results are never returned. A failed page, a revision change, a repeated cursor, a duplicate or foreign key, or a corrupt record gives `Unavailable`.
- Scan `Unsupported` gives `Unsupported`. Going over `maxItems` gives `TooLarge`, and the scan stops as soon as that is known.

## Why there is no Gradle dependency on fortis

Checked on fortis `origin/feature/raft`:

- Fortis is a Kotlin Multiplatform build (Kotlin 2.3.20, JVM, macOS and Linux native targets). Its `raft` module pulls in Netty QUIC (with native libraries) and kotlinx-coroutines. recl is a Kotlin JVM 2.2.20 build on JDK 21.
- `includeBuild` would bring fortis's plugin versions, native dependencies and repository setup into recl's build, which could break the existing modules. The task forbids that.
- The fortis batch and scan clients run in-process against `RaftRuntime`. The public SDK / client over the network (fortis #33) and AuthN/AuthZ (#34) are not implemented, so there is no remote client to bind to yet.

Result: the port is defined in recl and tested against a faithful fake. Connecting it to a real Fortis is a follow-up.

## Verified in this change

- `gradle test --offline` passes for all modules, including the shared contract on both `InMemoryContextStorage` and `FortisContextStorage`.
- Fake-port tests cover: create duplicates, CAS conflicts, not found, tenant isolation, OutcomeUnknown resolved through the receipt in both directions, OutcomeUnknown kept when the lookup fails, DigestMismatch, Unavailable, corrupt records, identifier rejection, enumeration Complete / multi-page / TooLarge / Unsupported / failed page, pinned revisions across pages, codec round trips, and determinism.

## Not verified (required before any production claim)

- **No real Fortis.** The port is not connected to a running Fortis. The fake encodes our reading of the contracts. Compare it with the real implementation before relying on it.
- **Authentication and authorization.** Principal and tenantId are caller-supplied. Fortis does not authenticate them. Server-side derivation (#34) is required.
- **3-voter E2E, crash injection, QUIC.** Fortis's own tests use an in-memory 3-node transport. Leader change, quorum loss and restart behaviour under real processes are not verified.
- **Receipt retention.** Fortis receipts are not garbage-collected. Retention work must keep `ReceiptExpiredOrUnknown` meaning "unknown".
- **Snapshot and compaction.** Fortis's scan contract says the compaction floor is not in snapshots, so a restored leader may accept a cursor for a compacted revision. Pinned-revision enumeration depends on this being fixed.
- **Cursor integrity.** Fortis's cursor checksum detects corruption only. It is not authenticated.
- **On-disk atomicity.** Fortis's WAL has no atomic batch write. Durability of receipts depends on the Raft log and snapshots, not on the disk store alone.
- **requestDigest equality.** The real binding must make Fortis's digest for a batch match what `lookup` expects. The fake's digest is internal to the fake.
- **Fortis limits on requestId and key length** must be confirmed against the real implementation; the adapter's limits are the ones documented in fortis's contract.
- **Identifier validation** must be enforced in the API layer as well, so that invalid IDs never reach storage.
- **Async vs sync.** The synchronous port needs a decision (blocking bridge or suspend port) before the real binding.
- **Not wired into the app.** `app/` is unchanged, so production stays fail closed.
