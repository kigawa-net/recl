# Pinned Context live grant read admission (phase 1)

Tracked by [recl #54](https://github.com/kigawa-net/recl/issues/54). Status: **internal Core read-admission protocol and test-only InMemory grant authority, NOT a production revocation barrier**.

## Threat

A valid `VerificationEvidence` may outlive a revoked GitHub App installation, a repository transfer, an owner removal, or a regrant to a different installation. Checking only evidence TTL and SourceTrust during Pinned retrieval is insufficient. [recl PR #53](https://github.com/kigawa-net/recl/pull/53) guards the earlier **verification write**; it does not revoke evidence already stored.

## TrustedReadAdmission contract

`TrustedGrantEpochReader.check(Principal, SourceGrantFence)` must be an authoritative, revocation-aware, linearizable read bound to the *full* expected immutable Repository ID, Installation ID, Generation, Tenant, Owner and expiry. Missing/unsupported/quorum-lost access must not return `Current`.

`SourceGrantReadAdmission` checks all cited sources, their effective evidence TTL, SourceGrantFence, original Context Owner/Tenant and each Grant Epoch. It returns `Allowed`, `Denied`, `Unsupported`, or `Unavailable`. Client Scope / Source URI / USER_STATED do not mint grants. Source-free legacy Contexts and unbound non-GitHub sources are **Unsupported** in the narrowly scoped strict mode, pending an independent repository-scoped policy engine.

`PinnedContextReader(..., readAdmission=..., requireReadAdmission=true)` runs the checks before including candidate contents in the token-budget selection and again after selection. Between the two passes it re-reads each Context authoritatively and fails the **whole** result if a selected snapshot changed or a Grant was revoked. A denied initial candidate is omitted rather than counted/returned. A missing GrantReader or Fortis operation returns `Unsupported`, not a deceptively complete list. An unavailable read returns `Unavailable`, not a cache fallback.

Default `requireReadAdmission=false` remains for **internal legacy tests only**; it does NOT constitute a production-safe constructor. Production service wiring must set it to true and provide a real authoritative reader, a trustworthy tokenizer and a Fortis revision-pinned complete enumeration.

## Consistency limit / next release gate

A second admission check closes **revocations during assembly**, but not the interval **after `list()` returns and before the caller sends the prompt**. The implementation returns ContextSnapshots and cannot guarantee that callers render them within a revocation-serialized transaction. Immediate revocation at the actual prompt-send boundary requires a trusted `PromptOutputBarrier` or short-lived, fenced admission lease with an explicit linearization point, cancellation and recheck at delivery, across replicas. This is separate acceptance work [recl #56](https://github.com/kigawa-net/recl/issues/56). This phase-1 implementation therefore **does not enable production Pinned or sourceVerification capabilities**.

## Fault tests and dependencies

- Live verified → grant revoke, regrant at higher epoch/installation, owner/tenant change, expiry
- Quorum loss, missing/unsupported reader, legacy source-free or unbound proofs
- Revocation between selection and final check; mutation between selection and final authoritative re-read
- No inference from an empty result that an unauthorized Context exists
- Test-only InMemory authority runs under a JVM monitor. **No durable/restart or multi-node Raft guarantees.**

Production gates: [recl #14](https://github.com/kigawa-net/recl/issues/14), [#21](https://github.com/kigawa-net/recl/issues/21), [#26](https://github.com/kigawa-net/recl/issues/26), [#37](https://github.com/kigawa-net/recl/issues/37), [#39](https://github.com/kigawa-net/recl/issues/39), [#54](https://github.com/kigawa-net/recl/issues/54), [#55](https://github.com/kigawa-net/recl/issues/56); [Fortis #44](https://github.com/kigawa-net/fortis/issues/44)/[#45](https://github.com/kigawa-net/fortis/issues/45), [Kivra #71](https://github.com/kigawa-net/kivra/issues/71).
