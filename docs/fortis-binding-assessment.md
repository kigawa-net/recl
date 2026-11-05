# Fortis real-binding assessment (recl #69)

Status: **assessment only.** Verdict: production remote binding is **BLOCKED**; an in-process PoC is
**PARTIALLY possible**. No code in this document is implemented.

Verified 2026-11-05 against:

- recl `storage-fortis/src/main/kotlin/net/kigawa/recl/storage/fortis/FortisKvPort.kt`;
- Fortis worktree `/home/kigawa/dev/kigawa-net/fortis-46-wt` (`7336ca6`, `feat/45-prefix-scan`);
- Fortis worktree `/home/kigawa/dev/kigawa-net/fortis-44-wt` (`b3314fb`, `feat/44-conditional-batch`);
- Fortis main worktree `fortis` (`db9c5e3`, `feature/raft`, does not yet contain #44/#45);
- `docs/conditional-batch-contract.md` and `docs/revision-scan-contract.md` in `fortis-46-wt`.

## recl requirements

`FortisKvPort` is synchronous and requires:

| Operation | Required semantics |
| --- | --- |
| `getLinearizable(key)` | Linearizable read returning value **and** the key's last-write revision |
| `submit(batch)` | Atomic conditional batch (`Absent` / `RevisionEquals` → `Put`) with a stable request receipt |
| `lookup(batch)` | Receipt lookup by `(principal, requestId)`; must not execute |
| `scanPrefix(...)` | Complete, revision-pinned prefix enumeration with opaque cursors |

`FortisContextStorage` already maps `ContextStorage` onto this port and is tested against a faithful
fake. CAS revisions are `Long`, matching Fortis's Raft log index.

## Capability matrix

| Requirement | Fortis (`fortis-46-wt`) | Verdict |
| --- | --- | --- |
| Conditional create/CAS | `ConditionalBatchClient.submit` + `ConditionalBatchApplier` | Yes (in-process only) |
| Receipt lookup | `ConditionalBatchClient.lookupResult` | Yes (in-process only) |
| Revision-pinned prefix scan | `RevisionScanClient.scanAtRevision` | Yes, single Raft group only |
| Linearizable read | `RaftRuntime.linearizableRead` + `StorageEngineStateMachine.get` | Partial: value only, no revision |
| CAS token | `Long` Raft log index | Yes |
| `OutcomeUnknown` | `ConditionalBatchResponse.OutcomeUnknown`, `ReceiptExpiredOrUnknown` | Yes, safety-biased |
| Network KV API / SDK | none | **Missing (#33)** |
| KV AuthN/AuthZ | none; `principal` is caller-supplied | **Missing (#34)** |
| Leader redirect / discovery | none | **Missing (#34)** |
| Published artifact | no `maven-publish`; KMP 2.3.20 / Gradle 9.7.1 vs recl 2.2.20 / 9.6.0 | **Missing** |

Only Raft peer RPC is networked (`NettyQuicRaftRpcServer`, `RequestVote` / `AppendEntries` /
`InstallSnapshot`); there is no client-facing KV server.

## Concrete gaps

1. **No remote binding.** `ConditionalBatchClient` and `RevisionScanClient` need an in-process
   `RaftRuntime`. A separate recl process or Pod cannot connect today.
2. **No KV authorization.** The contract itself states `principal` is not authenticated. Tenant
   isolation cannot be delegated to Fortis yet.
3. **No leader routing.** Non-leader submits degrade to `OutcomeUnknown`; scans degrade to
   `Unavailable`. There is no discovery/redirect layer.
4. **No artifact / build compatibility.** Fortis is a KMP build with Netty QUIC and native targets.
   `includeBuild` would import its plugin versions and repositories into recl.
5. **Value+revision read is missing.** `StorageEngineStateMachine.get` returns only the value. A
   dedicated API is preferable to a one-key scan workaround.
6. **Rejected receipt lacks its log index.** `BatchOutcome.Rejected(failedCondition,
   observedRevision)` has no `raftIndex`, while `conditional-batch-contract.md` says the receipt
   stores the log index and recl's `FortisReceipt` requires one.
7. **`Unavailable` is coarse.** `ConditionalBatchResponse` has no `Unavailable`; NotLeader, Stopped
   and Timeout all become `OutcomeUnknown` (fail-safe but imprecise).
8. **Receipt durability is unproven.** `RaftRuntime` defaults to `RaftSnapshotApplier.NoOp`, and
   `StorageEngineStateMachine` snapshot restore is not wired. Raft log replay covers restarts, but
   receipt retention after compaction is not established.
9. **Only an in-memory MVCC engine exists.** `MemoryMvccStorageEngine` is the only implementation;
   durability depends on Raft log replay.
10. **Sync port vs suspend client.** Either bridge with `runBlocking` or make the port suspend and
    propagate that through `ContextStorage`.

## Bounded in-process PoC (only if explicitly wanted)

1. Build/consume Fortis `raft`, `storage-engine` and `io` in a throwaway composite build; do not
   commit a cross-repo `includeBuild` to recl without a compatibility review.
2. Start `RaftRuntime` with `MemoryMvccStorageEngine` in one JVM.
3. Add `FortisRaftKvPort` implementing `FortisKvPort` over `RaftRuntime` +
   `ConditionalBatchClient` + `RevisionScanClient`.
4. Add a Fortis-side `getWithRevision(key)` and carry a log index on rejected receipts.
5. Run the existing `ContextStorageContract` plus unknown-outcome reconciliation, multi-page scan,
   compaction, cursor corruption and single-writer CAS tests.

This PoC proves semantics only. It is not a production connection path.

## Production gates

- Fortis public client SDK/API (#33) with a stable wire protocol.
- KV AuthN/AuthZ and server-derived tenant/principal (#34).
- Leader redirect/discovery and deterministic `Unavailable` vs `OutcomeUnknown`.
- Receipt durability across compaction and snapshot restore.
- Published, versioned artifacts compatible with recl's Kotlin/Gradle/JDK baseline.
- 3-voter E2E with QUIC, crash injection and recovery evidence.

## Decision

Do **not** add a Fortis dependency to recl yet. Keep the faithful fake and the port contract. Revisit
when #33/#34 are implemented; track progress in [recl #69](https://github.com/kigawa-net/recl/issues/69).

See [fortis-adapter.md](fortis-adapter.md) for the port-level adapter and its test strategy.
