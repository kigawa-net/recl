# Authoritative Context Direct-Get

Status: internal Core implementation for [recl #24](https://github.com/kigawa-net/recl/issues/24), not a public REST/MCP API.

```text
Caller bearer credential
  -> SessionAuthenticator (must be supplied by a future verified server)
  -> TrustedContextQueryGateway.get(credential, uuidV7Id)
  -> ContextReader.get(trustedPrincipal, canonicalContextId)
  -> ContextStorage.get(tenant, id)  [authoritative; Fortis later]
  -> Owner / Tenant / Tombstone checks
  -> ContextSnapshot (semantic fields ONLY)
```

The direct lookup never consults Qdrant and is independent of a stale or missing search index. Query results exclude `storageRevision`, `indexedContentRevision`, `indexAction`, `operationReceipts`, and internal owner/tenant fields. An authenticated but unauthorized principal receives the same `NotFound` as a missing/tombstoned Context. An unauthenticated caller receives `Forbidden`, and malformed UUIDv7 is `InvalidRequest`.

The current Core policy is **owner-only**. The owner can fetch historical `superseded` or `archived` Context via explicit ID. This is deliberately distinct from automatic search and pinned-context injection, which must filter lifecycle, validity, sensitivity, ACL and token budget.

Security limitations:
- `SessionAuthenticator` is a test seam. **There is no production token/OIDC verifier or REST/MCP server yet**.
- InMemoryContextStorage is not durable. No production fallback to it is allowed.
- Fortis Adapter must provide linearizable authoritative reads before this code is used in production ([recl #14](https://github.com/kigawa-net/recl/issues/14), [Fortis #44](https://github.com/kigawa-net/fortis/issues/44)).
- Future access policy must include verified grants; search scopes never confer read permission.
- Direct-Get cannot yet enforce temporal-validity windows, document-level sensitivity or revoke service tokens, since those features are not implemented in the Context Aggregate.

Next: add pinned/selection reads via an authoritative enumeration or durable index with proven consistency; an incomplete Qdrant result set is not an authoritative pinned list.
