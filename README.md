# recl

recl is a **development context repository** for coding agents. It stores durable project decisions, architecture, constraints, findings, and provenance so that agents such as Claude Code can retrieve the relevant context across sessions.

> **Status: design / bootstrap.** The repository currently has no production-ready implementation. The Fortis Storage Adapter, MCP server, and Qdrant indexer are planned, not shipped.

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

Implementation language, build commands, and package layout will be specified in a dedicated bootstrap design. **Do not assume the current repository is buildable** until bootstrap tooling and automated tests have been committed.
