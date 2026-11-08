# recl

recl is a **development context repository** for coding agents. It stores durable project decisions, architecture, constraints, findings, and provenance so that agents such as Claude Code can retrieve the relevant context across sessions.

> **Status: initial Core implementation / bootstrap.** The repository does not yet provide a production-ready service. A test-only REST adapter, an environment-gated HTTP MCP launcher with OIDC JWT or local static Bearer authentication and ephemeral storage, and a Fortis port-level adapter exist. The real Fortis binding and Qdrant indexer are not implemented; production OIDC policy and client interoperability still require deployment validation.

## Architecture

```text
Claude Code / other agents
            |
         MCP client
            |
      recl MCP / REST API
            |
       Context Service
            |
        Storage Port
           /   \
          /     \
   Fortis Adapter  InMemory Adapter (tests / gated ephemeral MCP)
          |
       Fortis KV  <-- source of truth
          |
    Index Reconciler --> Embedding provider --> Qdrant
                                           (derived search index)
```

- **Fortis** is the intended authoritative store for Context, lifecycle, scope/ACL, revisions, and pending index intent.
- **Qdrant** is a rebuildable, eventually consistent search index, **not** an authorization boundary.
- **Storage Port** is the abstraction layer between the service and the storage backend.

## Design documentation

| Topic | Location | Status |
|-------|----------|--------|
| [Architecture overview](docs/design.md) | `docs/design.md` | ✅ Complete |
| [Context domain model](docs/design.md#3-context-domain) | `docs/design.md` | ✅ Complete |
| [Revision model](docs/design.md#4-revision-model) | `docs/design.md` | ✅ Complete |
| [Storage Port interface](docs/design.md#5-storage-port) | `docs/design.md` | ✅ Complete |
| [Context mutation protocol](docs/design.md#6-context-mutation-protocol) | `docs/design.md` | ✅ Complete |
| [Search and authorization](docs/design.md#7-search-and-authorization-protocol) | `docs/design.md` | ✅ Complete |
| [Index reconciliation](docs/index-reconciliation.md) | `docs/index-reconciliation.md` | ✅ MVP |
| [MCP tools catalog](docs/design.md#9-mcp-minimum-tools) | `docs/design.md` | ✅ MVP |
| [Bootstrap module layout](docs/bootstrap.md) | `docs/bootstrap.md` | ✅ Complete |

### Core contracts and evidence

| Document | Issue | Status |
|----------|-------|--------|
| [Design](docs/design.md) | #1 | ✅ Complete |
| [Storage Port](docs/design.md#5-storage-port) | #15 | ✅ Complete |
| [Context mutation](docs/design.md#6-context-mutation-protocol) | #16 | ✅ Complete |
| [Core contract evidence](docs/core-contract-evidence.md) | #15/#16 | ✅ Evidence matrix |

### Index and search

| Document | Issue | Status |
|----------|-------|--------|
| [Index reconciliation](docs/index-reconciliation.md) | #5/#10/#13 | ✅ Test slice |
| [Qdrant adapter design](docs/qdrant-adapter-design.md) | #5 | ✅ Design only |

### MCP and API

| Document | Issue | Status |
|----------|-------|--------|
| [MCP server](docs/mcp-server.md) | #7 | ✅ Test-only |
| [MCP production readiness](docs/mcp-production-readiness.md) | #7 | ✅ Readiness assessment |
| [REST API](docs/rest-context-api.md) | #4 | ✅ Test-only |

### Storage and providers

| Document | Issue | Status |
|----------|-------|--------|
| [Fortis binding](docs/fortis-binding-assessment.md) | #2/#3/#14 | ⏸ Blocked |
| [Production capability gate](docs/production-capability-gate.md) | #65 | ✅ Capability inventory |

## Consistency and failure rules

1. A Context write succeeds only after durable authoritative storage commit. A Qdrant update is asynchronous.
2. A logical Context update and its index intent must be persisted atomically (MVP proposal: one authoritative aggregate key).
3. Concurrent writes must use real storage CAS; GET followed by PUT is **not** CAS.
4. Timeout can mean **OutcomeUnknown**. Request identity and payload digest are required to reconcile retries; never assume failure or success.
5. Qdrant results must be revalidated against the authoritative Context and current authorization before returning them.
6. Deletion uses a tombstone; Qdrant deletion must be reconciled before tombstone garbage collection.
7. Watch notifications can accelerate indexing, but durable enumeration/reconciliation is still required.
8. Features not supported and validated by Fortis must not be advertised as operational in production.

## Current dependencies and implementation roadmap

| Work | GitHub Issue |
| --- | --- |
| Design documentation | [#1](https://github.com/kigawa-net/recl/issues/1) |
| Fortis integration and Qdrant environment | [#2](https://github.com/kigawa-net/recl/issues/2) |
| Storage Port and test adapter | [#15](https://github.com/kigawa-net/recl/issues/15) |
| Context CAS, idempotency and UNKNOWN outcomes | [#16](https://github.com/kigawa-net/recl/issues/16) |
| Fortis public API capability verification | [#14](https://github.com/kigawa-net/recl/issues/14) |
| Single-key aggregate and index reconciliation | [#13](https://github.com/kigawa-net/recl/issues/13) |
| Index Worker / Qdrant synchronization | [#5](https://github.com/kigawa-net/recl/issues/5) |
| Claude Code MCP Server | [#7](https://github.com/kigawa-net/recl/issues/7) |
| Production capability gate | [#65](https://github.com/kigawa-net/recl/issues/65) |

Fortis is under development. In particular, Snapshot/Recovery and public conditional KV capabilities require verification before recl can claim a production-ready Fortis backend.

See [the implementation design](docs/design.md) for key layout, write/read protocol, and operational acceptance gates.

## Development

The initial bootstrap uses **Kotlin/JVM 21**, Gradle 9.6.0, and the modules `:core`, `:storage-memory`, `:storage-fortis`, `:api-rest`, `:mcp`, `:dev-mcp`, `:index`, `:source-github`, and `:app`.

Run `./gradlew test` (JDK 21). The GitHub Actions Kotlin CI workflow runs the same suite on pull requests.

The executable app serves `GET /health` and a read-only MVP dashboard at `GET /dashboard`. `GET /dashboard/api/metrics` returns JSON with server-instance uptime in seconds, total HTTP request count (all paths and methods, including metrics requests), JVM/OS information, and module wiring states. The dashboard has no authentication and is not production-ready. MCP is disabled by default; `POST /mcp` can be enabled through explicit environment gates with OIDC JWT validation (`RECL_MCP_AUTH=oidc`) or a static Bearer token for local development (`token`, the default) ([HTTP MCP setup](docs/mcp-http.md)). Persistence is not implemented: the temporary `:storage-memory` wiring requires acknowledgement that all Context is lost on restart. `:api-rest` remains a test-only adapter slice; `:mcp` supplies the shared transport-independent protocol and tools (see [REST docs](docs/rest-context-api.md) and [MCP docs](docs/mcp-server.md)). `:dev-mcp` is a separate, explicitly gated stdio launcher for local Claude Code testing with ephemeral storage ([dev MCP docs](docs/dev-mcp.md)); it is excluded from the production image. `:index` holds the test-only index reconciliation slice over injected `SearchIndexPort`/`EmbeddingProvider` ports ([index docs](docs/index-reconciliation.md)); no Qdrant adapter or scheduler is wired. OIDC fixes the tenant in configuration and derives each user from the verified JWT; configure an audience or dedicated required scope for production. Fortis, Qdrant, and durable MCP integration are not yet operational, and live Keycloak/OpenCode OIDC integration remains to be validated.

See [bootstrap notes](docs/bootstrap.md) and [architecture design](docs/design.md).
