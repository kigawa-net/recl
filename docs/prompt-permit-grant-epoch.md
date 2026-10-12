# Grant Epoch binding for Prompt Send-start Permit (recl #63)

**Status: internal Core protocol and InMemory admission tests only.** No production signed Permit, distributed revocation gate, Fortis receipt or real model transport exists.

## Threat

A public `ContextSnapshot` exposes Source citation trust and verification status, but does **not** include the internal `VerificationEvidence.sourceGrantFence`. Consequently, `PromptDispatchBinding.digest(request)` by itself cannot bind the resulting Send-start authorization to a specific GitHub Repository ID, Installation ID, Grant Generation or expiry. A new Grant for the same public citation can otherwise have the same public request hash.

## Internal contract

- `PromptSourceGrantEpoch`: the authoritative Context ID / Source ID / `SourceGrantFence` tuple. Never accept it from a client URL or Scope.
- `PromptGrantEpochBinding.digest()`: canonical, length-prefixed `recl.prompt.source-grant-epoch.v1` SHA-256 of all tuples (sorted by Context/Source; duplicate tuples rejected). It binds Tenant, Owner, immutable GitHub Repository ID, Installation ID, Generation, resource name and Grant expiry. This is separate from the existing content/request digest.
- `PromptSendStartPermit.grantEpochDigest`: mandatory server-origin binding. Issuance fails closed if referenced Source has no authoritative verification fence. The initial Publisher must still verify live revocation state.
- `TrustedPromptPermitAdmission.check()`: return Allowed, Denied, Unsupported or Unavailable based on the **present** authenticated authority, context/snapshot, persisted Journal, current Grant Epoch, and deadline. The check belongs at the **actual wire-send-start boundary**, not when the request entered Kivra.
- `AdmissionCheckedPromptSink`: helper that invokes the downstream model only after an injected trusted admission port accepts. Missing authority, rejected/expired Permit or unavailable checks cause **zero downstream invocations**. It returns `DefinitelyNotSent` only for its own guaranteed no-call path. Because an earlier SEND_STARTED intent still exists, that result does not authorize new Operation IDs or automatic resend.

## InMemory validation

The test-only Journal implements `TrustedPromptPermitAdmission`. It checks the original operation identity and Request SHA-256, all selected Contexts against current local authoritative state, original Grant Epoch digest and that the operation has not settled. It uses the test Context/Grant monitor and **never calls the real model** in the admission callback.

Regressions cover different Grant Generations/Installation IDs, repository IDs, reordered references, wrong Model destination, forged permit digest, Context mutation, revoke/regrant after lease issuance, expired Permit, previously settled operation, and fail-closed behavior without authority.

## Remaining production gates

1. **No source of cross-pod authority**: InMemory grants and receipts disappear on restart. Fortis [#44](https://github.com/kigawa-net/fortis/issues/44) / [#45](https://github.com/kigawa-net/fortis/issues/45), authenticated namespaces, lease epoch persistence and 3-voter recovery must pass first.
2. **A final check followed by an unrelated HTTP POST remains a TOCTOU**. The production Kivra Model Gateway must fence/serialize the exact actual wire send-start with revocation or publish a measurable bounded lease consistency model. A short TTL alone does not imply instantaneous revocation ([recl #56](https://github.com/kigawa-net/recl/issues/56), [Kivra #87](https://github.com/kigawa-net/kivra/issues/87)).
3. Permit is still an in-process Kotlin value, not a signed/opaque issuer-authenticated artifact. Real destination/authenticated principal/cancellation/replay grants must be tested. Never trust caller-provided `grantEpochDigest` as authentication.
4. [recl #59](https://github.com/kigawa-net/recl/issues/59) / [#61](https://github.com/kigawa-net/recl/issues/61) still require real durable ResultLookup, unknown reconciliation, retention and explicit NoEffect proof from the correct *model* transport (distinct from Kivra Memory Mutation).
5. Production `pinnedContextsV1`, `promptOutputBarrierV1` and `durableDispatchReceiptV1` remain **Disabled**.

See also [docs/prompt-send-start-journal.md](prompt-send-start-journal.md).
