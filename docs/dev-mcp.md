# Dev-only MCP launcher (recl #7)

Status: **development tool.** It serves the `:mcp` tool contract over stdio with an ephemeral
in-memory store so the protocol can be exercised before Fortis and a production authenticator
exist. It is not part of the production `:app` image and must never be deployed.

## Why a separate module

`:app` must not silently fall back to InMemory storage. `:dev-mcp` is a distinct Gradle module that
depends on `:storage-memory` on purpose, so the production application keeps failing closed. The
Dockerfile builds only `:app:installDist`.

## Configuration

All variables are required. The process refuses to start (exit code 2) otherwise.

| Variable | Meaning |
| --- | --- |
| `RECL_DEV_MCP_ALLOW_EPHEMERAL` | Must be exactly `1`. Acknowledges that every Context is lost on exit. |
| `RECL_DEV_MCP_TOKEN` | At least 16 non-blank characters. The launcher owns it; it is never read from a tool argument. |
| `RECL_DEV_MCP_TENANT_ID` | Tenant bound to this process. Non-blank, no whitespace. |
| `RECL_DEV_MCP_OWNER_ID` | Owner Principal bound to this process. Non-blank, no whitespace. |

The principal is fixed for the process lifetime. Tool arguments cannot select another tenant/owner;
unknown fields such as `tenantId` are rejected with `INVALID_ARGUMENT`.

## Build and run

```sh
./gradlew :dev-mcp:installDist

RECL_DEV_MCP_ALLOW_EPHEMERAL=1 \
RECL_DEV_MCP_TOKEN='change-me-0123456789' \
RECL_DEV_MCP_TENANT_ID='tenant-dev' \
RECL_DEV_MCP_OWNER_ID='owner-dev' \
  dev-mcp/build/install/dev-mcp/bin/dev-mcp
```

A warning is written to **stderr**; stdout carries only JSON-RPC lines.

## Register with Claude Code (`.mcp.json`)

```json
{
  "mcpServers": {
    "recl-dev": {
      "command": "/absolute/path/to/recl/dev-mcp/build/install/dev-mcp/bin/dev-mcp",
      "env": {
        "RECL_DEV_MCP_ALLOW_EPHEMERAL": "1",
        "RECL_DEV_MCP_TOKEN": "change-me-0123456789",
        "RECL_DEV_MCP_TENANT_ID": "tenant-dev",
        "RECL_DEV_MCP_OWNER_ID": "owner-dev"
      }
    }
  }
}
```

Do not commit a real token. The goal of this launcher is to verify `initialize`, `tools/list`,
`save_context`, `get_context` and `get_pinned_contexts` from a real client.

## Verified behavior

2026-11-05, Claude Code 2.1.21 (`-p --strict-mcp-config` with a temporary config, no user config
change): `save_context` then `get_context` completed over stdio and the client reported the stored
title. This verifies the protocol/tool path only; it does **not** provide durable session-crossing
memory, because storage is still in-memory.

## What still does not work

- Contexts do not survive a restart, so session-crossing retrieval is **not** satisfied.
- `search_context` returns `SEARCH_UNSUPPORTED` (no Qdrant).
- GitHub/source verification is not performed; submitted citations stay `UNVERIFIED`.
- There is no cross-process revocation, Fortis authority or production authentication.
- The token counter is a character heuristic, not a model tokenizer.

See [mcp-server.md](mcp-server.md) for the production gates.
