# SourceVerifier Grant Epoch fencing (internal contract)

Status: **phase 1 core storage port + in-memory fault tests**, [recl #39](https://github.com/kigawa-net/recl/issues/39). **NOT** an implemented Fortis multi-key transaction or a production identity/authorization service.

## Problem

GitHub App authorization can be revoked between `GitHubSourceAttestor.canRead()` and `SourceVerificationService.compareAndSwap()`. A second application-level check is still vulnerable to time-of-check/time-of-use. A GitHub name is mutable on repository transfer, so immutable numeric GitHub Repository ID must be bound to Installation ID, Tenant, Owner, Grant Generation and expiry.

## Safe commit contract

The trusted server-only `SourceGrantFence` binds tenantId, ownerId, repositoryOwner/name, GitHub repositoryId, installationId, generation and expiry. SourceVerificationService checks its identity, source type, URI and expiration before attempting the write.

`GrantFencedContextCommit.compareAndSwapWithGrant(tenantId, contextId, expectedStorageRevision, updatedContext, fence)` MUST atomically:
1. Read the authoritative Grant identity and verify it matches the entire `SourceGrantFence`, has not been revoked or expired, and its allowed operations include evidence confirmation.
2. Check the authoritative Context's storageRevision, identity and semantic content revision.
3. Apply the new Context state and durable result/receipt **as one state machine operation**.
4. Return an explicit `GrantRevoked`, `Conflict`, `Unsupported`, `Unavailable`, or `OutcomeUnknown` rather than silently falling back to a plain PUT.

**Two separate reads followed by a Context CAS DO NOT satisfy this contract.** Fortis #44 is a *proposed* single-group Conditional Batch; it must prove every involved key, including Grant Epoch, belongs to the same group and placement epoch. If this cannot be guaranteed, return `Unsupported` and keep Production `sourceVerificationV1` DISABLED. Multi-group atomicity is not claimed.

The existing `SourceVerificationService.verify()` remains an INTERNAL test pathway for non-GitHub fake attestations. To enforce the secure contract in future deployment wiring, set `requireGrantFenceForVerified=true` and supply a genuine `GrantFencedContextCommit`; a missing fence or missing commit capability is then `CapabilityUnsupported`, never an unfenced CAS.

## GitHub attestation changes

`GitHubInstallationGrant` includes optional `repositoryId` (legacy test fixtures use 0). Only nonzero IDs can issue a `SourceGrantFence`. `AuthoritativeGitHubAppAccess.acquireFence()` reads the server's authoritative grant, and `GitHubSourceAttestor` compares the fence before token issuance and after GitHub response. It binds the matched fence into the **internal** VerificationEvidence. Client `SourceSubmission` and public `SourceCitation` do not expose Grant Epoch.

**Missing concrete production GrantLedger / Fortis Adapter:** The `InMemoryContextStorage` implements the combined predicate under one JVM lock solely to run deterministic fault tests. Its grant API methods contain `ForTestOnly` and provide no process/restart/durable guarantees.

## Unresolved revocation of previously verified Context

A successful initial proof can still outlive a later grant revocation for its TTL. Current PinnedContextReader checks proof time validity, **not live repository grant revocation**. A complete implementation must authorize the principal and current Grant Epoch at retrieval/prompt-render time, or guarantee a revocation barrier and verified evidence invalidation under the same authority. This is a **separate release blocker** tracked by recl #39, not solved by the new commit port.

## Acceptance still open
- [ ] Implement Fortis namespace-correct same-group Conditional Batch and durable Receipt / Result Lookup; enforce Grant/Context same-group placement or `Unsupported`.
- [ ] Enforce verified grant epoch at the *read/prompt output* boundary and revocation barrier across replicas.
- [ ] Real GitHub App credential rotation, private repo/transfer, authenticated Tenant/Owner grant table, constant-time secret-safe logs.
- [ ] 3-voter Raft crash/partition/response-lost, concurrent grant revoke/regrant/move and index ACK fault tests.
- [ ] Kivra #71 deployment contract test and production capability attestation. 

Related: recl #14/#16/#21/#26/#35/#37/#39; Fortis #34/#44/#45; Kivra #71.


## Evidence lifetime is bounded by the grant
A VerificationEvidence that expires after its bound Grant Epoch expiry is rejected by the Core even when the proof is otherwise cryptographically valid. This is defense in depth; a previously VERIFIED Context can still outlive an immediate revocation, so the live read/prompt admission barrier remains tracked separately in [recl #54](https://github.com/kigawa-net/recl/issues/54).


In fenced mode, GitHubSourceAttestor requires the repository grant port and Installation Credentials port to be the **same authoritative broker instance**. A separate credential provider may use an unrelated installation despite a valid repository grant; such wiring is explicitly rejected with Unavailable.
