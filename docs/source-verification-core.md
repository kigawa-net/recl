# Internal Source Verification CAS / Evidence Lease

Status: **internal test-only verification engine** for [recl #33](https://github.com/kigawa-net/recl/issues/33). Does not implement a real GitHub/exkes/Fusha verifier or expose any HTTP/MCP route. Production source validation remains [#32](https://github.com/kigawa-net/recl/issues/32).

## Trusted architecture

```text
server-owned verifier worker (NOT caller-supplied DTO)
  -> SourceVerificationService(tenantId, contextId, sourceId, expectedContentRevision)
  -> authoritative ContextStorage.Get
  -> TrustedSourceAttestor.attest(SourceVerificationScope, ContextSource)
  -> validate evidence binding / audience / digest / TTL
  -> authoritative ContextStorage.Get again
  -> CAS preserving semantic identity and index intent
```

`SourceSubmission` intentionally carries no `VerificationState` and no attestation. Any client-submitted source starts `UNVERIFIED`. The `TrustedSourceAttestor` interface is an **in-process trust boundary** for eventual authenticated adapters; mocks can produce attestation test data but are not trusted in production.

## Evidence contract

- Non-secret `VerificationEvidence` binds tenant/context/source ID, canonical URI, SHA-256 digest, **the original semantic content revision**, verifier identity, audience, attestation ID, verifiedAt and expiresAt.
- The engine checks the configured audience and the exact stored Source identity/digest. Unknown or missing stored contentDigest cannot become VERIFIED.
- verifiedAt must not be in the future, expiresAt must be in the future, and evidence lifetime must be at most the configured 24-hour default.
- `Verified` is one of five typed provider results: `Verified`, `Stale`, `Invalid`, `Unavailable`, `OutcomeUnknown`. Only trusted Verified with passing constraints receives a persisted verified status. Stale/Invalid clear proof and block future pinned injection.
- Evidence fields are **not a signature and not sufficient to authenticate a Provider**. Production adapters must verify issuer/audience/tenant grants, actual source data, immutable digest and authorization *before* returning the internal Verified type.

## Consistency

- Verification fixes the **semantic content revision** before contacting the attestor. A content/ACL/status/source/delete mutation while checking invalidates the pending attestation; an unrelated index ACK is a physical storage revision change and can be retried.
- A successful proof update uses an authoritative storage CAS, increments contentRevision and updates index intent. No approximate GET→PUT fallback is allowed in the real Fortis adapter.
- The provider may return unknown; the storage CAS may independently return OutcomeUnknown. **Neither is interpreted as Applied**. Until Fortis durable receipts and result lookup are available, do not blindly replay an unknown mutation.
- Changing the Context title/summary/content through the ContextPatch revokes previously verified source proofs. Replacing the list via ContextPatch also resets all SourceSubmissions to UNVERIFIED.
- If an inferred Context is CONFIRMED and the attestor later marks the inference invalid or stale, the internal service moves status back to PROPOSED to preserve the prohibition on confirmed, unverified agent inference.

## Read policy / expiry

`ContextSource.effectiveVerificationAt(trustedClockMillis)` downgrades expired proofs to STALE **for the current read**; it does not mutate Fortis to STALE. The Pinned reader excludes any Context with an expired/non-verified source. Direct Get returns a public `SourceCitation` view with the effective verification status, **without provider identity, audience, receipt or attestation ID**.

This is time-dependent authorization of prompt inclusion, not a persisted evidence-revocation job. A future scheduled verifier worker can persist STALE at expiry, subject to CAS/Receipt/GC requirements.

## Production gates still missing

1. Real GitHub/exkes/Fusha Provider checks, short-lived signed attestation and proper HTTPS URL SSRF/DNS/redirect protection ([recl #32](https://github.com/kigawa-net/recl/issues/32)).
2. Verified identity and repository/execution grants, policy audits, source-specific retention/expiry and authenticated worker scheduling.
3. Actual Fortis linearizable Get/CAS, durable request result receipts, revision-pinned Scan, snapshot/restart safety ([recl #14](https://github.com/kigawa-net/recl/issues/14), [Fortis #44](https://github.com/kigawa-net/fortis/issues/44), [#45](https://github.com/kigawa-net/fortis/issues/45)).
4. Kivra Memory Provider wire schema / capability gate, MCP/REST auth and exact tokenizer ([recl #21](https://github.com/kigawa-net/recl/issues/21), [Kivra #71](https://github.com/kigawa-net/kivra/issues/71)).

**A fake attestor plus passing InMemory JUnit tests is never production verification evidence.**
