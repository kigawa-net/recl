# MCP Production Readiness (recl #7)

> Status: **design only, not implemented.** This document assesses the current MCP implementation
> against production readiness criteria.

Date: 2026-11-05.

## 1. Status Summary

### Implemented Tools

| Tool | Status | Implementation |
|------|--------|----------------|
| `search_context` | **Disabled** | Tool defined, but explicitly disabled (no semantic index) |
| `get_context` | **Enabled** (test-only) | Implemented in `McpContextServer.handle()` |
| `get_pinned_contexts` | **Enabled** (test-only) | Implemented in `McpContextServer.handle()` |
| `save_context` | **Enabled** (test-only) | Implemented via `ContextService.create()` |
| `update_context` | **Enabled** (test-only) | Implemented via `ContextService.update()` |

### Transport/Packaging

| Aspect | Current State | Production Requirement |
|--------|---------------|------------------------|
| Protocol | stdio JSON-RPC 2.0 | ✅ Sufficient |
| Launcher | `:dev-mcp` with `RECL_DEV_MCP_ALLOW_EPHEMERAL=1` | ❌ Needs durable storage |
| Storage | InMemory (ephemeral) | ❌ Needs Fortis binding |
| Process monitoring | None | ❌ Needs health check / lifecycle |

## 2. Identity/Authorization

| Aspect | Current State | Production Requirement |
|--------|---------------|------------------------|
| Principal | Test-only authority | ❌ Needs OIDC / OAuth2 |
| Tenant derivation | Test-only | ❌ Needs authentication |
| Relationship to #21 | Not yet integrated | ⏸ Future work |
| Relationship to #41 | Not yet integrated | ⏸ Future work |

## 3. Durable Storage

### Current State

- `:storage-memory` is test-only
- `RECL_DEV_MCP_ALLOW_EPHEMERAL=1` gates ephemeral behavior
- No persistent storage binding

### Production Requirements

1. **Fortis binding:**
   - Must implement `ContextStorage` interface
   - Must verify Fortis #33 (public SDK) and #34 (KV auth)

2. **Storage Port isolation:**
   - InMemory adapter must remain test-only
   - Production must fail closed if Fortis unavailable

3. **Durable receipt:**
   - Operation receipts must be persisted
   - Replay window must be bounded

## 4. Capability Gate

The following capabilities must return `Disabled`/`Unsupported` in production:

| Capability | Reason |
|------------|--------|
| `search_context` | No semantic index implemented (Qdrant adapter not wired) |
| All storage operations | Fortis binding not verified |
| Authentication | No OIDC/OAuth2 implementation |
| Production-grade process lifecycle | No health check / monitoring |

## 5. Current Implementation

### Protocol Support

```kotlin
// McpContextServer.handle() supports:
- initialize (protocol version negotiation)
- ping
- tools/list (tool catalog)
- tools/call (tool invocation)
```

### Tool Catalog

```json
{
  "tools": [
    {
      "name": "search_context",
      "description": "Disabled: no semantic index",
      "annotations": {"readOnlyHint": true, "idempotentHint": true, "openWorldHint": false}
    },
    {
      "name": "get_context",
      "description": "Read one Context by exact UUIDv7",
      "annotations": {"readOnlyHint": true, "idempotentHint": true, "openWorldHint": false}
    },
    {
      "name": "get_pinned_contexts",
      "description": "Select caller's pinned contexts under token budget",
      "annotations": {"readOnlyHint": true, "idempotentHint": true, "openWorldHint": false}
    },
    {
      "name": "save_context",
      "description": "Create a Context",
      "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false}
    },
    {
      "name": "update_context",
      "description": "Apply partial update guarded by expectedContentRevision",
      "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false}
    }
  ]
}
```

## 6. Evidence

### Implemented Evidence

- ✅ MCP JSON-RPC 2.0 protocol framing
- ✅ Tool catalog with schema validation
- ✅ InMemory adapter implementation (test-only)
- ✅ `ContextService` integration for CRUD operations
- ✅ RequestId + digest idempotency

### Missing Evidence

- ⏸ Fortis binding verification (Fortis #33/#34)
- ⏸ Production-grade authentication (#21/#41)
- ⏸ Durable storage persistence
- ⏸ Process lifecycle management (health check, graceful shutdown)
- ⏸ Production E2E runs

## 7. Remaining Work Checklist

### From recl #7

- [ ] `search_context` - Requires Qdrant adapter implementation
- [ ] `get_context` - ✅ Implemented (test-only storage)
- [ ] `get_pinned_contexts` - ✅ Implemented (test-only storage)
- [ ] `save_context` - ✅ Implemented (test-only storage)
- [ ] `update_context` - ✅ Implemented (test-only storage)
- [ ] Tool schema documentation - ✅ Complete
- [ ] Claude Code E2E testing - ⏸ Requires production storage

### Additional Production Gates

- [ ] Implement Fortis binding (Fortis #33/#34)
- [ ] Implement OIDC/OAuth2 authentication (#21)
- [ ] Implement trusted Principal authorization (#41)
- [ ] Add health check endpoint
- [ ] Add graceful shutdown handling
- [ ] Add production monitoring (logs, metrics)
- [ ] Define production error handling policy
- [ ] Add rate limiting
- [ ] Add request size limits

## 8. Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| InMemory adapter used in production | Data loss on restart | Explicit gating + documentation |
| No authentication | Unauthorized access | Implement OIDC/OAuth2 (#21) |
| No durable storage | Lost context on restart | Implement Fortis binding |
| search_context disabled | Limited discovery | Implement Qdrant adapter |
| No process monitoring | Hard to debug failures | Add health check + logging |

## 9. Open Questions

1. **Storage migration path:** How to migrate from InMemory to Fortis without data loss?
2. **Authentication strategy:** OIDC vs OAuth2 vs API key?
3. **Rate limiting:** Should MCP have per-client rate limits?
4. **Error handling:** How to handle Fortis unavailability gracefully?
5. **Versioning:** How to handle protocol version changes?

## 10. Related

- [recl #7](https://github.com/kigawa-net/recl/issues/7) - MCP Server implementation
- [recl #21](https://github.com/kigawa-net/recl/issues/21) - Common capability gate
- [recl #41](https://github.com/kigawa-net/recl/issues/41) - Browser OIDC User Principal
- [docs/mcp-server.md](mcp-server.md) - MCP contract and dev launcher
- [docs/dev-mcp.md](dev-mcp.md) - Dev MCP launcher documentation
- [docs/bootstrap.md](bootstrap.md) - Module boundaries
- [docs/design.md](design.md) - §9 MCP minimum tools
