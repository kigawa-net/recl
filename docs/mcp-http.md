# MCP over HTTP

`:app` exposes a stateless Streamable HTTP endpoint at **POST /mcp**, backed by the existing `:mcp` tools. The deployed host is `https://recl.kigawa.net`; deployment alone does not enable MCP. Without the environment gates below, `/mcp` returns 404. This change does not deploy or configure the running service.

## Authentication modes

| `RECL_MCP_AUTH` | Authentication | Intended use |
| --- | --- | --- |
| unset or `token` | Constant-time comparison of `RECL_MCP_TOKEN`; fixed tenant and owner | Local development |
| `oidc` | Signed JWT verified against the issuer's JWKS; fixed tenant and per-user identity | OAuth clients such as OpenCode |
| any other value | Startup rejected | Configuration error |

## Environment

| Variable | Required value when enabled |
| --- | --- |
| `RECL_MCP_ENABLED` | `1` enables MCP; unset or any other value leaves it disabled |
| `RECL_MCP_AUTH` | `token` (default) or `oidc` |
| `RECL_MCP_TOKEN` | Token mode only: secret Bearer token, at least 32 characters without whitespace; use a random ASCII token |
| `RECL_MCP_TENANT_ID` | Non-blank tenant ID without whitespace |
| `RECL_MCP_OWNER_ID` | Token mode only: non-blank owner ID without whitespace |
| `RECL_MCP_ALLOW_EPHEMERAL` | `1` explicitly acknowledges storage loss on restart |

OIDC mode also accepts:

| Variable | Meaning |
| --- | --- |
| `RECL_OIDC_ISSUER` | Required exact issuer URL, e.g. `https://user.kigawa.net/realms/kigawa-net`; HTTPS required except HTTP on `localhost` or `127.0.0.1` for tests |
| `RECL_OIDC_JWKS_URI` | Optional; defaults to issuer + `/protocol/openid-connect/certs`; same HTTPS / test loopback requirement |
| `RECL_OIDC_AUDIENCE` | Optional exact member required in JWT `aud` |
| `RECL_OIDC_REQUIRED_SCOPE` | Optional single scope required in the space-separated JWT `scope` claim |
| `RECL_PUBLIC_URL` | Optional public resource base URL, e.g. `https://recl.kigawa.net`; HTTPS / test loopback required; enables metadata discovery when configured |

OIDC mode does not require `RECL_MCP_TOKEN` or `RECL_MCP_OWNER_ID`.

Invalid configuration when enabled refuses startup with exit code 2 and a reason on stderr. Credentials are not printed. Supply the token through your secret manager in production; do not put it in source control or enable shell tracing.

## Start and call with a local token

After `./gradlew :app:installDist` with JDK 21:

```sh
export RECL_MCP_ENABLED=1
export RECL_MCP_AUTH=token
export RECL_MCP_TOKEN="$(openssl rand -hex 32)"
export RECL_MCP_TENANT_ID=example-tenant
export RECL_MCP_OWNER_ID=example-owner
export RECL_MCP_ALLOW_EPHEMERAL=1
PORT=8080 app/build/install/app/bin/app
```

In another shell with the same token available (use the HTTPS base URL for a configured remote service):

```sh
export RECL_MCP_URL=http://127.0.0.1:8080/mcp
curl "$RECL_MCP_URL" \
  -H "Authorization: Bearer $RECL_MCP_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

curl "$RECL_MCP_URL" \
  -H "Authorization: Bearer $RECL_MCP_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

The endpoint returns JSON responses with HTTP 200 and notifications with HTTP 202 and an empty body. It does not create sessions or provide an SSE stream. GET, DELETE and other methods return 405 with `Allow: POST`. Missing or invalid Bearer credentials return 401 with `WWW-Authenticate: Bearer` before executing JSON-RPC. Content-Type must be `application/json` (parameters such as `charset=utf-8` are allowed). Bodies over 1 MiB return 413; all responses use `Cache-Control: no-store`.

## Keycloak and OIDC

Example environment (the audience must match the access token issued by your realm):

```sh
export RECL_MCP_ENABLED=1
export RECL_MCP_AUTH=oidc
export RECL_MCP_ALLOW_EPHEMERAL=1
export RECL_MCP_TENANT_ID=example-tenant
export RECL_OIDC_ISSUER=https://user.kigawa.net/realms/kigawa-net
export RECL_PUBLIC_URL=https://recl.kigawa.net
export RECL_OIDC_AUDIENCE=recl
# Optional dedicated scope, configured and issued by Keycloak:
# export RECL_OIDC_REQUIRED_SCOPE=recl:access
PORT=8080 app/build/install/app/bin/app
```

The default JWKS URL is `https://user.kigawa.net/realms/kigawa-net/protocol/openid-connect/certs`. Signatures allow RS256/384/512, PS256/384/512 and ES256/384/512. Verification requires exact `iss`, an unexpired `exp`, a valid `nbf` when present, and `iat` no more than 60 seconds ahead when present. Unsigned tokens are rejected. The user ID is `sub`, falling back to `preferred_username` only when `sub` is empty. The configured tenant applies to all users; request arguments cannot select another tenant.

JWKS are fetched lazily and cached for five minutes. An unknown `kid` triggers one refresh, limited to once per 30 seconds. Connection and request timeouts are five seconds. Failed fetches, malformed JWKS and missing keys fail authentication; token, claim and JWKS contents are not logged.

Keycloak already supports Dynamic Client Registration (DCR) at `https://user.kigawa.net/realms/kigawa-net/clients-registrations/openid-connect`, discoverable through `https://user.kigawa.net/realms/kigawa-net/.well-known/openid-configuration`. When its registration policies permit the client's registration request, OpenCode can register dynamically and no manual client registration or `client_id` configuration is needed. Supporting DCR alone does not guarantee anonymous registration: trusted hosts, redirect URIs and allowed scopes must satisfy realm policy. Otherwise pre-register a public client with authorization code flow, PKCE and the chosen loopback callback. See [Keycloak client registration](https://www.keycloak.org/securing-apps/client-registration).

## OAuth discovery

With OIDC and `RECL_PUBLIC_URL` configured, an unauthenticated MCP request receives:

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer resource_metadata="https://recl.kigawa.net/.well-known/oauth-protected-resource"
```

`GET` or `HEAD` on `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` (also with a trailing slash) serves the same protected resource metadata. Other methods return 405. Example JSON:

```json
{
  "resource": "https://recl.kigawa.net",
  "authorization_servers": ["https://user.kigawa.net/realms/kigawa-net"],
  "bearer_methods_supported": ["header"],
  "resource_name": "recl",
  "scopes_supported": ["recl:access"]
}
```

`scopes_supported` is empty when no required scope is configured. Metadata is unavailable (404) when OIDC is disabled. Without `RECL_PUBLIC_URL`, metadata also returns 404, the challenge is simply `Bearer`, and clients must be configured with the authorization server explicitly.

## OpenCode

For OIDC, the remote server entry is:

```json
{
  "type": "remote",
  "url": "https://recl.kigawa.net/mcp",
  "oauth": { "scope": "openid" }
}
```

If you configure a dedicated required scope, request it as well, for example `"scope": "openid recl:access"`. An audience mapper in Keycloak must supply `recl` when using the example audience above.

The following full example uses the OpenCode V2 `mcp.servers` layout; older versions use `mcp.recl` instead. Run `opencode mcp auth recl` to sign in. See [OpenCode MCP configuration](https://dev.opencode.ai/v2/docs/mcp-servers/).

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "recl": {
        "type": "remote",
        "url": "https://recl.kigawa.net/mcp",
        "oauth": { "scope": "openid" }
      }
    }
  }
}
```

If DCR is unavailable, use a pre-registered public client with the matching callback (for this example, `http://127.0.0.1:19876/callback`). Server entry:

```json
{
  "type": "remote",
  "url": "https://recl.kigawa.net/mcp",
  "oauth": {
    "client_id": "recl-opencode",
    "callback_port": 19876,
    "redirect_uri": "http://127.0.0.1:19876/callback",
    "auth_server_metadata_url": "https://user.kigawa.net/realms/kigawa-net/.well-known/openid-configuration",
    "scope": "openid"
  }
}
```

For local static token mode, set `RECL_MCP_TOKEN` in the OpenCode environment and use this server entry:

```json
{
  "type": "remote",
  "url": "http://127.0.0.1:8080/mcp",
  "headers": { "Authorization": "Bearer {env:RECL_MCP_TOKEN}" },
  "oauth": false
}
```

## Production status and limitations

This is an explicitly gated temporary HTTP integration, not durable production memory. In-memory Context and request deduplication records disappear on restart and are not shared between replicas. `search_context` is disabled; use exact UUIDv7 IDs with `get_context` or `get_pinned_contexts` instead. The pinned-context budget counts `title.length + summary.length + content.length`; this character heuristic is not a model tokenizer.

Fortis remote storage bindings are not implemented. OIDC JWT verification is implemented, but live Keycloak/OpenCode interoperability, realm audience/scope mappings and production authorization policy still need deployment validation. Token mode uses a single fixed server-side Principal (tenant and owner from the environment) for all calls authenticated with the shared token. Tenant and owner cannot be selected by request arguments. Use HTTPS for remote access and treat the token as granting that Principal's access to every enabled tool.

Without `RECL_OIDC_AUDIENCE`, tokens issued for other clients in the same realm may also be accepted. For production, configure `RECL_OIDC_AUDIENCE` or a dedicated `RECL_OIDC_REQUIRED_SCOPE`; `openid` alone does not isolate recl access. JWT verification does not provide immediate revocation or token introspection.

The default remains fail-closed. Removing the ephemeral acknowledgement gate for normal durable production operation requires implementing and wiring Fortis; setting `RECL_MCP_ALLOW_EPHEMERAL=1` only opts into temporary storage and does not provide persistence. The dashboard reports `modules.mcp` as `running` and `modules.mcpStorage` as `in-memory (ephemeral)` only when MCP is wired; both are `not_configured` otherwise. These are wiring states, not external service health checks.
