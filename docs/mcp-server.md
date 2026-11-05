# MCP server (first slice, recl #7)

Status: **protocol/tool contract and InMemory-backed tests only.** No launcher, durable storage,
production authenticator or semantic index is wired. Do not advertise recl as an available MCP
memory service yet.

## What is implemented

`:mcp` implements the MCP stdio shape used by Claude Code:

- newline-delimited JSON-RPC 2.0, one JSON object per line, UTF-8;
- `initialize` (protocol versions `2024-11-05`, `2025-03-26`, `2025-06-18`; unknown versions get
  `2025-06-18`), `notifications/initialized`, `ping`, `tools/list`, `tools/call`;
- notifications never produce a response; unknown methods return JSON-RPC `-32601`;
- each inbound line is capped at 1 MiB before UTF-8 decoding; an oversized line is rejected with
  `-32600` and is not executed or echoed.

The transport is `McpStdioTransport`; `McpContextServer` is transport-agnostic so tests can drive it
directly.

## Tools

| Tool | Status | Notes |
| --- | --- | --- |
| `search_context` | **Unsupported** | No Qdrant/semantic index exists. Returns `SEARCH_UNSUPPORTED`; it never substitutes a substring match or an index snapshot. |
| `get_context` | Implemented over the core reader | Exact UUIDv7 only. Missing, tombstoned, other-owner and other-tenant Contexts all return `NOT_FOUND`. |
| `get_pinned_contexts` | Implemented over the core pinned reader | `scope` is a relevance filter, never a permission. Requires `tokenBudget` (1..100000) and `maxContexts` (1..100). Partial results are never reported as success. |
| `save_context` | Implemented over the trusted mutation gateway | Caller supplies stable UUIDv7 `contextId` and `requestId`. Reusing `requestId` with a different payload fails. |
| `update_context` | Implemented over the trusted mutation gateway | Guarded by `expectedContentRevision`; stale revisions return `REVISION_CONFLICT` with `currentContentRevision`. |

`delete_context` and Supersede are deliberately absent. Supersede changes two authoritative keys
and stays blocked until an atomic batch primitive is validated (see `design.md` §6).

### Arguments

Arguments are camelCase like the REST adapter. Unknown fields and JSON `null` are rejected: omit a
field to leave it unchanged. `status=superseded` is rejected. `sensitivity=restricted` is parsed and
then rejected by core policy.

`sources` accepts at most 16 citation claims (`sourceId`, `type`, `uri`, optional `locator`,
`retrievedAtEpochMillis`, `contentDigest`, `trust`). Trust is a **claim about where the assertion
came from**, not verification. Submitted sources are stored `UNVERIFIED`; no client may promote
verification. A `CONFIRMED` Context that cites `agent_inferred` evidence is rejected.

`pinned` defaults to `false`. A pinned Context is only eligible for injection when it is `CONFIRMED`,
`public`/`internal`, inside its validity interval, and all of its sources are `VERIFIED`.

## Identity and authorization

- The credential is owned by the stdio launcher and injected into `McpContextTools`. It is **never**
  read from a tool argument, JSON-RPC field or environment value supplied by the model.
- Tenant and owner come only from the injected `SessionAuthenticator`. Tool arguments cannot select
  a tenant, owner, Principal, payload digest or verification state.
- Scopes are classification/relevance filters. They do not grant access.
- An unverified credential maps to `UNAUTHENTICATED`; an authenticated caller asking for another
  owner's or tenant's Context gets the same `NOT_FOUND` as a missing ID.

## Error shape

Known tools always answer with a `tools/call` result and `isError: true` for domain failures:

```json
{ "content": [ { "type": "text", "text": "{\"error\":\"REVISION_CONFLICT\",\"message\":\"…\",\"currentContentRevision\":2}" } ],
  "isError": true }
```

Codes: `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `NOT_FOUND`, `CONTEXT_ID_CONFLICT`,
`REQUEST_ID_REUSED`, `REVISION_CONFLICT`, `REJECTED_BY_POLICY`, `OUTCOME_UNKNOWN`, `UNAVAILABLE`,
`SEARCH_UNSUPPORTED`, `PINNED_UNSUPPORTED`, `PINNED_TOO_LARGE`, `PINNED_UNAVAILABLE`.

Protocol failures use JSON-RPC errors: `-32700` parse, `-32600` invalid request, `-32601` unknown
method, `-32602` invalid params/unknown tool, `-32603` internal (details are never echoed).

## Remaining production gates (recl #7, #11, #21)

1. **No launcher.** `app/Main.kt` does not start an MCP session. Production startup must fail closed
   without durable storage; an InMemory fallback must never happen silently.
2. **No production authenticator.** Verify issuer, audience, expiry, subject, tenant grants and
   revocation at this boundary; do not reuse a browser OIDC user session for an agent principal.
3. **No durable storage/index.** `storage-fortis` exists as a port-level adapter but the real Fortis
   KV binding is still open (#33/#69), and Qdrant is not implemented. `search_context` must stay
   unsupported until candidates are revalidated against authoritative storage.
4. **No prompt injection path.** `get_pinned_contexts` returns data through MCP; automatic prompt
   insertion is a separate operation gated by #54/#56 (Grant Epoch read admission, prompt output
   barrier). Do not treat these results as instructions.
5. **No cross-process revocation.** The injected authenticator is an in-process seam; live grant
   revocation needs the Fortis-side authority described in `docs/prompt-permit-grant-epoch.md`.
6. **No E2E claim.** Issue #7's completion condition (session-crossing retrieval from Claude Code)
   cannot be met with ephemeral storage.

See also [design.md](design.md) §9, [pinned-context.md](pinned-context.md) and
[rest-context-api.md](rest-context-api.md).
