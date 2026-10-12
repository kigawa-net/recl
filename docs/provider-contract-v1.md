# recl Provider Contract v1 — Kivra Memory integration (proposal)

**Status: proposed, not implemented.** No public recl REST/MCP server, Fortis production adapter or Qdrant indexer has yet passed acceptance. This document defines the desired cross-repository contract; it is **not** an advertised or verified production capability.

Owners: [recl #21](https://github.com/kigawa-net/recl/issues/21) / [Kivra #37](https://github.com/kigawa-net/kivra/issues/37), [#71](https://github.com/kigawa-net/kivra/issues/71). Kivra [PR #57](https://github.com/kigawa-net/kivra/pull/57) currently implements a **mock-backed skeleton**, not a connected provider.

## 1. Responsibility matrix

| Component | Authority / role |
| --- | --- |
| recl | Stored Context, ACL, lifecycle, revision, provenance and authorized retrieval |
| Fortis | recl's authoritative KV storage; requires verified CAS, read and recovery contracts |
| Qdrant | Derived, eventually consistent semantic search. Not an authorization boundary |
| Kivra | Agent/run/task identity, working memory, context assembly, privacy classification, durable external-operation journal |
| exkes | Runtime execution/artifact origin; recl stores references, not an authoritative execution log |
| ai-scheduler | Scheduling/trigger source; not an authorization authority for memory writes |

Do not duplicate authoritative Task status from fusha-task, immutable code artifacts from GitHub, or execution logs from exkes in recl. Store provenance and verified summaries only.

## 2. Provider identity, versioning and capabilities

- Every mutating operation must be tied to **a verified service principal** and approved tenant/project grants. The client cannot assert `tenantId`, owner or ACL grants through request JSON or search scopes.
- Kivra uses a dedicated service credential. An Agent must not receive a general-purpose recl write token.
- Proposed contract identifier: `recl.memory.v1`. A future server should provide `GET /v1/capabilities` with `contractVersion` and supported operations. **A server's advertised capabilities are not sufficient to enable production writes.** Kivra must independently verify its deployment SHA/image digest, issuer/audience, tenant grants, end-to-end behavior and test evidence before transitioning its provider from Fake/Read-only.
- The actual deployed URL/path and supported authentication mechanism are **not yet settled**. Proposed canonical API prefix: `/v1`; Kivra's current `/api/v1` assumption must be reconciled through a versioned adapter. Do not silently alias different protocols.
- `GET /health` is an availability probe, not proof of linearizable Fortis reads, Qdrant freshness, durable receipts or safe write readiness. A dedicated readiness/capability endpoint should report limitations without leaking credentials.

## 3. Proposed API operations

| Method | Proposed route | Requirement / status |
| --- | --- | --- |
| POST | `/v1/contexts` | Future authenticated create; caller-stable UUIDv7 ID and requestId |
| GET | `/v1/contexts/{id}` | Future authorized latest Context |
| PATCH | `/v1/contexts/{id}` | Future `expectedContentRevision` + requestId + typed patch |
| DELETE | `/v1/contexts/{id}` | Future logical tombstone; precondition required |
| POST | `/v1/contexts/search` | Future Qdrant candidates revalidated against Fortis |
| GET | `/v1/pinned-contexts` | Future authorized direct Fortis read of pinned Context |
| GET | `/v1/requests/{requestId}` | **Proposal**, not implemented: authoritative request result lookup |
| POST | `/v1/contexts/{id}/supersede` | **Blocked** until verified multi-Context atomicity or explicit non-atomic state machine |
| DELETE | `/v1/contexts/{id}?mode=redact` | **Unsupported** until a verified end-to-end deletion/redaction protocol exists |

All routes above are **desired** API shapes, not claims that a server currently responds. Public DTOs, OpenAPI schemas, method-specific status codes, expiration rules and version negotiation need an implementing PR.

### Create (proposed request envelope)

```json
{
  "contractVersion": "recl.memory.v1",
  "contextId": "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b",
  "requestId": "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6c",
  "kind": "decision",
  "status": "confirmed",
  "title": "Architectural decision",
  "summary": "Summary",
  "content": "Content",
  "scopes": ["project:kigawa-net/recl"]
}
```

`tenantId`, `ownerId` and `payloadDigest` must be **derived by the server**, not accepted as authorization-bearing user input. See [trusted mutation gateway](trusted-mutation-envelope.md) and recl #20.

### Update (proposed request envelope)

```json
{
  "contractVersion": "recl.memory.v1",
  "requestId": "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6d",
  "expectedContentRevision": 3,
  "patch": {"title": "Updated architecture"}
}
```

The visible `contentRevision` is a semantic revision. Fortis's physical `storageRevision` is internal and cannot be used as a client precondition.

## 4. Memory types and status mapping

Current recl Core types are `decision | architecture | constraint | task | finding | reference | preference`. The eventual Kivra MemoryKind map must be explicit, rejecting types without a lossless or approved conversion.

| recl status | Kivra meaning | Retrieval rule |
| --- | --- | --- |
| `confirmed` | Validated active context | May be recalled if authorized and temporally valid |
| `proposed` | Unconfirmed assertion / hypothesis | Do not silently treat as confirmed |
| `superseded` | Historical context | Do not inject as current truth |
| `archived` | Retained inactive context | Omit from active recall |
| `deletedAt != null` (tombstone) | Deleted | Never return old text from Qdrant |

Kivra's `active/superseded/deleted` is not the same enum as recl's lifecycle; the adapter must deliberately transform states and reject unsupported states.

Additional Kivra metadata, including `confidence`, `sensitivity`, `retention`, structured `provenance`, `WriteProvenance` (task/execution), may require new recl domain fields. **Not supported merely because Kivra's mock DTO includes them.**

## 5. Scope and authorization

- Tenant, service identity, and allowed resource grants are server-derived from authenticated credentials.
- Scopes such as `tenant:`, `project:`, `repository:`, `exec:` are relevance filters/classifications, **not capabilities**.
- A response from Qdrant is a candidate. recl must perform authoritative Fortis reads, check current Context revision, ACL, status, validity and tenant grants, then return safe bounded summaries. Missing candidates may cause reduced recall, never an authorization bypass.
- Kivra re-filters returned data before context assembly, but must not rely on this to compensate for absent recl server ACL enforcement.
- Context for one Run/Task/Execution must not be reused for another without re-evaluating identity, TTL and provenance.

## 6. Idempotency, response loss and error semantics

- Mutating requests require a stable UUIDv7 `requestId`. Repeated requests must preserve Context ID and the same semantic payload.
- Server computes its own canonical SHA-256 request digest (domain-separated, NFC, sorted Scope set and explicit optional-field presence). Do not accept a caller-supplied payload digest as proof.
- A duplicate `requestId` with a different canonical operation/payload must be rejected.
- `ContentRevision` conflicts map to `409 Conflict` when the authoritative server proves the mismatch; no fake GET+PUT fallback.
- Proposed typed error categories: `INVALID_REQUEST`, `FORBIDDEN`, `NOT_FOUND`, `VERSION_CONFLICT`, `REQUEST_ID_REUSED`, `STORE_UNAVAILABLE`, `OUTCOME_UNKNOWN`, `CAPABILITY_UNSUPPORTED`.
- A timeout, connection reset after upload, response-lost, or generic 5xx may mean the write **already committed**. **Never automatically replay an UNKNOWN mutating request** without an authoritative result lookup and replay capability proof.
- A 404 from a subsequent GET is **not** evidence that a prior POST/DELETE did not execute; subsequent read might not identify the affected resource.
- An unchanged `contentRevision` alone does not prove `NOT_APPLIED` if an operation receipt was evicted or the state changed later. Request receipt retention and unknown-result query must be durable before production automatic recovery.
- `REDACT` must not downgrade to soft deletion. Supersede must not claim cross-key atomicity without verified Fortis support.

## 7. Kivra adapter migration plan

1. Update Kivra PR #57 README/KDoc/contract document: recl now has design + Core in main, but **not** a public REST/MCP service.
2. Keep `ReclAdapter` Fake/Mock-backed and non-mutating until the contract is implemented. No automatic memory-write retries for UNKNOWN operations.
3. Choose the official API prefix and schema; update DTOs and test exact URL/status/Identity/Version/Request ID behavior.
4. Run shared contract tests for Kivra Fake + recl implementation. Fake success does not authorize a production endpoint.
5. Complete provider attestation under [Kivra #71](https://github.com/kigawa-net/kivra/issues/71) and deterministic fault tests under [Kivra #70](https://github.com/kigawa-net/kivra/issues/70).
6. Only enable production writes after recl public AuthN/Z, Fortis storage recovery, durable request receipts, Qdrant reconciliation and Kivra journal have been verified.

## 8. Required end-to-end tests

- Different tenant/service principal cannot read or write another tenant's Context, regardless of supplied Scope.
- A stale token, wrong issuer/audience or revoked grant causes denial.
- Replaying the same Request ID with a modified payload/target/operation does not create a second write.
- A successful Fortis write followed by dropped HTTP response produces a safely reconcilable receipt or explicit UNKNOWN, never blind retry.
- A stale Qdrant point after ACL/status/delete change is never returned.
- Unknown/unsupported Kind, Sensitivity, Redact and Supersede do not silently transform to weaker semantics.
- Memory retrieved for a different Task/Execution/expired Handoff is never injected.
- A Capability advertises only the operations actually implemented; Kivra requires independently verified deployment-specific evidence to promote capabilities.

**Acceptance gate:** This contract becomes authoritative only after a versioned API implementation and cross-provider contract tests have passed. Until then recl is an internal Core library with no production Memory Provider endpoint.
