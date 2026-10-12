# Trusted Mutation Envelope (internal contract, version 1)

This document specifies the input boundary proposed in [#20](https://github.com/kigawa-net/recl/issues/20), before exposing recl's ContextService to MCP or REST clients.

## Trust boundary

- The client sends `contextId`, `requestId`, mutation fields and the expected semantic revision, never `tenantId`, `ownerId`, a trusted `Principal`, or a digest.
- `TrustedMutationGateway` asks `SessionAuthenticator` to **verify** the bearer credential and derive a trusted Principal. The current Core has **no production authenticator**; the existing tests inject a deterministic Fake. A real HTTP/MCP adapter must check issuer, audience, expiry, tenant grants and revocation before invoking the gateway.
- Context creation binds `tenantId` and `ownerId` to the verified principal. The current owner-only ACL is deliberately narrower than the planned role-based access policy.
- `contextId` and `requestId` must be valid UUIDv7 strings (canonical lower-case when stored), and the caller must reuse the same IDs after a lost response.
- Reject mutation inputs before they reach `ContextService` if the request ID or Context ID is invalid.

## Versioned digest

A digest is SHA-256 over a custom versioned, domain-separated binary envelope; **never** accept a client-supplied digest.

```text
string("domain", "recl.mutation.v3")
string("operation", create|update|delete)
string("tenant", authenticatedTenantId)
string("principal", authenticatedPrincipalId)
string("requestId", canonicalUUIDv7)
string("contextId", canonicalUUIDv7)
operation-specific fields ...
```

The `string`/optional long/optional set encoders write a fixed field name followed by a presence bit. UTF-8 strings are NFC-normalized and length-prefixed (signed big-endian 32-bit length), numbers use big-endian signed 64-bit integer encoding, and scope sets are NFC-normalized, deduplicated and sorted before writing.

`null` (unspecified PATCH field) and `""` (explicit empty PATCH field) receive different digests. Kotlin's current `ContextPatch` does **not** support explicit JSON `null` field-clearing: REST/MCP DTOs must define whether such requests are rejected or require a separate operation.

The digest binds the operation, target, revision and submitted fields. Version 2 additionally bound `pinned` on create and optional `patch.pinned` on update. The encoder version was advanced from `recl.mutation.v1` because changing a request digest while reusing the same domain version would invalidate stored replay receipts. Fortis durable receipt migration must explicitly account for previous versions. It is an **identity checksum**, not a signature. It does not authenticate a request or grant permissions.

## Idempotency and errors

- Same Context key + request ID + digest: replay the cached operation receipt when present.
- Same Context key + request ID + **different** digest: `RequestIdReused`.
- A CAS conflict requires re-reading the latest authoritative Context and re-evaluating ownership and expected semantic revision.
- A timeout after sending a write is `OutcomeUnknown` until authoritative evidence identifies the outcome. Receipt eviction means an old success might no longer be demonstrable; do not claim `NOT_APPLIED`.
- A request ID retained in only one Context key is **not** globally unique across all Contexts. Cross-resource replay prevention must be specified at the provider API boundary before multi-key operations.
- If a token is not verified, no Context mutation is accepted.
- Returning `InvalidRequest` here does not imply any public HTTP status mapping has been implemented.

## Remaining work / production gates

- Production `SessionAuthenticator`, Tenant grants, Agent service identity, revocation and audit.
- Versioned REST/MCP DTO schema and formal canonical encoder compatibility tests across different language clients.
- Fortis durable request receipt persistence, recovery, CAS and idempotent request-result lookup under [#14](https://github.com/kigawa-net/recl/issues/14).
- Bounded receipt retention / expiration, request-replay window policy, no silent deletion of proof.
- Scope ownership and authorization for search/pinned Context. An internal owner-scoped Pinned reader exists in the test-only Core and requires an authoritative complete scan; production Fortis enumeration and token budget verification remain blocked (#26 / Fortis #45).
- [Kivra contract compatibility #21](https://github.com/kigawa-net/recl/issues/21): this gateway does not make the provider API operational.

This is an internal API hardening step, **not** evidence that recl's production REST/MCP service or Fortis Adapter exists.


## v3 policy fields (Core, recl #28)
- Create digests now bind `sensitivity`, `validFromEpochMillis`, and `validUntilEpochMillis`; update digests bind the optional corresponding patch fields.
- `RESTRICTED` Context bodies are rejected, `CONFIDENTIAL` is not auto-injected into pinned prompts, and valid-time uses a trusted Clock.
- Historical v1/v2 receipt digests are **not equivalent** to v3. See [validity and sensitivity](validity-sensitivity.md) and do not migrate persistent receipts without replay/version tests.
