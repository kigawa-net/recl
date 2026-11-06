# Production Capability Gate (recl #65)

> Status: **design only, not implemented.** This document defines the capability inventory and
> cross-repo E2E evidence matrix. No CI checks, staging runbook, or audit trail exist yet.

Date: 2026-11-05.

## 1. Background

recl #14/#21/#26/#39/#56/#59/#61/#63 establish internal Core contracts and production Provider
boundaries, but there is no single source of truth for:

- Which capabilities are actually enabled in production.
- Which capabilities require cross-repo E2E evidence (Fortis/Kivra).
- The evidence required to enable each capability.
- The fail-closed conditions under which a capability must remain disabled.

recl PR #64 is test-only (InMemory), Fortis PR #51 and Kivra PR #92 are Draft. This document
defines the inventory and evidence matrix before any production enablement.

## 2. Capability Inventory

| Capability | Owner | Dependency issues | Provider | Minimal supported version | Status | Evidence required | Current evidence |
|------------|-------|-------------------|----------|--------------------------|--------|-------------------|------------------|
| authoritativeStorageV1 | :core/:storage-fortis | #14 | Fortis | 1.0.0 | **Disabled** | Public single-key CAS, linearizable reads, durable iteration, recovery/snapshot | None (Fortis #33/#34 blocked) |
| consistentPinnedEnumerationV1 | :storage-fortis | #45 | Fortis | 1.0.0 | **Unsupported** | Gap-detectable enumeration, cursor, epoch verification | None (Fortis #45 open) |
| verifiedGitHubSourceV1 | :source-github | #39/#54/#63 | GitHub App | 2026-10-12 | **Enabled** (test-only) | Grant revoke/regrant, repo transfer, grant generation, Source Verification CAS | Implemented in test adapter only |
| pinnedContextsV1 | :mcp/:api-rest | #21/#40 | — | 1.0.0 | **Enabled** (test-only) | Token budget enforcement, tenant/owner verification | Implemented in test adapter only |
| promptOutputBarrierV1 | :core | #56/#59 | Kivra Model Gateway | 1.0.0 | **Disabled** | Real wire send-start, revoke, loss detection, duplicate prevention | None (Kivra #87 open) |
| durableDispatchReceiptV1 | :core | #61/#63 | Kivra Model Gateway | 1.0.0 | **Disabled** | UNKNOWN/NoEffect proof, operation dedup, worker kill/restart handling | None (Kivra #87/#91 open) |
| mutationV1 | :core/:storage-fortis | #16 | Fortis + Qdrant | 1.0.0 | **Enabled** (test-only) | CAS loop, idempotency, UNKNOWN semantics, ACL reevaluation | Implemented in InMemory adapter only |

### Status Definitions

- **Enabled (test-only)**: Implemented and tested in InMemory adapter. Production enablement requires:
  - Fortis provider evidence (for storage capabilities)
  - Kivra provider evidence (for prompt dispatch capabilities)
  - Production E2E verification
- **Disabled**: Not yet enabled. Requires provider evidence and E2E verification.
- **Unsupported**: Provider does not offer the capability. Must remain disabled.
- **Unavailable**: Provider is unavailable or under maintenance.

## 3. Cross-Repo E2E Evidence Matrix

### Fortis Evidence (recl #14)

| Scenario | Evidence Required | Status |
|----------|-------------------|--------|
| 3 voter restart | Log correlation across voters | Not verified |
| partition detection | Split-brain prevention | Not verified |
| leader-change | Lease transfer verification | Not verified |
| CAS operation | Conditional write success rate | Not verified |
| scan-at-revision | Consistent enumeration | Not verified |
| compaction-gap | Data durability verification | Not verified |
| receipt retention | OutcomeUnknown handling | Not verified |

**Fortis blocking issues:**
- #33: Public client/SDK availability
- #34: KV authentication and leader routing
- #45: Enumeration gap detection

### GitHub Evidence (recl #37/#39/#54/#63)

| Scenario | Evidence Required | Status |
|----------|-------------------|--------|
| Installation revoke/regrant | Grant lifecycle test | Not verified |
| Repository transfer | Tenant mapping preservation | Not verified |
| Grant Generation | Token issuance verification | Not verified |
| Source Verification CAS | Attestation check | Not verified |

### Kivra Evidence (recl #56/#59/#61/#63)

| Scenario | Evidence Required | Status |
|----------|-------------------|--------|
| real wire send-start | Actual network send verification | Not verified |
| revoke handling | Graceful revocation | Not verified |
| response loss | OutcomeUnknown return | Not verified |
| worker kill/restart | Idempotency verification | Not verified |
| duplicate operation | Deduplication verification | Not verified |
| UNKNOWN/NoEffect proof | Evidence of unknown outcome | Not verified |

**Kivra blocking issues:**
- #87: Model Gateway real wire semantics
- #91: Memory Mutation vs Prompt Dispatch domain separation

## 4. Fail-Closed Matrix

| Condition | Capability | Action |
|-----------|------------|--------|
| Missing provider | authoritativeStorageV1, consistentPinnedEnumerationV1 | Return `Unavailable` |
| Clock skew > threshold | promptOutputBarrierV1, durableDispatchReceiptV1 | Return `Unavailable` |
| Quorum loss | authoritativeStorageV1 | Return `Conflict`/`Unavailable` |
| Grant unavailable | verifiedGitHubSourceV1 | Return `Forbidden` |
| Index lag > threshold | mutationV1 | Return `OutcomeUnknown` |

## 5. Enablement Gate

Production enablement requires:

1. **CI required checks:**
   - All capabilities pass production E2E tests
   - Fortis provider capability verification (#14)
   - Kivra provider capability verification (#87)

2. **Owner approval:**
   - Capability owner reviews and approves production enablement
   - Risk assessment documented

3. **Staging runbook:**
   - Staging environment successfully tested
   - Rollback procedure documented

4. **Audit trail:**
   - Evidence URLs recorded in this document
   - Change history maintained

## 6. Non-Goals

- Do not claim `Enabled` without production provider evidence.
- Do not enable capabilities in production that are only tested in InMemory.
- Do not treat Draft PRs (Fortis #51, Kivra #92) as production-ready.

## 7. Related

- [recl #14](https://github.com/kigawa-net/recl/issues/14) - Fortis capability contract tests
- [recl #21](https://github.com/kigawa-net/recl/issues/21) - Common capability gate
- [recl #39](https://github.com/kigawa-net/recl/issues/39) - GitHub grant lifecycle
- [recl #56](https://github.com/kigawa-net/recl/issues/56) - Kivra Model Gateway semantics
- [recl #61](https://github.com/kigawa-net/recl/issues/61) - Kivra duplicate handling
- [recl #63](https://github.com/kigawa-net/recl/issues/63) - Kivra UNKNOWN semantics
- [Fortis #33](https://github.com/kigawa-net/fortis/issues/33) - Public client/SDK
- [Fortis #34](https://github.com/kigawa-net/fortis/issues/34) - KV auth and leader routing
- [Fortis #45](https://github.com/kigawa-net/fortis/issues/45) - Enumeration gap detection
- [Kivra #87](https://github.com/kigawa-net/kivra/issues/87) - Model Gateway real wire semantics
- [Kivra #91](https://github.com/kigawa-net/kivra/issues/91) - Memory Mutation vs Prompt Dispatch
