# recl

recl is a **development context repository** for coding agents. It stores durable project decisions, architecture, constraints, findings, and provenance so that agents such as Claude Code can retrieve the relevant context across sessions.

> **Status: initial Core implementation / bootstrap.** The repository does not yet provide a production-ready service. A test-only REST/MCP adapter and a Fortis port-level adapter exist, but the real Fortis binding, launcher, authentication, and Qdrant indexer are planned, not shipped.

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
   Fortis Adapter  InMemory Adapter (tests only)
          |
       Fortis KV  <-- source of truth
          |
    Index Reconciler --> Embedding provider --> Qdrant
                                           (derived search index)
```

- **Fortis** is the intended authoritative store for Context, lifecycle, scope/ACL, revisions, and pending index intent.
- **Qdrant** is a rebuildable, eventually consistent search index, **not** an authorization boundary.
- **Storage Port** isolates recl Core from Fortis's evolving public KV API.
- **InMemory Adapter** is strictly for development and tests. Production must fail closed rather than silently switching to ephemeral storage.
- **MCP** is the first agent integration; REST and other agents can use the same application services.

## Context model

The initial Context types are `decision`, `architecture`, `constraint`, `task`, `finding`, `reference`, and `preference`.

A Context belongs to a **Tenant** and is governed by an **Access Policy**. Its `Scope` values (repository, project, module, branch, task, topic) select relevant context, but they do **not** confer read/write permissions.

Revisions serve different purposes:
- `contentRevision`: logical changes to content, scope, status, or ACL.
- `storageRevision`: conditional write/CAS generation at the storage boundary.
- `indexedContentRevision`: last acknowledged Qdrant projection of a content revision.

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

Fortis is under development. In particular, Snapshot/Recovery and public conditional KV capabilities require verification before recl can claim a production-ready Fortis backend.

See [the implementation design](docs/design.md) for key layout, write/read protocol, and operational acceptance gates.

## Development

The initial bootstrap uses **Kotlin/JVM 21**, Gradle 9.6.0, and the modules `:core`, `:storage-memory`, `:storage-fortis`, `:api-rest`, `:mcp`, `:dev-mcp`, `:source-github`, and `:app`.

Run `./gradlew test` (JDK 21). The GitHub Actions Kotlin CI workflow runs the same suite on pull requests.

The executable app is currently a **fail-closed placeholder** with only `GET /health`, not an MCP/REST server. `:storage-memory` is a test adapter only. `:api-rest` and `:mcp` expose test-only adapter slices over the trusted gateways (see [REST docs](docs/rest-context-api.md) and [MCP docs](docs/mcp-server.md)); neither has a production authenticator or durable storage. `:dev-mcp` is a separate, explicitly gated stdio launcher for local Claude Code testing with ephemeral storage ([dev MCP docs](docs/dev-mcp.md)); it is excluded from the production image. Fortis, Qdrant, and durable Claude Code MCP integration are not yet operational.

See [bootstrap notes](docs/bootstrap.md) and [architecture design](docs/design.md).
