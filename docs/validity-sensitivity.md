# Temporal Validity and Sensitivity for Context injection

Status: **internal Core / InMemory tests**, implementing [recl #28](https://github.com/kigawa-net/recl/issues/28). A production policy engine, Fortis adapter, REST/MCP endpoint, and actual Model Prompt Tokenizer are **not** available.

## Domain contract

- Context `Sensitivity`: `PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED`, aligned with Kivra's Memory model.
- `RESTRICTED`: no body is persisted to recl in the MVP. Constructor enforces rejection and Trusted Mutation Gateway returns `InvalidRequest`; this is a storage policy, not cryptographic redaction.
- `CONFIDENTIAL`: an authenticated Owner may explicitly retrieve the Context with Direct Get, but it is **never injected automatically** through the pinned selection path.
- `PUBLIC` and `INTERNAL`: eligible for automatic pinned injection only after all other ACL, status, scope and time filters pass.
- Default `sensitivity=INTERNAL`. Future RBAC and token grants must determine whether a remote agent may request even `INTERNAL` data.
- `validFromEpochMillis` is inclusive; `validUntilEpochMillis` is exclusive. Both are optional UTC Unix milliseconds. With both present, `from < until`. This is **application valid-time**, not Fortis MVCC revision or transaction recording timestamp.
- `ContextPatch` uses `null` to mean **unspecified**, not clearing the existing validity bound. A future public DTO must introduce explicit clear operations if needed. The current Domain does not support clearing a bound.
- Validity/sensitivity changes are semantic mutations, so ordinary CAS mutation increments `contentRevision` and regenerates `indexIntent`. Invalid combined validity on a PATCH is a typed `InvalidRequest`, not a partial write.
- Automatic selection uses an injected trusted JVM `Clock` evaluated against each latest authoritative Context. The caller does not provide a "now" to bypass expiration.
- Explicit authorized Direct Get can read expired, Superseded, Archived, and Confidential historical records; pinned auto-selection is strictly narrower. Returned ContextSnapshot carries sensitivity/time metadata but not physical CAS tokens, receipt history or index state.

## Digest versioning

Internal canonical domain-separated request digest changes to `recl.mutation.v3`. The digest includes new sensitivity and validity fields for both CREATE and PATCH; otherwise the same requestId could be replayed after changing the meaning of the write.

**Existing `recl.mutation.v1/v2` receipt strings cannot be interpreted as v3 without a versioned migration.** Persistent Fortis receipts are not yet implemented: future deployments must version the receipt schema and preserve replay safety across upgrades. The externally proposed `recl.memory.v1` Provider REST contract is a *different* version namespace.

## Security and consistency limits

- The in-memory snapshot/scan plus separate per-key reads does not provide one globally atomic read over authorization and response. Authorization may change after last re-read; production requires bounded response semantics and verified revocation control.
- No policy approval or explicit opt-in exists for Confidential automatic injection; therefore it stays blocked.
- RESTRICTED must also be filtered/redacted by Kivra before requests reach recl. A server-side restriction is defense in depth, not a reason to send secrets to the server.
- Basic typed, unverified Source Provenance is implemented by recl #30, but no independent SourceVerifier, prompt-injection quarantine or tokenizer-level access control has been implemented.
- Pinned production remains gated on Fortis [#45](https://github.com/kigawa-net/fortis/issues/45) Scan, recl [#14](https://github.com/kigawa-net/recl/issues/14) capability verification and [#26](https://github.com/kigawa-net/recl/issues/26) RBAC/tokenizer/transport.


## Subsequent canonical request format
The validity/sensitivity fields were introduced under `recl.mutation.v3`. Source metadata extended the internal canonical digest in `recl.mutation.v4` (recl #30). Old v1/v2/v3 receipts remain non-interchangeable with v4, and durable migration is not implemented.
