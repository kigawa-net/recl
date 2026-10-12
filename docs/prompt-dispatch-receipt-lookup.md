# Model Prompt Dispatch Receipt Lookup / Outcome Reconciliation

Status: **Core contract + ephemeral InMemory tests** for [recl #61](https://github.com/kigawa-net/recl/issues/61). No production Fortis/Kivra ModelGateway implementation is included.

## Why lookup is separate from send-start

[recl PR #60](https://github.com/kigawa-net/recl/pull/60) introduced a bounded, server-issued Permit and a single logical SEND_STARTED intent. A lost response can mean a remote model received the prompt, regardless of the local HTTP status. A missing in-memory receipt after restart is **not evidence** that the remote provider had no effect.

`TrustedPromptDispatchReceiptReader.lookup(query)` is a server-authenticated authority returning:
- `Found(STARTED_PENDING / TRANSPORT_ACCEPTED / DEFINITELY_NOT_SENT / OUTCOME_UNKNOWN)` with a version-bounded request SHA-256 and timestamps;
- `NotFound`, `BindingConflict`, `Unsupported`, `Unavailable`, or `OutcomeUnknown`.

`TrustedPromptDispatchReconciler` makes no network call and cannot replay an operation. It maps missing/unresolved receipts to `HeldNoReplay`, never to a retry authorization. `DefinitelyNotSent` means a **trusted transport** settled the original operation with explicit no-effect evidence; it does **not** automatically create another permitted SEND_STARTED with the same OperationId. For a new distinct operation, production must require additional authenticated permission and actual external-result reconciliation.

The lookup identity binds **Tenant, Principal, Operation ID, Model Destination and canonical digest**, not merely a user-visible task ID. A request for an unrelated Tenant/Principal must hide even the existence of a record. A different Destination or Digest under the same authenticated operation is `BindingConflict`.

## Security boundary

The in-memory implementation adds a ReadOnly `lookup()` method to its existing process-local operation map. Like the previous test-only journal, it fails to persist receipts across restart; the corresponding regression explicitly treats the resulting `NotFound` as `HeldNoReplay`.

This is **not** a trustworthy proof of transport delivery, model completion, distributed single-writer ownership, durable GC retention or authorization after revocation. Statuses are facts recorded by the local fake, not remotely signed receipts.

## Production acceptance still blocked

1. Fortis [#44](https://github.com/kigawa-net/fortis/issues/44) same-group conditional batch and **durable** RequestResult receipt + linearizable ResultLookup; wire to [recl #14](https://github.com/kigawa-net/recl/issues/14) without read-then-write approximation.
2. Authenticated caller, Tenant, Repo Grant Epoch, Model Destination and per-operation request digest checks in a real recl/Kivra Transport, not caller-provided strings. Loss/compaction/tombstone retention cannot silently permit new dispatch.
3. Model Provider's result lookup evidence, response-lost/Quorum Loss/Crash/LeaderChange/2+Replicas and idempotency tests. Fortis cannot atomically undo external HTTP side effects.
4. Keep MemoryMutation operations [Kivra #77](https://github.com/kigawa-net/kivra/issues/77) separate from Model SEND_STARTED [Kivra #87](https://github.com/kigawa-net/kivra/issues/87) in the operation namespace/receipt schema. A mutation `DefinitelyNotSent` cannot authorize another model call.
5. Version and authenticate the real REST/MCP Provider API [recl #21](https://github.com/kigawa-net/recl/issues/21), qualify the live deployment via Kivra #71, and validate actual scope/budget/renderer before any production prompt injection.

Parent [recl #56](https://github.com/kigawa-net/recl/issues/56) and [#59](https://github.com/kigawa-net/recl/issues/59) remain Open. Production `pinnedContextsV1`, `promptOutputBarrierV1` and `durableDispatchReceiptV1` remain Disabled.
