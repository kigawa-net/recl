# Prompt Send-start Journal and bounded admission permit (recl #59)

**Status: INTERNAL CORE CONTRACT + INMEMORY FAULT TESTS ONLY.** This does not provide an actual Fortis durable dispatch journal, cross-replica lease authority or production model transport.

## Problem and chosen bounded semantics

An authenticated Pinned Query may pass all SourceGrant Epoch checks in [recl #54](https://github.com/kigawa-net/recl/issues/54), yet GitHub Grant revocation can happen before the model-side HTTP request. [PR #58](https://github.com/kigawa-net/recl/pull/58) modeled a single-JVM mutex around the actual send; that is unsafe with real network IO.

A **logical send-start record** is a distinct event from HTTP socket send, model transport acceptance, or model completion. A bounded permit means a revocation acknowledged *after* send-start may not cancel an in-flight send, but no new permit should be minted after revocation. The permitted grace window must be explicitly bounded by permit expiry and independently enforced at the model transport's exact send-start point; code claiming instant external rollback is incorrect.

## Internal interfaces

1. `PromptDispatchBinding.digest(request)` computes a versioned `sha256` of authenticated Tenant/Owner, destination/model/operation, selected Context ID, content, revision, all citation fields, scope and declared token limits. It uses length-prefixed data and sorted Scope sets. Only the digest, not raw Context or model credentials, belongs in the future operation receipt.
2. `TrustedPromptSendStartJournal.tryStart` must atomically verify all Authoritative Context/Grant Revisions and write an immutable, unique SendStart intent + bounded `PromptSendStartPermit`, **including the operation identity and digest**. An existing/unknown operation never authorizes a new send. Wrong digest must be rejected. A lost response is `OutcomeUnknown`, not NotSent.
3. `TrustedLeaseBoundPromptSink` must enforce permit origin, request binding and expiry at the actual network send-start boundary, not only when the application obtained the permit. This Port is not implemented for Kivra's real ModelGateway.
4. `TrustedPromptLeaseDispatcher` calls `tryStart`, checks the returned permit and exact snapshots, invokes the trusted sink only for Started/current leases, and settles the transport observation. A failed/unknown settle never becomes Accepted or NotSent. No retry logic is present.
5. `Accepted` only means trusted transport accepted a send, not that the model completed. An exception or lost response is `OutcomeUnknown`; `DefinitelyNotSent` requires affirmative transport evidence, never HTTP 404/429.

## Deliberate limitations of the InMemory adapter

`InMemoryPromptSendStartJournalForTestOnly` serializes a local send-start record **inside the earlier test-only Grant/Context JVM lock** and then releases that lock *before* calling the trusted sink. It caps the permit at 2 seconds and the earliest referenced Evidence/Grant expiry. It stores only operationId, principal/tenant, request SHA-256 and permit/outcome metadata.

This is not durable: a new JVM loses the receipt, as a test explicitly demonstrates. Multiple instances can mint the same operation and send twice. No protection exists against a malicious/non-cooperative sink ignoring a permit's actual start deadline or an external provider that accepts requests after expiry. DO NOT bind either InMemory authority or a generic caller-provided Sink into a production gateway.

## Remaining release gates

- [ ] Authoritative Fortis ConditionalBatch and durable RequestResult receipt with same-group placement and normalized Tenant/Principal/OperationId uniqueness ([Fortis #44](https://github.com/kigawa-net/fortis/issues/44), [recl #14](https://github.com/kigawa-net/recl/issues/14)).
- [ ] Signed, bound permit (or private server authority) validated by a concrete Kivra Gateway at actual wire send-start; protect against cross-model, cross-tenant, replay, late revocation.
- [ ] Document max revocation acknowledgment delay / active dispatch lease drain, deny after timeout, and how concurrent revocation and SEND_STARTED become ordered across 2+ replicas.
- [ ] Recover STARTED/UNKNOWN after crash without replay; Kivra [#77](https://github.com/kigawa-net/kivra/issues/77) and [#87](https://github.com/kigawa-net/kivra/issues/87) must use a durable operation journal.
- [ ] Real Fortis #45 Snapshot Scan, Identity/GrantLedger, live Kivra Model Gateway and Provider API with authenticated E2E, and a production capability manifest ([recl #21](https://github.com/kigawa-net/recl/issues/21), Kivra #71).
- [ ] Ensure the external model dispatch does not treat retrieved memory as privileged instructions or leak Context content in receipt/logs.

Parent [recl #56](https://github.com/kigawa-net/recl/issues/56) remains **OPEN**. `pinnedContextsV1`, `promptOutputBarrierV1`, and `durableDispatchReceiptV1` remain **DISABLED** in production.
