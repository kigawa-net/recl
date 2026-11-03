# Context CRUD REST API (first slice, recl #4)

Status: **test-only / not production-operational.** This document describes the first REST slice implemented in the `:api-rest` module. It exposes Context create, read, update and delete over HTTP on top of the core `TrustedMutationGateway`, `TrustedContextQueryGateway`/`ContextReader` and `ContextService`.

**Editing via this API is not enabled in production.** The module has no production bearer verifier, no Fortis storage adapter, and no launcher wired into `app/Main.kt`. Its tests use `InMemoryContextStorage` (ephemeral, JVM-only) and a fake `SessionAuthenticator`. Passing these tests does **not** make Context editing production-ready. Console mutations must stay disabled until the gates in "Not implemented" below are met (see [trusted-mutation-envelope.md](trusted-mutation-envelope.md) and [authoritative-direct-get.md](authoritative-direct-get.md)).

## Endpoints

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| `POST` | `/contexts` | Create a Context (client supplies `contextId`, UUIDv7) | `201` (new) or `200` with `replayed: true` |
| `GET` | `/contexts/{contextId}` | Read a Context | `200` |
| `PATCH` | `/contexts/{contextId}` | Partial update, guarded by `expectedContentRevision` | `200` |
| `DELETE` | `/contexts/{contextId}?requestId=…&expectedContentRevision=…` | Tombstone delete | `200` |
| `GET` | `/contexts/pinned?scope=…&tokenBudget=…&maxContexts=…` | Pinned Context selection for the caller (see below) | `200` |

Unknown paths return `404`. Wrong methods return `405` with an `Allow` header (`GET` for `/contexts/pinned`).

### Pinned Context (`GET /contexts/pinned`)

Internal, test-only slice. It exposes the core `TrustedPinnedQueryGateway` / `PinnedContextReader` and **must not be used for automatic prompt injection in production**; see the blockers below and [pinned-context.md](pinned-context.md).

- Query: `tokenBudget` (required, integer 1..100000), `maxContexts` (required, integer 1..100), `scope` (optional, repeatable, at most 32 values, each non-blank and at most 256 characters). Any other parameter, a duplicate `tokenBudget`/`maxContexts`, or a missing required parameter is `400 INVALID_FIELD`. There is no client-side `maxItems`: the enumeration bound is a server-side reader setting (default 10 000).
- Owner and tenant come only from the authenticated Principal. `scope` is a **relevance filter**, not an authorization boundary. `global` contexts are returned for any scope query, but only within the caller's own tenant.
- Only contexts that the core considers eligible for automatic injection are returned: owned by the caller, `pinned`, `confirmed`, not tombstoned, `public` or `internal` sensitivity, inside the validity interval at the server clock, and with every source `VERIFIED` (not UNVERIFIED, STALE or INVALID). Other owners' and other tenants' contexts never appear.
- Success `200`:

```json
{ "contexts": [ { "id": "019a…", "kind": "decision", "status": "confirmed", "title": "…", "summary": "…",
                  "content": "…", "scopes": ["project:recl"], "contentRevision": 2, "sensitivity": "internal",
                  "validFromEpochMillis": null, "validUntilEpochMillis": null } ],
  "usedTokens": 120, "limited": false }
```

  Contexts are ordered by `id` ascending. `usedTokens` is the token count of the selected items as computed by the server's injected counter. `limited: true` means some otherwise eligible contexts were skipped because of `tokenBudget` or `maxContexts`. Citation `sources` are **not** returned in this slice.
- A failed enumeration or read never returns a partial `contexts` array. Failures are `503` with a distinct code:

| HTTP | `error` | When |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` | No or unverified bearer credential |
| 400 | `INVALID_FIELD` | Invalid or unknown query parameter |
| 503 | `PINNED_UNSUPPORTED` | The storage port does not provide a complete authoritative enumeration |
| 503 | `PINNED_TOO_LARGE` | The tenant has more Contexts than the enumeration bound |
| 503 | `PINNED_UNAVAILABLE` | Enumeration, a per-ID read, or a token count could not complete |

- Known limits: the response is a point-in-time view and not a global atomic snapshot (a Context created or pinned during the request may be absent). The default `InMemoryContextStorage` enumeration is test-only. Enumeration is capped at 10 000 objects per tenant in a single response, so large tenants currently get `PINNED_TOO_LARGE`.

Pinned writes are not exposed: `pinned` is still rejected as an unknown field on create and update.

## Authentication

- Every request needs `Authorization: Bearer <credential>`. A missing, non-Bearer or unverified credential returns `401` with `WWW-Authenticate: Bearer`.
- The credential is verified by the injected `SessionAuthenticator`. The client never sends `tenantId`, `ownerId`, a Principal, a digest or a verification state. Any such field is rejected with `400`.
- **Implemented:** the injection seam and the wiring into the core gateways.
- **Not implemented:** a production verifier (issuer, audience, expiry, tenant grants, revocation). The only authenticators are test fakes.

## Request format

- Bodies are JSON objects, at most 1 MiB (`413` above that).
- **Unknown fields are rejected** (`400 INVALID_FIELD`).
- **JSON `null` is rejected** for every field (`400 INVALID_FIELD`). Clearing a field is not supported; omit the field to leave it unchanged. Core `ContextPatch` cannot express field clearing.
- Identifiers must be UUIDv7 (`400 INVALID_ID`). Uppercase input is accepted and canonicalised to lowercase.
- `kind`: `decision`, `architecture`, `constraint`, `task`, `finding`, `reference`, `preference`.
- `status`: `proposed`, `confirmed`, `archived`. `superseded` is **rejected** (`400`) because Supersede (#9) is not implemented.
- `sensitivity`: `public`, `internal`, `confidential`. `restricted` is rejected by core policy (`422`).
- `scopes`: array of strings. Scopes never grant access.
- `validFromEpochMillis` / `validUntilEpochMillis`: integers (inclusive / exclusive UTC epoch milliseconds).

### Create (`POST /contexts`)

Required: `contextId`, `requestId`, `kind`, `status`, `title`, `summary`, `content`.
Optional: `scopes` (default `[]`), `sensitivity` (default `internal`), `validFromEpochMillis`, `validUntilEpochMillis`.

```json
{ "contextId": "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b",
  "requestId": "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6c",
  "kind": "decision", "status": "confirmed",
  "title": "Use Fortis as source of truth", "summary": "…", "content": "…",
  "scopes": ["project:recl"] }
```

### Update (`PATCH /contexts/{contextId}`)

Required: `requestId`, `expectedContentRevision` (integer ≥ 1). At least one of `title`, `summary`, `content`, `status`, `scopes`, `sensitivity`, `validFromEpochMillis`, `validUntilEpochMillis` must be present. An empty patch is `400`. A blank `title` is `400`.

### Delete (`DELETE /contexts/{contextId}`)

No body. Query parameters `requestId` and `expectedContentRevision` are required. Duplicate or unknown query parameters are `400`.

## Responses

Mutation success:

```json
{ "contextId": "019a…", "contentRevision": 2, "replayed": false }
```
Delete success adds `"deleted": true`. `replayed: true` means this `requestId` was already applied to this Context and the stored result was returned.

Read success returns the semantic fields only: `id`, `kind`, `status`, `title`, `summary`, `content`, `scopes` (sorted), `contentRevision`, `sensitivity`, `validFromEpochMillis`, `validUntilEpochMillis` (absent values are `null` in output). Storage revisions, receipts, index bookkeeping, owner and tenant are never returned. Citation `sources` and `pinned` are not part of this slice.

Errors use a flat body: `{ "error": "<CODE>", "message": "…" }`, plus `currentContentRevision` on `REVISION_CONFLICT`.

| HTTP | `error` | When |
| --- | --- | --- |
| 400 | `INVALID_JSON` | Body is not a UTF-8 JSON object |
| 400 | `INVALID_FIELD` | Unknown field, JSON null, wrong type, missing required field, blank title, empty patch, `superseded`, bad query string |
| 400 | `INVALID_ID` | `contextId` or `requestId` is not UUIDv7 |
| 401 | `UNAUTHENTICATED` | No or unverified bearer credential |
| 404 | `NOT_FOUND` | Missing, tombstoned, **or owned by another owner/tenant** (see below) |
| 405 | `METHOD_NOT_ALLOWED` | Method not supported on the resource |
| 409 | `CONTEXT_ID_CONFLICT` | `POST` with a `contextId` that already exists for this tenant (different request, or another owner) |
| 409 | `REVISION_CONFLICT` | `expectedContentRevision` does not match; body has `currentContentRevision` |
| 409 | `REQUEST_ID_REUSED` | Same `requestId` on the same Context with a different payload |
| 413 | `PAYLOAD_TOO_LARGE` | Body over 1 MiB |
| 422 | `REJECTED_BY_POLICY` | Core policy rejection, e.g. `restricted` sensitivity or an invalid validity interval |
| 503 | `OUTCOME_UNKNOWN` | Write outcome not known (see below) |
| 503 | `PINNED_UNSUPPORTED`, `PINNED_TOO_LARGE`, `PINNED_UNAVAILABLE` | `GET /contexts/pinned` could not produce a complete result (see above) |
| 503 | `UNAVAILABLE` | Storage unavailable |
| 500 | `INTERNAL_ERROR` | Unexpected error (no details returned) |

## Concurrency and idempotency

- **Optimistic locking:** `expectedContentRevision` must equal the current `contentRevision`, otherwise `409` with the current value. The core re-reads and re-authorises inside its CAS retry loop. Only the single-key `ContextStorage.compareAndSwap` provides atomicity; there is no multi-key transaction.
- **Idempotency:** `requestId` plus the server-computed canonical digest identify a write. The same `requestId` with the same payload returns the stored result (`replayed: true`) without a second write. The same `requestId` with a different payload is `409 REQUEST_ID_REUSED`.
- Operation receipts are retained per Context up to the core limit (32). An evicted receipt cannot be proven, so replaying a very old request is not guaranteed to be recognised.
- A `requestId` is unique only within one Context key. It is not a global cross-resource identity.

### OutcomeUnknown

A timeout, lost response or storage fault after a write may leave the outcome unknown. The API returns `503 OUTCOME_UNKNOWN` and **does not say "not applied"**. The recovery is to retry with the **same `requestId` and the same body**:

- If the write committed, the core finds the receipt and returns `200 replayed: true`.
- If it did not commit, the retry performs the write (or returns the usual conflict if the revision moved).

Clients must not treat `503` as proof of non-application, and must not generate a new `requestId` for a retry of the same intent.

## Authorization and existence leaks

Policy is **owner-only** (core `ContextReader` and `ContextService`): a Context is visible and mutable only to the same tenant and the same owner.

- An authenticated caller who is not the owner receives the same `404 NOT_FOUND` body as for a missing Context, on read, update and delete. Tests compare these bodies for equality.
- A different tenant's `contextId` behaves like a missing one.
- The only case where a non-owner sees a conflict is `POST` with an existing `contextId` in the **same tenant**, which returns `409 CONTEXT_ID_CONFLICT` and does not change the existing Context. Context keys are tenant-scoped, so another tenant may create the same UUID independently.
- Unauthenticated requests return `401` before any Context lookup. A malformed `contextId` path segment returns `400` before authentication, which discloses nothing about stored data.

## Implementation pointers

- Module: `api-rest/` (`ContextRestApi.kt`: routing, strict JSON parsing, error mapping; `startContextRestServer` starts a JDK `com.sun.net.httpserver.HttpServer`).
- JSON: `kotlinx-serialization-json` 1.9.0 (parsing into `JsonObject`; no code generation).
- Tests: `api-rest/src/test/.../ContextRestApiTest.kt` run an HTTP server on a loopback ephemeral port against `InMemoryContextStorage` and a fake authenticator. A fault-injecting storage wrapper covers lost responses before and after commit.

## Not implemented / not claimed

- **Production authentication:** no OIDC/JWT verifier, no tenant grants, no service identity, no revocation, no audit log.
- **Durable storage:** no Fortis Storage Adapter. `InMemoryContextStorage` is ephemeral and test-only. Production must fail closed rather than fall back to it. Fortis linearizable reads and conditional writes are gated by [#14](https://github.com/kigawa-net/recl/issues/14).
- **Entry point:** `app/Main.kt` is not changed; nothing starts this server in production.
- **Supersede** (#9): not supported; `status: superseded` is rejected. No two-key atomicity is claimed.
- **Pinned Context writes** (`pinned` on create/update) and **automatic injection** into prompts: not exposed. `pinned` is still rejected as an unknown field. `GET /contexts/pinned` is implemented (test-only, see above) but automatic Pinned injection must not be enabled in production until the blockers in [pinned-context.md](pinned-context.md) are closed: a production `SessionAuthenticator`, a verified Fortis enumeration/CAS/read capability (recl #14), trusted source attestation and full RBAC, and a pagination or atomic-index design for large tenants.
- **Citation sources in Pinned results**: not returned.
- **Pinned authorization admission**: the `TrustedPinnedReadAdmission` port is not wired into this endpoint (the reader is constructed without it).
- **Search / index**: no Qdrant projection, no `indexedContentRevision` exposure, no index reconciliation driven by this API.
- **Citation sources**: not accepted or returned.
- **Field clearing via JSON null**: unsupported by design (400).
- **Duplicate JSON object keys**: not rejected. `kotlinx-serialization-json` 1.9.0 keeps the last value; the server then validates the resulting values, but this is a known gap.
- **Request result lookup endpoint**, rate limiting, TLS, CORS, MCP transport, request-replay window policy: not implemented.
- **Atomicity:** this slice does not claim more than the single-key CAS of the storage port.
