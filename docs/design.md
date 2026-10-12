# recl — Fortis-first architecture and MVP contracts

> Status: proposed implementation specification, 2026-10-12. This document specifies recl's desired behavior; it does **not** attest that Fortis exposes the required public KV APIs today. Verify capabilities via [recl #14](https://github.com/kigawa-net/recl/issues/14) and do not enable production writes otherwise.

## 1. Scope

recl is a persistent development-context service for coding agents, initially Claude Code via MCP. It stores durable decisions, architecture, constraints, tasks, findings, references, and preferences alongside provenance and validity.

Goals:
- Retrieve relevant *project/repository/task* context without sending every past conversation to the agent.
- Maintain historical context, superseding old decisions instead of silently overwriting their meaning.
- Enforce tenant and principal authorization independently of semantic-search filters.
- Recover the search index after lost events, worker crashes, and storage reconfiguration.

Non-goals for MVP:
- Full conversation archive or automatic memory inference.
- SQL schema or query support.
- Cross-context atomic updates without a demonstrated Fortis transaction primitive.
- Treating a vector index as the authoritative record.
- Claiming a production-ready Fortis client before its CAS, scan, read, and recovery tests pass.

## 2. Components and trust boundaries

```text
Claude Code / Agent
       |
  MCP transport (stdio adapter or remote HTTP)
       |
recl API Boundary: authentication -> principal -> tenant context
       |
Context Application Service ---- AuthZ / policy evaluator
       |
    Storage Port ---------- InMemoryAdapter (tests only)
       |
  FortisAdapter -> Fortis KV (SOURCE OF TRUTH)
       |
  Index Reconciler -- EmbeddingProvider --> Qdrant (DERIVED INDEX)
       ^
   durable enumeration + optional Watch wake-up
```

Only the Context Service can authorize reads/writes. Qdrant payload filters reduce candidate sets, not permissions. Fortis operational and namespace authorization must also prevent a tenant or another service from bypassing recl.

Separate interfaces:
- Domain/Application: context state transitions, authorization, idempotency, provenance.
- Storage Port: linearizable read, conditional creation/CAS, enumerating authoritative objects, typed failure.
- Search Port: semantic index lookup and index-version inspection.
- Index Projection: embedding, upsert/delete, lag reconciliation.

## 3. Context domain

```text
ContextAggregate {
  schemaVersion: 1,
  id: UUIDv7,
  tenantId: TenantId,
  ownerId: PrincipalId,
  accessPolicy: Policy,
  contentRevision: UInt64,
  type: decision | architecture | constraint | task |
        finding | reference | preference,
  status: proposed | confirmed | superseded | archived,
  title: string,
  summary: string,
  content: string,
  importance: [0,1],
  pinned: boolean,
  scopes: Scope[],
  relations: ContextRelation[],
  sources: Provenance[],
  validFrom: timestamp?,
  validUntil: timestamp?,
  deletedAt: timestamp?,
  indexIntent: { action: upsert | delete, targetContentRevision: UInt64 },
  projection: { indexedContentRevision: UInt64?, indexGeneration: string? },
  operationReceipts: OperationReceipt[]
}
```

- Scope types initially: global, organization, project, repository, module, branch, task, topic. Scope is **classification**, never an authorization grant.
- Relation types: depends_on, supersedes, conflicts_with, related_to, part_of, derived_from, implements, affects.
- Sources can refer to GitHub Issue/PR/commit, repository file/symbol, document, conversation, or manual input.
- All relations are validated as far as possible when writing. An aggregate-only MVP cannot guarantee foreign-context atomicity; report this explicitly.
- A deleted Context remains a tombstone until index deletion and retention criteria pass.

### Identity and keyspace

Logical keys:
```text
recl/v1/t/{tenantId}/c/{contextId}
```
Both IDs must use validated canonical, separator-safe encodings. In the MVP all authoritative metadata about a single Context lives in that Context key.

TenantId must come from authenticated server-side authorization, **not** merely from an MCP tool argument, a Git remote URL, or Qdrant payload. Repository identity is normalized for scope matching, then verified against the principal's grants.

Size limits, revision-history retention, and codec choice are implementation decisions to test before splitting aggregates. Splitting authoritative data across multiple Fortis keys changes the atomicity contract and requires an independently validated atomic batch/transaction.

## 4. Revision model

Three distinct counters/tokens exist:

| Name | Meaning | Changed by |
| ---- | ------- | ---------- |
| `contentRevision` | Semantically changed context, status, ACL, scope, index target | Authorized context mutation |
| `storageRevision` | Opaque physical CAS generation of the whole KV value | Any aggregate value write, including worker ACK |
| `indexedContentRevision` | Last projection revision *acknowledged* against Qdrant | Index reconciler |

- Storage Port returns `storageRevision` alongside the Context, rather than storing a fabricated Fortis token inside the serialized domain value.
- `contentRevision` only advances on logically meaningful edits.
- Worker ACK updates may advance `storageRevision` but must not advance `contentRevision`.
- `indexedContentRevision` must be monotonic. It is a checkpoint, not an immutable proof of Qdrant's current contents; a stale worker can overwrite a point later, so regular verification remains necessary.
- ACL changes update the logical revision so stale search results are rejected by revision/authorization checks.

## 5. Storage Port

Language-neutral signatures:

```text
get(tenantId, contextId, consistency=LINEARIZABLE)
  -> Found(aggregate, storageRevision)
   | NotFound | Unavailable

createIfAbsent(aggregate, requestId, payloadDigest)
  -> Applied(storageRevision) | AlreadyExists
   | OutcomeUnknown | Unavailable | Unsupported

compareAndSwap(tenantId, contextId, expectedStorageRevision,
               nextAggregate, requestId, payloadDigest)
  -> Applied(storageRevision) | RevisionConflict
   | OutcomeUnknown | Unavailable | Unsupported

enumerateTenant(tenantId, cursor, limit, consistency)
  -> Page(objects, nextCursor, snapshotOrRevisionMetadata)
   | Unsupported | Unavailable
```

All provider-specific errors must map to explicit typed results. Neither the API nor the test adapter may synthesize true CAS using non-atomic GET+PUT. Fortis's actual public operation signatures and isolation guarantees remain a prerequisite.

InMemoryAdapter must implement genuinely atomic conditional updates under one lock (or equivalent) for repeatable tests. It is ephemeral and **must never be automatically substituted** for Fortis in production.

## 6. Context mutation protocol

```text
updateContext(id, expectedContentRevision, requestId, patch):
  validate requestId + canonical payload digest
  repeat with bounded retries:
    r = storage.get(tenantFromAuth, id, LINEARIZABLE)
    require r.Found, not tombstoned
    evaluate latest ACL for the principal
    if r.aggregate.contentRevision != expectedContentRevision:
       return Conflict (409)
    if operation receipt for requestId exists:
       compare digest and return its recorded outcome or replay error
    next = apply patch to r.aggregate, preserving worker projection
    next.contentRevision += 1
    next.indexIntent = upsert(target=next.contentRevision)
    next.operationReceipts += signed/canonical receipt
    outcome = storage.compareAndSwap(..., r.storageRevision, next,...)
    if Applied: return new contentRevision
    if RevisionConflict: re-read, reauthorize, recheck content revision
    if OutcomeUnknown: reconcile via authoritative read; never assume failure
  return Conflict / RetryableUnavailable as appropriate
```

Create uses a caller-stable UUIDv7 Context ID and conditional put. Delete CAS-writes a tombstone and `indexIntent=delete` with a new logical revision. A reused requestId with a different digest must fail. Retention and dedup windows must be explicit: once a receipt is evicted, do not claim certainty about an old lost ACK.

**Important:** A linearizable GET of the latest aggregate may no longer expose an operation receipt after later updates. If a stable request-result lookup cannot be guaranteed, return `OutcomeUnknown`; do not infer success from a newer content revision.

### Two-Context supersede

Superseding A with B requires changes to two keys. Until an approved Fortis atomic batch is available, do **not** represent the pair as an atomic transaction. The first MVP can record a new proposed Context referencing the old one and require a separate explicit confirmation/reconciliation step; API must expose intermediate states or mark this feature blocked.

## 7. Search and authorization protocol

1. Authenticate principal; derive trusted tenant and repository grants.
2. Resolve allowed scopes using **trusted** repository context, not client-provided scope alone.
3. Search Qdrant for candidate IDs with best-effort scope/status/payload filters.
4. For every candidate, perform authoritative Fortis read (and fail closed on unavailable).
5. Reject missing/tombstoned/expired contexts, unauthorized tenants, disallowed scopes, and revoked grants.
6. If candidate payload `contentRevision` differs from Fortis's current revision, reject stale content rather than returning outdated text. It may be re-ranked only after authoritative content retrieval.
7. Apply ranking and relation expansion only across authorized contexts.
8. Respond with bounded summaries first; fetch full context through a separate operation.

Eventually consistent indexing may omit newly written contexts temporarily. This is an availability/completeness limitation, not permission to return stale or unauthorized records. Use a direct Fortis pinned-context read when bypassing semantic search is necessary.

## 8. Index reconciliation

A write changes the authoritative aggregate and `indexIntent` in one conditional operation. Do not assume a separate transactional outbox table exists.

Worker loop:
1. Obtain candidate IDs from an authoritative, gap-detectable Fortis enumeration; Watch may only wake the loop.
2. Read latest aggregate and current intent. If its desired content revision and observed Qdrant projection disagree, reconcile.
3. Generate embeddings; upsert one point per Context for MVP (with tenant, ID, status, contentRevision and indexGeneration payload).
4. Verify Qdrant's point revision after write; acknowledge monotonically to Fortis using a separate physical CAS, preserving any intervening user update.
5. Retry with backoff. Periodically compare Fortis and Qdrant, including tombstones and already-ACK'd records.

Fencing note: Fortis leases/fencing tokens **alone cannot stop** an old worker whose Qdrant request arrives late. Prefer one logical writer per context plus ongoing revision inspection and repair. Search must always revalidate against Fortis.

A complete source enumeration or equivalent durable dirty-key index with proven atomic updates is mandatory to *guarantee* convergence. If Fortis offers neither, the worker's production synchronization capability must remain blocked.

Deletes: keep the tombstone until Qdrant confirms deletion and the recheck/retention policy is satisfied. Never silently resurrect an older point.

## 9. MCP minimum tools

- `search_context(query, scope?, limit?)`
- `get_context(id)`
- `get_pinned_contexts(repository, task?, tokenBudget?)`
- `save_context(type, title, summary, content, scopes, sources, requestId)`
- `update_context(id, expectedContentRevision, patch, requestId)`

MCP and REST invoke the same Application Service and policy evaluator. Public tool schemas should clearly distinguish client hints (repository/task) from server-verified authorization. Avoid storing secrets, credentials, or ephemeral chain-of-thought in retrieved context.

## 10. Errors and operational states

- `NOT_FOUND`, `FORBIDDEN`, `REVISION_CONFLICT`, `INVALID_REQUEST`, `DUPLICATE_REQUEST_DIFFERENT_PAYLOAD`
- `OUTCOME_UNKNOWN`, `STORE_UNAVAILABLE`, `CAPABILITY_UNSUPPORTED`, `INDEX_DEGRADED`.
- Quorum loss: do not serve stale authoritative state as confirmed. Refuse writes and fail-closed for security-relevant reads.
- Index unavailable: writes may still commit to Fortis. Semantic search may return an explicit degraded response; never return stale cached ACL decisions.
- Retain minimal structured logs, redact payloads and authentication secrets, record `requestId`, tenant, trace IDs, storage/index revisions.
- Readiness requires explicit Storage Port capabilities; dev InMemory is never a silent production fallback.

## 11. Acceptance tests / gates

### Storage Port and API
- Two concurrent writers at the same expected content revision -> at most one semantic update succeeds.
- Worker projection ACK concurrent with user mutation -> no lost content, ACL, or projection metadata.
- Repeated create using the same Context ID -> no duplicate Context.
- Lost success response -> safe reconciliation, or explicit `OutcomeUnknown`.
- ACL revoked between first read and CAS retry -> write denied.
- Tombstoned Context is not returned, even while the stale Qdrant point exists.
- InMemory cannot start in production configuration.

### Search/index
- Old Qdrant point not returned after new content/ACL revision.
- Worker crash before/after Qdrant write and before Fortis ACK -> repair on restart.
- Qdrant index lost -> full reindex from Fortis.
- Watch missed or compacted -> reconciliation still converges.
- Older worker's delayed Qdrant write -> index version mismatch detected and repaired.

### Fortis production gate
- Public single-key conditional creation/CAS, linearizable reads, and durable iteration verified via #14.
- Recovery/Snapshot and unknown-outcome behavior verified, including [Fortis #17](https://github.com/kigawa-net/fortis/issues/17) and [Fortis #40](https://github.com/kigawa-net/fortis/issues/40).
- Do not treat in-memory transaction prototypes under [Fortis #24](https://github.com/kigawa-net/fortis/issues/24) as production multi-key transactions.
- No production write-path enablement until these gates pass.

## 12. Implementation sequence

1. [#15 Storage Port + InMemoryAdapter](https://github.com/kigawa-net/recl/issues/15)
2. [#16 Context mutation/CAS/idempotency](https://github.com/kigawa-net/recl/issues/16)
3. [#14 Fortis capability contract tests](https://github.com/kigawa-net/recl/issues/14)
4. [#3 Fortis Storage Adapter](https://github.com/kigawa-net/recl/issues/3)
5. [#4 Context API](https://github.com/kigawa-net/recl/issues/4)
6. [#5 Index Reconciliation](https://github.com/kigawa-net/recl/issues/5)
7. [#7 Claude Code MCP Server](https://github.com/kigawa-net/recl/issues/7)

Other relevant issues: [#9 data model](https://github.com/kigawa-net/recl/issues/9), [#10 worker concurrency](https://github.com/kigawa-net/recl/issues/10), [#11 MCP identity](https://github.com/kigawa-net/recl/issues/11), [#12 Fortis selection](https://github.com/kigawa-net/recl/issues/12), [#13 single-key reconciliation](https://github.com/kigawa-net/recl/issues/13).
