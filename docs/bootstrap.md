# Kotlin/JVM bootstrap

Current scope: **initial MVP modules and contract tests**. This is not a production backend.

| Module | Responsibility |
| --- | --- |
| `:core` | ContextAggregate, revision types, ContextStorage port, CAS-based ContextService |
| `:storage-memory` | Ephemeral and synchronized InMemoryContextStorage; test-only |
| `:storage-fortis` | Fortis-facing ContextStorage adapter over a recl-side KV port; the real binding is still open |
| `:api-rest` | Context CRUD/pinned REST adapter over the trusted gateways; test-only, no launcher |
| `:mcp` | MCP JSON-RPC/stdio adapter and tool contract; test-only, no launcher |
| `:dev-mcp` | Dev-only stdio launcher with ephemeral storage; never part of the production image |
| `:index` | Index reconciliation over injected search-index and embedding ports; test-only, no Qdrant adapter |
| `:source-github` | Scoped GitHub App token broker and source attestation adapters |
| `:app` | Minimal `/health` HTTP process; the Context API remains disabled |

Kotlin JVM plugin: 2.2.20. Java toolchain: 21. Gradle: 9.6.0 (wrapper committed).

## Local verification

```sh
./gradlew test --no-daemon
```

The initial JUnit suite covers concurrent writers, logical vs physical revisions, duplicate request identity, owner/tenant isolation, tombstones, and reconciliation after a lost write acknowledgement. It does not simulate Raft consensus or physical storage failure.

## Implementation boundaries

- The in-memory StoragePort is linearizable only within a single JVM process; **not durable**.
- The StoragePort currently returns a JVM `Long` as its physical CAS revision. A future Fortis adapter must translate Fortis's actual opaque version token; **the public Fortis API is not yet validated**.
- Context ID and digest are supplied by a trusted internal application caller. Client-facing validation, canonical request digest calculation, UUIDv7 issuance, ACL grants and token authorization remain future work.
- Current authorization is restricted to the owning Principal in the same Tenant. No administrative/broader grants have been implemented.
- The in-memory operation-receipt window is bounded; an evicted receipt cannot prove the outcome of a past request. The service returns `OutcomeUnknown` for a failed-ACK operation that cannot be verified.
- The index ACK method only advances metadata; there is **no Qdrant integration** and no proof of remote index publication yet. Production indexing must verify the projection and reconcile periodically.
- Multi-Context Supersede remains blocked without a verified Fortis multi-key atomic primitive.
- There is deliberately **no silent InMemory fallback** in the application entry point.

## Next steps

1. ~~Generate and commit the official Gradle Wrapper~~ (done, Gradle 9.6.0).
2. Implement trusted authorization boundaries and canonical request envelopes ([#16](https://github.com/kigawa-net/recl/issues/16)).
3. Validate the actual Fortis public KV API before implementing its adapter ([#14](https://github.com/kigawa-net/recl/issues/14)).
4. Only then integrate Fortis, Qdrant, MCP, and REST, with fault-injection tests.
