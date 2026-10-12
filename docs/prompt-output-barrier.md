# Prompt Output Barrier — Phase 1 internal contract

Status: **test-only single-process emission ordering model**, tracked by [recl #56](https://github.com/kigawa-net/recl/issues/56). This does **not** authorize Production Pinned/Prompt or deployment of the InMemory adapter.

## Threat and operation semantics

The earlier [recl PR #55](https://github.com/kigawa-net/recl/pull/55) checks live GitHub grants when selecting a Pinned Context and again before returning snapshots. Another revocation can occur between that return and sending the prompt to a model. A standard linearizable Get followed by an HTTP POST does not remove this check/send race. No Fortis KV transaction can roll back bytes already sent to a remote model.

`TrustedPromptOutputService.dispatch(PromptOutputRequest, TrustedPromptSink)` **never sends without** an injected `TrustedPromptOutputBarrier`. The request binds an authenticated Principal, a non-secret Model Destination (provider/model/operation ID), original PinnedQuery and the exact previously selected Context snapshots/used token cost. The sink itself is server-owned; no caller-supplied URL/authorization header is accepted.

`PromptOutputResult` distinguishes Accepted, NotSent, OutcomeUnknown, Forbidden, InvalidRequest, Unsupported and Unavailable. Once a trusted sink starts sending, exception/timeout cannot establish `DefinitelyNotSent`; it is `OutcomeUnknown`. Model completion requires a separate provider receipt and is not implied by Accepted. Do not automatically retry UNKNOWN.

## InMemory fault model

`InMemoryContextStorage.promptBarrierForTestOnly(counter)` holds the **same JVM monitor** as grant revoke/regrant and Context write across all of:

1. Re-read every selected Context from the authoritative local store, enforce Tenant/Owner/Confirmed/Pinned/validity/sensitivity/scope and live immutable Grant/Installation/Epoch/TTL.
2. Reconstruct a complete `ContextSnapshot` and demand exact equality with the preselected snapshot. Deny on Context mutation, altered provenance/citation or content revision.
3. Recompute the trusted token counter, enforce limits and compare the originally reported used tokens. Any error/oversize => do not invoke the sink.
4. Enter the **synchronous, trusted** sink under the same monitor, then release it on return. A concurrently revoking worker cannot commit revocation before the send-start call executes. Later deliveries fail after the revoked epoch.

This is deliberately an **unsafe production implementation**: it can block a lock across network I/O, has no cross-process fencing, cannot bound a hung sender or enforce destination credentials, and has no durable send journal. It is used only to deterministically prove that a well-defined atomic read/send-start order is possible. A callback running in the *same thread* can reenter a Java monitor; never interpret the JVM mutex as a distributed reentrancy-proof security boundary.

## Production design: separate admission and irreversible emission

A deployable mechanism must specify:
- Source of truth: actual Fortis AuthN Namespace/Grant Ledger with immutable GitHub Repository ID, Installation ID, Grant Generation and revoke epoch; same-group placement constraints (Fortis #44), complete Context scan (#45).
- **Revocation visibility / lease model**: either all send-start operations run through a strongly consistent service that serializes revoke vs start, or an explicitly bounded admission lease tied to the principal/destination/context digests, source epochs and expiration. Revocation may be delayed for a documented maximum lease lifetime; instantaneous external recall is impossible.
- Identity and replay: authenticated Kivra/Claude caller, operation ID, audience and model destination, request digest and a durable send-start/OUTCOME_UNKNOWN journal. Reuse across Tenant, Model or source versions must fail.
- Send-start is a separate linearization event from provider acceptance/completion. If a transport response is lost, preserve UNKNOWN and reconcile; never call it NotSent without evidence.
- Keep raw Context data from being promoted to instructions. Do not leak secrets, proof internals, or rejected-candidate counts.
- Fault acceptance: 2+ replicas, epoch rotate/revoke/regrant, Leader change/QuorumLoss, watch compaction gap, delayed sender, crash after send-start, burst/admission budget, Kivra Model Gateway E2E.

**Production Capability remains DISABLED until the live Fortis/Kivra/Provider acceptance tests pass.** Source-free legacy contexts need a separate authenticated repo/tenant read-policy; current strict SourceGrant admission returns Unsupported.
