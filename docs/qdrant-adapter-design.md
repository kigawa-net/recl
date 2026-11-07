# Qdrant adapter design (recl #5)

Status: **design only, not implemented.** No Qdrant client dependency, collection, payload schema,
alias, reindex job, or worker is present in this repository. The `:index` module still ships only
`SearchIndexPort`, `IndexReconciler`, and their test doubles. This document proposes how a future
`QdrantSearchIndex` adapter should map the existing port onto Qdrant, and which questions must be
answered before it can be written.

Production connection is blocked twice over:

1. The authoritative side is not bindable yet. `docs/fortis-binding-assessment.md` records that the
   real Fortis binding is **BLOCKED** on Fortis #33 (public client/SDK) and #34 (KV auth and
   leader routing), and `docs/design.md` §11 keeps production writes disabled until those gates pass.
2. Qdrant is a **derived** index. It can only converge against a complete, revision-pinned Fortis
   enumeration. Without that enumeration, this adapter can repair individual points but cannot
   guarantee tenant-wide convergence.

Date: 2026-11-05.

Related: [recl #5](https://github.com/kigawa-net/recl/issues/5),
[recl #13](https://github.com/kigawa-net/recl/issues/13),
[recl #10](https://github.com/kigawa-net/recl/issues/10),
[recl #14](https://github.com/kigawa-net/recl/issues/14),
[design §8](design.md#8-index-reconciliation),
[index-reconciliation.md](index-reconciliation.md),
[fortis-binding-assessment.md](fortis-binding-assessment.md).

## 1. Scope

In scope:

- Mapping `SearchIndexPort.inspect` / `upsert` / `delete` onto Qdrant operations.
- Point identity and payload schema.
- Stale-writer and duplicate-processing behavior, including what Qdrant can and cannot enforce.
- Retry, backoff, rate-limit, and batch policy.
- Reindex with collection aliasing, including rollback.
- Tombstone retention and the GC gate.
- Monitoring, invariant checks, and acceptance tests to add later.

Out of scope (deliberately not decided here):

- A concrete Qdrant client library (REST vs gRPC) or Gradle dependency.
- The embedding model, its dimension, distance metric, and normalization.
- Any change to `Core`, `ContextStorage`, `SearchIndexPort`, or `IndexReconciler`.
- Deployment topology, auth to Qdrant, TLS, and network policy.
- Actually creating Qdrant resources.

## 2. Design summary

The issue title says "Outbox", but `docs/design.md` §8 is explicit: **there is no separate
transactional outbox table.** The write of the authoritative aggregate and its `indexIntent`
(`UPSERT`/`DELETE` at a target `contentRevision`) happen in one conditional Fortis write. The worker
derives work by comparing authoritative state to the observed index point. This document keeps that
model and does not introduce an outbox.

Shape of the proposed adapter (names are proposals, nothing exists yet):

```text
IndexReconciler
      |
      v
SearchIndexPort  <-- QdrantSearchIndex (proposed)
      |                    |
      |                    +--> QdrantPointId (UUIDv5 derivation)
      |                    +--> QdrantPayloadCodec
      |                    +--> QdrantErrorMapper
      |                    +--> QdrantRetryPolicy
      |
      +--> ReindexCoordinator (proposed, outside the port)
      +--> IndexInvariantChecker (proposed, read-only)
      +--> TombstoneGcGate (proposed, consumes reconciler results)
```

Two properties dominate every decision below:

1. **Fortis is authoritative.** A Qdrant point is a candidate generator. Search must re-read Fortis
   and compare `contentRevision` before returning anything (design §7).
2. **The port is weaker than the problem.** `upsert` and `delete` carry no expected revision, and
   `IndexWriteResult` has no "stale" or "conflict" case. Conditional behavior must therefore live
   inside the adapter, and the post-write `inspect` in `IndexReconciler` remains the real check.

## 3. Current code and the gaps this design must respect

| Existing type | Relevant property | Consequence for the adapter |
| --- | --- | --- |
| `IndexPoint` | Carries `content` and the full embedding, plus `contentRevision` and `indexGeneration`. | `content` is only embedding input. It should not be persisted in the Qdrant payload (see §5.3). |
| `SearchIndexPort.inspect` | Returns `Found(point)` / `NotFound` / `Unavailable` / `Unsupported`. | Must reconstruct enough of `IndexPoint` from payload. Not all fields are stored. |
| `SearchIndexPort.upsert` | No expected revision, no conditional variant. | The adapter needs an internal "do not downgrade" guard and a post-write read. |
| `SearchIndexPort.delete` | Only `(tenantId, contextId)`. | Conditional delete-by-revision is not expressible. See §6.3 and §12. |
| `IndexReconciler` | On `UNAVAILABLE` it **returns immediately** for that context; `maxAttempts` only covers "applied but not visible". | Transport retries, 429 handling, and timeouts must be implemented **inside the adapter**. |
| `IndexReconciler` | Never ACKs before re-reading Fortis; ACK is monotonic via CAS. | The adapter does not need to (and must not) advance `indexedContentRevision`. |
| `InMemorySearchIndex` | Models `upsertIsVisible = false`, `deleteIsVisible = false`, and post-write concurrent writers. | The fake-based test suite can cover most ordering logic without Qdrant. |

## 4. Port → Qdrant mapping

### 4.1 Endpoints used

All paths below are the Qdrant v1.20.x REST shapes as published on 2026-11-05. The pinned server
version is not chosen yet, so every path and parameter must be re-verified against the version that
is actually deployed (**要確認**).

| Port operation | Qdrant request | Key parameters |
| --- | --- | --- |
| `inspect(tenantId, contextId)` | `POST /collections/{collection}/points` (retrieve by ID) | `ids`, `with_payload: true`, `with_vector: false`, `consistency` |
| `upsert(point)` | `PUT /collections/{collection}/points` | `wait=true`, one point, optional `update_filter`, optional `ordering` |
| `delete(tenantId, contextId)` | `POST /collections/{collection}/points/delete` | `wait=true`, `points: [pointId]`, optional `ordering` |
| Reindex scan (not a port op) | `POST /collections/{collection}/points/scroll` | `limit`, `offset`, `with_payload: true`, `with_vector: false` |
| Alias resolution (not a port op) | `GET /aliases` | returns `alias_name` + `collection_name` pairs |
| Alias swap (not a port op) | `POST /collections/aliases` | `actions: [{delete_alias}, {create_alias}]` |

### 4.2 `inspect`

```text
inspect(tenantId, contextId):
  pointId = derivePointId(tenantId, contextId)
  response = POST /collections/{resolvedCollection}/points
             { "ids": [pointId], "with_payload": true, "with_vector": false }
  if transport error / 5xx / 429 / timeout -> Unavailable
  if 400/401/403/404                   -> Unavailable (fail closed, alarm)
  if result == []                      -> NotFound
  payload = decode(result[0].payload)
  if payload is missing required fields, or tenantId/contextId do not match the requested identity
                                       -> Unavailable (never silently overwrite a foreign point)
  else                                 -> Found(IndexPoint(...payload fields..., embedding = emptyList()))
```

Notes:

- `with_vector: false` is deliberate at this layer. `IndexPoint.embedding` is then incomplete. The
  reconciler only compares `contentRevision`, so this is safe today, but it makes `Found` a lossy
  snapshot. A future port revision should split the body from the vector; until then the adapter must
  never be used for vector equality checks.
- Undecodable payload is mapped to `Unavailable`, not `NotFound`. Treating corruption as absence
  would let an ACK follow a write that may not have repaired anything. An operator-visible alarm
  plus a repair command is the intended response.
- `IndexInspection.Unsupported` is not returned. Qdrant supports all three operations. If a pinned
  server version lacks a required feature (for example `update_filter`), the adapter degrades to
  inspect-then-write; it does not claim `Unsupported`.
- The caller supplies no consistency parameter through the port, so the adapter chooses it
  (see §4.6).

### 4.3 `upsert`

```text
upsert(point):
  pointId = derivePointId(point.tenantId, point.contextId)
  payload = encode(point)                      // content omitted
  request = {
    "points": [{ "id": pointId, "vector": point.embedding, "payload": payload }],
    "update_filter": {
      "must": [
        { "key": "tenantId",       "match": { "value": point.tenantId } },
        { "key": "contextId",      "match": { "value": point.contextId } },
        { "key": "contentRevision", "range": { "lt": point.contentRevision } }
      ]
    }
  }
  retry loop (see §7):
    response = PUT /collections/{collection}/points?wait=true
    if 200 and result.status == "completed": break
    if 200 and result.status in {"acknowledged", "wait_timeout"}: break to verification
    if retryable error and attempts remain: backoff; continue
    else: return Unavailable
  read back with inspectAtConsistency(...)
  if NotFound: return Unavailable         // write claimed success but is not visible
  if Found and contentRevision >= target: return Applied
  if Found and contentRevision < target:  retry / Unavailable
```

Important Qdrant semantics, verified on 2026-11-05:

- `update_filter` is documented as: only existing points matching the filter are updated; points that
  do not match keep their state; **new points are inserted regardless of the filter**. So this guard
  protects against downgrading an existing newer point, but cannot stop a stale writer from
  *inserting* a point that was just deleted. See §6.3.
- `update_filter` matching a point that has no `contentRevision` field fails the range condition, so
  such a point is left untouched. This is why `contentRevision` must always be written.
- Qdrant has no compare-and-set token, ETag, or native version check. `update_filter` is the closest
  facility and is not equivalent to the Fortis CAS.
- `wait=true` is documented as "wait for changes to actually happen", but the response status still
  distinguishes `completed`, `acknowledged`, and `wait_timeout`. `acknowledged` is not enough to ACK.

Returning `Applied` when the read-back shows a **newer** revision is intentional: the adapter did not
lose data. The reconciler then sees `contentRevision != target`, retries, and eventually reports
`RETRY_REQUIRED`. A point newer than the authoritative revision is an anomaly and must alert
(see §10.2), not be overwritten.

### 4.4 `delete`

```text
delete(tenantId, contextId):
  pointId = derivePointId(tenantId, contextId)
  retry loop:
    response = POST /collections/{collection}/points/delete?wait=true
               { "points": [pointId] }
    if 200 and result.status == "completed": break
    if retryable error and attempts remain: backoff; continue
    else: return Unavailable
  verify with inspect(): NotFound -> Applied; Found -> retry / Unavailable
```

- Deleting an absent ID is expected to succeed, matching the port's "absence is success" contract.
  This must be verified against the real server (**要確認**).
- The port cannot express "delete only if `contentRevision <= N`". The proposed adapter therefore
  deletes by ID. The reconciler's authoritative re-read before ACK is what prevents a delete from
  being acknowledged after the context was recreated. Convergence is preserved; a brief missing
  point is possible (availability, not correctness). See §6.3.
- If a future port revision adds an expected revision, the delete can use
  `PointIdsList` + a filter, or a `FilterSelector` on `tenantId`/`contextId`/`contentRevision`.
  Qdrant only offers one selector per delete, so by-ID plus a pre-check is likely simpler.

### 4.5 Result and error mapping

| Qdrant outcome | Port result | Retry inside adapter? | Notes |
| --- | --- | --- | --- |
| HTTP 200, `status = completed` | `Applied` (after read-back) | No | Read-back still required for `upsert`. |
| HTTP 200, `status = acknowledged` | Unknown | Verify by read | Should not occur with `wait=true`; treat as a bug signal. |
| HTTP 200, `status = wait_timeout` | Unknown | Verify, then retry | The operation may still land later. Never assume failure. |
| Connection reset / timeout | `Unavailable` | Yes, bounded | The write may have succeeded; read back before rewriting. |
| HTTP 429 | `Unavailable` | Yes, with backoff | Honor `Retry-After` if the server sends it (**要確認**). |
| HTTP 5xx | `Unavailable` | Yes, bounded | Distinguish overload from persistent failure via attempt count. |
| HTTP 400 (bad payload/vector) | `Unavailable` | No | Poison point. Do not retry forever; emit a terminal-failure signal. |
| HTTP 401/403 | `Unavailable` | No | Configuration/auth failure; fail closed and alarm. |
| HTTP 404 (collection/alias missing) | `Unavailable` | No | Never auto-create a collection from the worker. |
| HTTP 413 (payload too large) | `Unavailable` | No | Terminal for that point; requires a size policy (§12). |

The port has no terminal-failure result. Mapping a permanent 400 to `Unavailable` is lossy: the
reconciler will keep re-selecting the context on every pass. This is acceptable for the design phase
because a poison point must remain visible, but it argues for a future `IndexWriteResult.Failed`
or a dead-letter record (**open question**, §12).

### 4.6 Read-after-write and consistency

The port's `upsert` contract implies that a following `inspect` sees the write. Qdrant's guarantees
depend on deployment shape and on two different parameters:

1. **Write visibility (`wait=true` + `result.status`).**
   `wait=true` makes the request wait for the operation to be applied rather than merely enqueued.
   The adapter must require `completed`; `acknowledged` and `wait_timeout` are "unknown", not success.
2. **Read consistency (`consistency`).**
   Retrieve and scroll accept `consistency`. The documented default is `Factor(1)`: one replica,
   which may be behind. On a replicated deployment, post-write verification should use `majority`
   (or `all` for diagnostics), not the default. `all` is a correctness tool, not a hot-path default.
3. **Write consistency factor (collection setting).**
   A collection can require a write consistency factor so a write is not acknowledged until enough
   replicas have applied it. The exact interaction with `wait=true` must be verified against the
   pinned version (**要確認**); do not assume `wait=true` alone means "all replicas".
4. **Ordering (`ordering`).**
   Upsert/delete/set-payload accept `ordering` with `weak`, `medium`, or `strong`. The precise
   cross-writer guarantees are not established by the API reference (**要確認**). It may reduce
   reordering, but it must not be treated as fencing against a delayed old writer.

Resulting rule:

> The adapter may return `Applied` only after a read at `consistency = majority` (multi-node) or
> default (single node) reports a point whose `contentRevision >= target` and whose identity fields
> match. `wait=true` is necessary but not sufficient.

Read-after-write does **not** protect against a write that arrives later. That requires §6.

## 5. Collection and payload schema

### 5.1 Point ID derivation

Requirements: deterministic across workers and restarts, collision-free across tenants, stable for
the lifetime of a Context, and accepted by Qdrant as a string point ID.

Proposal:

```text
name    = "recl/v1/index/t/" + tenantId + "/c/" + contextId
pointId = UUIDv5(RECL_INDEX_NAMESPACE, UTF-8(name))     // canonical lowercase UUID string
```

- `RECL_INDEX_NAMESPACE` is a fixed, documented UUID constant committed in code. It must never
  change. Changing it changes every point ID and requires a full reindex plus alias swap.
- The name mirrors the authoritative key layout (`recl/v1/t/{tenantId}/c/{contextId}`), with an
  `index` segment so the derivation cannot be confused with any future UUID use of the key.
- Including `tenantId` is defense in depth. Context IDs are UUIDv7 and already globally unique, but
  a validation regression elsewhere must not let two tenants collide on one point.
- UUIDv5 is SHA-1-based; collision probability is negligible for this namespace size. Qdrant string
  point IDs are documented as strings; whether the pinned version requires strict UUID formatting
  (rather than arbitrary strings) must be verified (**要確認**). UUIDv5 satisfies the stricter case.
- The scheme identifier `uuidv5-tenant-context-v1` is stored in the payload so an invariant checker
  can detect mixed schemes during a migration.
- Why not a `u64` hash: truncation raises collision risk and makes the tenant/context relationship
  unreadable in diagnostics. Why not the raw `contextId`: it works, but loses tenant separation and
  makes an ID-scheme change harder to detect.

### 5.2 Payload fields

| Field | Qdrant type | Indexed | Source | Purpose |
| --- | --- | --- | --- | --- |
| `schemaVersion` | integer | no | constant `1` | Payload evolution. |
| `pointIdScheme` | keyword | no | constant | Detect mixed ID derivations. |
| `tenantId` | keyword | yes (`is_tenant: true` candidate) | `IndexPoint.tenantId` | Filtering, orphan scans, defense in depth. |
| `contextId` | keyword | yes | `IndexPoint.contextId` | Recover identity from a hashed point ID; detect duplicates. |
| `contentRevision` | integer | yes | `IndexPoint.contentRevision` | Stale-writer guard, mismatch detection, GC evidence. |
| `indexGeneration` | keyword | no | `IndexPoint.indexGeneration` | Diagnostics and attempt correlation only. **Not an ordering key.** |
| `status` | keyword | yes | `IndexPoint.status` | Candidate filtering; never authorization. |
| `title` | text (or keyword if filters needed) | optional | `IndexPoint.title` | Display hint, revalidated against Fortis. |
| `summary` | text | optional | `IndexPoint.summary` | Bounded snippet, revalidated against Fortis. |
| `kind` | keyword | yes | `ContextAggregate.kind` | Candidate filtering. |
| `scopes` | keyword[] | yes | `ContextAggregate.scopes` | Classification filter only, never a grant. |
| `sensitivity` | keyword | optional | `ContextAggregate.sensitivity` | Candidate filtering; `RESTRICTED` never reaches storage. |
| `pinned` | bool | optional | `ContextAggregate.pinned` | Pinned candidate pre-filter. |
| `validFromEpochMillis` | integer | optional | aggregate | Pre-filter; final validity check is authoritative. |
| `validUntilEpochMillis` | integer | optional | aggregate | Pre-filter; final validity check is authoritative. |
| `embeddingModel` | keyword | yes | adapter config | Detect mixed-model collections; drives reindex. |
| `embeddingDim` | integer | no | adapter config | Sanity check on retrieval. |
| `projectedAtEpochMillis` | integer | no | injected clock | Projection time. **Not authoritative**; deterministic clock in tests. |

Deliberately absent from the payload:

- `ownerId`, grants, ACL decisions, or anything used as an authorization input.
- `content` (see §5.3).
- `storageRevision`, `operationReceipts`, `indexAction`, `indexedContentRevision`. These are
  authoritative bookkeeping and must not be duplicated into the index.
- `deleted`. A tombstone is represented by the absence of a point, not by a payload flag. If a
  payload flag were used, every search would need to filter it and a stale flag could hide a live
  point. Absence is simpler.

`contentRevision` must be an integer and must always be present. Qdrant integer payloads are signed
64-bit; `Long` maps directly. Fortis revisions are monotonic per Context, so no wrapping policy is
needed at this scale, but the adapter should reject negative values.

### 5.3 Should the body live in the payload?

**Decision: no.** Only `title` and `summary` are projected; `content` is never stored in Qdrant.

Rationale:

1. **Authority.** design §2 and §7 state that Qdrant is derived and that every candidate is
   revalidated against Fortis before being returned. Persisting the body duplicates the authoritative
   record and creates a second place where stale or unauthorized text can exist.
2. **Staleness.** A payload body can be read by something that forgets to revalidate. A missing body
   makes that mistake fail closed.
3. **Size.** Fortis aggregate values are capped at 1 MiB by `FortisContextStorage`; Qdrant payload
   and request limits are separate and not yet measured. Bodies plus vectors would hit them sooner.
4. **Blast radius.** A Qdrant compromise or misconfiguration should expose candidate metadata, not
   full development context.
5. **Rewrite cost.** Embedding is computed from `title + summary + content` in `IndexReconciler`;
   `content` is transport input to `EmbeddingProvider`, not a search result.

`title` and `summary` are retained because design §7 step 8 returns bounded summaries first and
because they are useful for ranking/diagnostics. They are explicitly **hints**: a response may only
use them after the authoritative read confirms the same `contentRevision`, and should prefer the
Fortis value when they differ. If the security review decides that even summaries are too much
duplication, drop both fields; search then needs a Fortis read per candidate before showing anything
(a cost increase, not a correctness change).

### 5.4 Vector configuration

Undecided, and intentionally not fixed here:

- **Dimension** depends on the embedding model, which `EmbeddingProvider` deliberately leaves open.
- **Distance** must be identical for every point in a collection. `Cosine`, `Dot`, `Euclid`, and
  `Manhattan` are supported. `Cosine` is documented as dot product over normalized vectors and
  Qdrant normalizes automatically on upload.
- **Normalization** policy must match the distance: with `Cosine`, pre-normalization is redundant
  but harmless; with `Dot`, the provider's normalization becomes semantically significant.
- **Datatype** (float32/float16/uint8) affects recall and storage; not selected.

Constraints for the future implementation:

1. One collection = one embedding model version, one dimension, one distance.
2. Changing any of these requires a new collection and an alias swap (§8), never an in-place update.
3. The adapter rejects a vector whose length is not the configured dimension and any non-finite
   component before calling Qdrant. `IndexReconciler` already rejects empty/non-finite vectors; the
   adapter adds the dimension check.
4. `embeddingModel` and `embeddingDim` in the payload make a mixed collection detectable.

### 5.5 Payload indexes and multitenancy

One physical collection with payload-based tenant partitioning is the documented Qdrant
recommendation for most deployments; a collection per tenant is an isolation/cost trade-off.
Decision pending (§12). If a single collection is used:

- `tenantId` should be a keyword index with tenant optimization enabled (`is_tenant: true`) so
  per-tenant filtering is efficient. Verify the exact parameter for the pinned version (**要確認**).
- `contextId`, `status`, `kind`, `contentRevision`, and `embeddingModel` are candidate keyword/integer
  indexes. `contentRevision` needs range support (the integer index defaults to range support).
- Payload indexes are created with `PUT /collections/{collection}/index` and can be created with
  `wait=true`; creation cost and rebuild behavior must be measured.
- Tenant isolation is **not** provided by the payload index. Qdrant credentials, network policy, and
  server-side tenant derivation remain required. A payload filter is a performance tool, not a
  security boundary.

## 6. Stale writers and duplicate processing

### 6.1 What orders two writes

| Signal | Monotonic? | Use |
| --- | --- | --- |
| `contentRevision` | Yes, per Context, advanced only by authorized mutation | The only ordering key for projection. |
| `indexGeneration` | No; opaque, per attempt | Diagnostics, log correlation, duplicate-attempt detection. Never compare lexicographically. |
| `projectedAtEpochMillis` | Wall clock, non-monotonic | Diagnostics only. |
| Qdrant `operation_id` | Per collection, sequential | Debugging only; not comparable across restarts or collections. |
| Fortis lease/fencing token | N/A for Qdrant | Cannot fence an HTTP request that is already in flight (design §8). |

Rule: a write for revision `R` may only replace a point whose `contentRevision < R`. A point at
`R` may be rewritten only with semantically identical content (same revision implies same content);
such a rewrite is harmless but should be suppressed to save work. A point at `> R` must never be
replaced.

### 6.2 What Qdrant can enforce

Qdrant has no per-point CAS. The available mechanisms are:

1. **`update_filter` on upsert** (verified 2026-11-05): update only if the existing point matches
   `tenantId`, `contextId`, and `contentRevision < R`.
   - Existing newer point: not updated. Good.
   - Missing point: **inserted regardless of the filter.** This is the documented behavior and the
     main hole.
   - Point with no `contentRevision`: does not match the range, so it is not updated. This is why
     the field is mandatory.
2. **`update_mode`**: `upsert` (default), `insert_only`, `update_only`. `update_only` could express
   "do not create", but then a legitimate first write would fail, and the adapter needs a
   create-or-update decision anyway. Not proposed as the primary guard; verify exact semantics if
   considered (**要確認**).
3. **`set_payload` with a filter**: `POST /collections/{collection_name}/points/payload` accepts
   either explicit `points` or a `filter` (verified 2026-11-05) and can rewrite payload on matching
   points. Limits: it cannot change the vector, so it cannot publish a new embedding; it is not a
   compare-and-set; and the filter only constrains the *current* payload, so it has the same
   "nothing to compare against after a delete" hole. It is useful for metadata repair (for example
   backfilling `embeddingModel`), not for revision publication.
4. **External versioning (payload revision + read-check-write)**: store `contentRevision` in the
   payload, read it, then write. Limits: not atomic. Two writers can both read `R` and both write
   `R+1`; a delayed request can still land after a newer one unless the server enforces a filter;
   and the check is only as strong as that filter. This is why the design adds single-writer
   partitioning, `update_filter`, post-write inspection, and periodic repair instead of claiming
   CAS semantics.
5. **`ordering`**: `weak` / `medium` / `strong`. May constrain reordering, but does not compare
   payload versions. Must not be used as a substitute for the revision check (**要確認**).
6. **Single logical writer per Context.** Partition work by point ID (for example
   `hash(pointId) % workerCount`) so two workers do not intentionally write the same context. This
   is the strongest practical protection and is assumed by design §8 ("prefer one logical writer per
   context").
7. **Post-write inspection.** After every write, read the point back and compare
   `contentRevision`. This is what turns an undetected stale write into a detected anomaly.
8. **Periodic repair.** Even a correctly ordered writer can lose a race with a delayed request. The
   reconciler re-runs and the invariant checker finds the mismatch.

What Qdrant cannot enforce: "reject this insert because a newer revision was deleted". A delete
leaves no version to compare against. This is the residual risk in §6.3.

### 6.3 Delete versus recreate

Sequence of concern:

```text
T1  worker reads tombstone at revision 5, decides DELETE
T2  user recreates / updates the Context to revision 6 (deleted = false)
T3  worker's delete request reaches Qdrant and removes the point
T4  worker re-reads Fortis, sees revision 6 / not deleted
T5  worker must NOT acknowledge revision 5
T6  next reconcile pass upserts revision 6
```

`IndexReconciler.reconcileDelete` already implements T4/T5: it re-reads after the delete and returns
`RETRY_REQUIRED` if `deleted` is false or the revision changed. The window in T3 is an availability
gap (a search may miss the context), not a correctness failure, because search never returns Qdrant
content without a Fortis revalidation. The next pass restores the point.

The reverse race is worse:

```text
T1  worker reads live context at revision 5
T2  user deletes it (tombstone at revision 6)
T3  worker's upsert for revision 5 arrives late and inserts the point
T4  worker re-reads Fortis, sees deleted = true / revision 6, does not ACK
T5  next reconcile pass deletes the point
```

Again, no ACK, and search rejects the point after revalidation. The danger is only if something
reads Qdrant without revalidating, which design §7 forbids. Mitigations:

- `update_filter` blocks a downgrade of an existing point, but cannot block this insert.
- Keep the tombstone long enough that the repair pass runs before GC (§9).
- Run orphan detection (§10) so a resurrected point is found even if the reconciler does not revisit
  the context.

A stronger fix requires either (a) a Qdrant-native conditional insert tied to an external version,
which does not exist, or (b) making deletes visible as a payload tombstone with a filter on every
search, which trades one class of staleness for another and is not recommended. The design accepts
the residual risk because the authoritative revalidation is mandatory.

### 6.4 Duplicate processing

- Same Context, same revision, two attempts: the point ID is deterministic, the payload is
  semantically identical, and `indexGeneration` differs only in diagnostics. The second write is
  idempotent. `update_filter` may skip it (revision is not `< R`), which is fine.
- Lost response after a successful write: the adapter reads back before rewriting. If the point is
  at the target revision, it returns `Applied` without a second write.
- Duplicate ACK: `ContextService.acknowledgeIndex` returns true immediately when
  `indexedContentRevision >= target`; it is a monotonic CAS and cannot move backwards.
- Duplicate delete: deleting an absent point is expected to succeed; the port already defines
  absence as success.
- Two workers with different generations at the same revision: harmless, because revision equality
  implies content equality. If they disagree, the invariant checker flags it as a bug.

## 7. Retries, backoff, rate limiting, batching

### 7.1 Where retries live

Because `IndexReconciler` returns on `Unavailable`, the adapter must own:

- transport retries,
- 429 handling,
- timeout resolution (read back before deciding),
- bounded per-call deadlines.

`IndexReconciler.maxAttempts` then only handles "write reported success but the point is not at the
target revision" and "authoritative state changed". Do not duplicate the reconciler's loop inside
the adapter beyond a small fixed retry budget for a single request.

### 7.2 Policy

Proposed defaults (to be tuned after measurement):

| Parameter | Proposal |
| --- | --- |
| Per-attempt timeout | 2–5 s for point operations; 30–60 s for reindex batches (configurable). |
| Max attempts inside the adapter | 3–5 for retryable transport/5xx/429. |
| Backoff | Exponential with full jitter: base 200–500 ms, factor 2, cap 30 s. |
| Total per-context budget | A few seconds; exceeding it returns `Unavailable` and lets the next reconcile pass retry. |
| 429 handling | Backoff at least as long as `Retry-After` if present (**要確認**), otherwise jittered exponential. |
| Circuit breaker | Optional per endpoint: open after repeated connection failures, half-open probe. Do not let it convert a transient error into a permanent ACK. |

Bounded retries are required for a different reason too: a poison point (400/413) must not consume
the whole worker. After the budget, return `Unavailable` (today's only option) and emit a structured
failure record with the point identity, not the content.

### 7.3 Rate limiting and concurrency

- One global token bucket per Qdrant endpoint, plus a per-tenant bucket if noisy-neighbor behavior
  is observed. Qdrant Cloud and self-hosted deployments have different limits; make it configurable.
- Bounded worker pool; never unbounded coroutines/threads per Context.
- Serialize per Context: at most one in-flight write for a given `(tenantId, contextId)`. This is a
  local guarantee; it does not prevent a second process from writing.
- Reads (`inspect`) double the request count. Budget for two requests per point in the incremental
  path, plus retries.
- Reindex scans should be rate-limited separately from live reconciliation so a backfill cannot
  starve normal convergence.

### 7.4 Batching

- **Incremental path:** one point per request. It is simpler, keeps per-Context ACK semantics, and
  avoids one bad point failing a batch. `IndexReconciler` already operates per Context.
- **Reindex path:** batch upserts of 32–128 points, each carrying its own payload revision. Batching
  is safe because points are independent and the ID is deterministic. Split on 413 and on 400 that
  identifies a single bad point; do not lose the per-point result.
- **Deletes:** batch by ID for reindex/orphan cleanup; per-point for live tombstones.
- Batch size must be measured against Qdrant's request size limit, not guessed. A batch must be
  retried as a unit or split; partial application is possible, so a retry must be idempotent
  (it is, because upsert is by deterministic ID).
- Do not batch across tenants if per-tenant rate limiting or blast-radius isolation matters.

### 7.5 Idempotency summary

| Operation | Idempotent? | Why |
| --- | --- | --- |
| Point upsert | Yes (same revision) | Deterministic ID; same payload. |
| Point delete | Yes | Absence is success; deleting twice is harmless. |
| ACK | Yes | Monotonic CAS; already-ACKed target returns true. |
| Reindex copy | Yes | Same IDs/payloads; safe to replay. |
| Alias swap | **Not inherently** | Repeating create/delete can fail or invert. Must be driven by desired state, not blind replay (§8.4). |

## 8. Reindex and alias switching

### 8.1 Collection naming

```text
recl_ctx_v1_<modelSlug>_<dim>_<yyyymmddHHMM>     // physical, immutable after creation
recl_ctx_active                                   // alias used by readers/writers
recl_ctx_previous                                 // optional alias for rollback
```

- Never mutate a physical collection's vector configuration in place.
- Never reuse a deleted collection name; it breaks forensic comparisons and any cached resolution.
- Record the physical name, point-ID scheme, model, dimension, and distance in the collection
  metadata (and in deployment config). The adapter resolves the alias explicitly via `GET /aliases`
  and then writes to the concrete collection, so a long-lived client cannot keep writing to an old
  name by accident.
- Include the resolved collection name in structured logs and, if useful, in a
  `collectionEpoch` payload field. The payload is diagnostic; the authoritative check remains
  `contentRevision`.

### 8.2 Dual write

Before backfill starts, switch the worker into dual-write mode:

```text
for each projection:
  write target (new) first
  write serving (old) second
  ACK only when BOTH confirm the target revision
```

Why target first: if the process dies between the two writes, the new collection is ahead and the
old one still serves; a retry repairs the old one. Writing old first would leave the new collection
missing updates while the pipeline claims progress.

Cost: ACKs stall whenever either collection is unavailable. During reindex that is acceptable;
outside reindex, single-write resumes. The alternative — ACK on the serving collection only and rely
on a post-backfill catch-up — is faster but requires a reliable delta source. The catch-up loop in
§8.3 still runs, so both designs converge; dual-write is the safer default.

### 8.3 Backfill and catch-up

1. Pin a Fortis scan (revision `R0`) if the storage supports revision-pinned enumeration. Without
   it, a backfill cannot distinguish "not yet copied" from "changed during copy".
2. Scroll the new collection? No — the backfill source is Fortis, not the old collection. The old
   collection may itself be stale, and copying it would copy the bug.
3. Write every context at the revision read from Fortis. Re-read immediately before each write;
   never write a revision read minutes ago if a newer one exists.
4. After the initial pass, run catch-up passes over all contexts whose new-collection
   `contentRevision` differs from Fortis (or whose point is missing). Repeat until a full pass finds
   no differences, or until a bounded number of passes is exceeded (then abort and keep the old
   alias).
5. Compare counts only as a diagnostic. Count equality is not completeness; a missing point and a
   duplicate can cancel out.

Without a pinned enumeration or a durable dirty-key index, step 4 is best-effort. That is exactly
the blocker in §12 and design §8: a full reindex can be *operationally* complete but not *provably*
complete.

### 8.4 Alias swap

`POST /collections/aliases` accepts a list of actions (`delete_alias`, `create_alias`,
`rename_alias`). Proposed swap:

```json
{
  "actions": [
    { "delete_alias": { "alias_name": "recl_ctx_active" } },
    { "create_alias": { "alias_name": "recl_ctx_active", "collection_name": "recl_ctx_v1_..._new" } }
  ]
}
```

Open points:

- Whether all actions in one request are applied atomically is **not stated in the fetched API
  reference** (**要確認**). If they are not, a brief window with no alias exists. Mitigation: keep
  the old alias name resolvable until the new one is confirmed, or create a second alias and switch
  readers by config. Do not assume atomicity.
- Whether creating an alias that already exists fails is **要確認**. The swap above deletes first to
  avoid relying on overwrite behavior.
- Whether writes through an alias behave identically to writes to the concrete collection is
  **要確認**. This design avoids the question by resolving the alias and writing to the concrete
  name.
- Alias visibility to in-flight requests is **要確認**. A request that resolved the old collection
  just before the swap will write to the old collection; dual-write and the grace period cover it.

Swap preconditions:

- Catch-up pass clean.
- New collection status green (not red/yellow).
- Payload indexes created and ready (`wait=true`).
- Dual write active and both collections demonstrably receiving updates.
- A recorded alias state that can be restored.

### 8.5 Rollback and cleanup

- Keep `recl_ctx_previous` pointing at the old collection for the rollback window (for example 7
  days, configurable). Keep dual-writing for a shorter grace period (for example 24–48 h).
- Rollback = swap the alias back to the old collection. This is only safe while the old collection
  is still being kept current. Once dual write stops, rollback requires a reverse backfill and must
  be treated as a new reindex, not an instant revert.
- After the window: stop dual write, delete the old collection only after a final invariant check
  and a backup/snapshot decision. Never delete the only copy of a derived index during an incident.
- Deleting a collection is irreversible in normal operation; require explicit approval (same rule
  as production deploys).

### 8.6 Why updates are not lost at switch time

The argument is:

1. Dual write starts before any backfill.
2. Every write carries the authoritative revision read immediately before the write.
3. The catch-up loop drives the new collection to equality with Fortis, not with the old collection.
4. The swap happens only after a clean pass.
5. Dual write continues after the swap, so requests that resolved the old name still update both.
6. The old collection is retained, so a bad swap is reversible.

The remaining hole is a Fortis enumeration that is incomplete or not pinned. No amount of Qdrant
cleverness fixes that; it is a Fortis capability requirement.

## 9. Tombstone retention and GC gate

Design §8: "keep the tombstone until Qdrant confirms deletion and the recheck/retention policy is
satisfied. Never silently resurrect an older point."

Proposed gate. A tombstone may be considered for GC only when **all** hold:

1. `deleted == true` and `indexAction == DELETE` in the authoritative aggregate.
2. The Qdrant delete returned `completed` with `wait=true`.
3. A read at `consistency = majority` (or `all`) returns no point for the derived ID.
4. `indexedContentRevision >= contentRevision` was written by the reconciler's delete path (which
   only ACKs after steps 2–3).
5. A recheck after a retention window (for example 24 h) still finds no point and still sees the
   same tombstone revision.
6. Any stale-writer or orphan alerts touching this context are resolved.

Why the ACK is evidence: `IndexReconciler.reconcileDelete` deletes first, inspects, re-reads Fortis,
and only then calls `acknowledgeIndex`. So for a tombstone, `indexedContentRevision >= contentRevision`
does imply a verified absence at ACK time. It does **not** prove the point will stay absent; that is
why step 5 exists.

Blocking gap: `ContextAggregate` has **no `deletedAt` or `updatedAt` field** (see `core/.../Context.kt`).
The design document mentions `deletedAt`, but the implementation does not carry it. Today the gate
cannot compute a retention window from authoritative data; it would have to use a local worker clock
or a receipt, neither of which is authoritative across restarts. Options:

- Add `deletedAtEpochMillis` (and ideally `updatedAtEpochMillis`) to the aggregate in a schema
  version bump. This changes the Fortis codec and is a separate issue.
- Or persist a `GC-verified` receipt in the aggregate when the delete is verified; then retention is
  measured from the receipt. Also a schema change.
- Or accept a non-authoritative local window during the MVP and document that GC is best-effort.
  Not recommended for a security-relevant deletion path.

This is a **blocker** for a correct GC gate, independent of Qdrant (§12).

Additional rules:

- The GC job must never delete a tombstone merely because the point is absent; absence can also mean
  "never indexed" or "reindex in progress".
- A tombstone that is recreated before GC must cancel eligibility. Because recreation advances
  `contentRevision` and clears `deleted`, condition 1 fails. The GC job must re-read immediately
  before deleting.
- GC of a tombstone and GC of the Qdrant collection are separate operations. Never couple them.
- If an invariant check finds a point for a GC-eligible context, eligibility is withdrawn and the
  point is deleted again.

## 10. Monitoring and invariant checks

### 10.1 Metrics

Names are proposals; no metrics exist today.

| Metric | Type | Meaning |
| --- | --- | --- |
| `recl_index_reconcile_total{status}` | counter | Outcomes from `IndexReconciler`. |
| `recl_index_lag_revisions{tenant}` | histogram | `contentRevision - point.contentRevision` when known. |
| `recl_index_missing_points_total{tenant}` | counter | Fortis says live, Qdrant says `NotFound`. |
| `recl_index_orphan_points_total{tenant}` | counter | Qdrant point with no live authoritative context. |
| `recl_index_revision_mismatch_total{kind}` | counter | Point older/newer than authoritative (`older`, `newer`, `duplicate`). |
| `recl_index_corrupt_payload_total` | counter | Undecodable or identity-mismatched payload. |
| `recl_qdrant_requests_total{op,outcome}` | counter | Includes retries and 429s. |
| `recl_qdrant_request_duration_seconds{op}` | histogram | End-to-end including retries. |
| `recl_qdrant_wait_timeout_total` | counter | `wait_timeout` responses; should be rare. |
| `recl_index_ack_lag_seconds{tenant}` | histogram | Time between authoritative write and ACK. |
| `recl_index_reindex_progress{collection}` | gauge | Copied / expected, plus last clean-pass timestamp. |
| `recl_index_alias_info{alias,collection}` | gauge | Detects alias drift. |

Structured logs: `tenantId`, `contextId`, `contentRevision`, `indexGeneration`, `collection`,
Qdrant `operation_id`, attempt, outcome. Never log `title`, `summary`, or `content`.

### 10.2 Invariant checks

Read-only, periodic, rate-limited, and **never** a source of ACKs.

1. **Revision mismatch.** For each point, read Fortis. Classify:
   - point revision `<` authoritative and live → stale (repair by reconcile).
   - point revision `>` authoritative → anomaly (impossible under correct ordering); alert, delete
     the point, do not ACK.
   - point revision `==` but identity fields differ → corruption/ID collision; alert.
2. **Missing point.** For each live Fortis context, check existence. Expensive at scale; run
   tenant-partitioned or sampled. A missing point is a completeness issue, not an authorization
   issue.
3. **Orphan point.** Scroll Qdrant by tenant filter and compare to Fortis: missing context,
   tombstoned context, or an older revision. Delete only after a second confirmation read.
4. **Duplicate points.** Filter by `contextId`; with deterministic IDs there should be exactly one.
   Duplicates indicate an ID-scheme change or a manual write.
5. **Payload schema drift.** Missing `contentRevision`, unknown `schemaVersion`, mismatched
   `embeddingModel`, vector dimension different from collection config.
6. **Alias drift.** `GET /aliases` must show the expected active target. A worker holding a stale
   collection name is a stale writer by construction.
7. **Collection health.** Status green, expected point count within tolerance, expected payload
   indexes present.

Caveats:

- `scroll` is page-by-page and not documented as a snapshot. Concurrent writes can produce false
  positives (a point moves between pages). Use two consecutive clean passes, or compare against a
  pinned Fortis revision and only flag points older than the scan start. Do not treat a single pass
  as proof.
- Counts (`points_count`) are documented as approximate; never use them for correctness.
- The checker must be able to run in dry-run mode and must never delete during an incident unless
  explicitly enabled.

### 10.3 Alerts

- `recl_index_revision_mismatch_total{kind="newer"} > 0` — page immediately.
- Orphan growth beyond a threshold.
- Alias points at an unexpected collection.
- Reindex catch-up pass not clean within the target window.
- `wait_timeout` rate above baseline.
- ACK lag p95 above the agreed SLO.
- GC gate blocked by missing `deletedAt` (configuration error).

## 11. Acceptance tests to add

**None of these tests exist.** The list is a plan for a future change. Fake-based tests can run in
`:index` with no Qdrant dependency; real-Qdrant tests belong in a separate, opt-in integration
module so the default build stays offline.

### 11.1 Fake-based (no Qdrant)

Use a scripted fake of the Qdrant HTTP/gRPC surface, or the existing `InMemorySearchIndex` plus a
new `FakeQdrant` that records requests.

- Point ID derivation is deterministic, tenant-separated, and stable across JVM restarts.
- Payload round-trip preserves every documented field; `content` is absent.
- `inspect` maps empty result → `NotFound`, matching payload → `Found`, undecodable payload →
  `Unavailable`, identity mismatch → `Unavailable`.
- `upsert` with `wait_timeout` followed by a visible point returns `Applied`.
- `upsert` with `wait_timeout` and no visible point exhausts retries and returns `Unavailable`.
- Lost response after a successful write: read-back prevents a second write and ACKs.
- Older revision cannot overwrite a newer point (simulated `update_filter`).
- Older revision **does** insert when no point exists; reconcile repairs it.
- Delete of an absent point returns `Applied`.
- Delete vs recreate: no ACK, next pass upserts.
- Upsert vs delete: stale insert is detected, not ACKed, next pass deletes.
- Poison 400/413 is not retried indefinitely; a terminal-failure signal is emitted.
- 429 with and without `Retry-After` respects backoff.
- Batch split on 413 preserves per-point outcomes.
- Alias resolver reads `GET /aliases`; a stale cached name is rejected after an epoch change.
- Reindex: update during copy lands in both collections; catch-up pass finds zero differences.
- Reindex abort keeps the old alias and does not delete either collection.
- Rollback swaps back while dual write is active.
- GC gate refuses while `deletedAt` is missing; refuses until delete verified + window elapsed;
  cancels on recreate.
- Invariant checker classifies stale, orphan, missing, duplicate, newer-than-authoritative.
- Metrics counters increment once per logical outcome, not once per retry.

### 11.2 Real Qdrant (opt-in integration)

- `wait=true` + `consistency=majority` gives read-after-write under the deployed replica config.
- `wait=true` alone with default consistency can return a stale read on a replicated deployment
  (documents the reason for the explicit consistency choice).
- `update_filter` behavior: existing newer point untouched; missing point inserted regardless;
  point without `contentRevision` untouched.
- `ordering=weak|medium|strong` under two concurrent writers: measure, do not assume fencing.
- Delete of a missing ID succeeds.
- Alias swap visibility: readers either see old or new, never a missing alias (or the documented
  window is quantified).
- Creating an existing alias / deleting a missing alias behavior.
- Payload size limits and 413 thresholds for `title` + `summary` + vector.
- Collection creation with the chosen dimension/distance; `Cosine` normalization.
- Payload index creation with `wait=true` and `is_tenant` behavior.
- Scroll pagination under concurrent writes; duplicate/missing pages.
- 429 and `Retry-After` behavior; timeout behavior.
- Collection deletion and recreation of the same name (should be avoided; verify failure modes).

### 11.3 Proposed test names

Marked **not implemented**:

```text
QdrantPointIdTest.derivesStableUuidV5ForTenantAndContext
QdrantPointIdTest.doesNotCollideAcrossTenants
QdrantSearchIndexTest.inspectMapsMissingPointToNotFound
QdrantSearchIndexTest.inspectMapsCorruptPayloadToUnavailable
QdrantSearchIndexTest.upsertWaitsForCompletedStatus
QdrantSearchIndexTest.upsertVerifiesReadBackBeforeApplied
QdrantSearchIndexTest.waitTimeoutIsResolvedByReadBack
QdrantSearchIndexTest.lostResponseDoesNotWriteTwice
QdrantSearchIndexTest.olderRevisionDoesNotDowngradeExistingPoint
QdrantSearchIndexTest.olderRevisionInsertAfterDeleteIsRepaired
QdrantSearchIndexTest.deleteOfAbsentPointIsApplied
QdrantSearchIndexTest.poisonPointIsNotRetriedForever
QdrantSearchIndexTest.rateLimitHonoursRetryAfterWhenPresent
QdrantSearchIndexTest.batchSplitsOnPayloadTooLarge
ReindexCoordinatorTest.updateDuringBackfillLandsInBothCollections
ReindexCoordinatorTest.catchUpPassIsRequiredBeforeSwap
ReindexCoordinatorTest.failedCatchUpKeepsOldAlias
ReindexCoordinatorTest.rollbackRequiresDualWriteWindow
TombstoneGcGateTest.refusesWithoutDeletionEvidence
TombstoneGcGateTest.refusesBeforeRetentionWindow
TombstoneGcGateTest.cancelsWhenContextIsRecreated
IndexInvariantCheckerTest.detectsPointNewerThanAuthoritative
IndexInvariantCheckerTest.detectsOrphanAndMissingPoints
IndexInvariantCheckerTest.doesNotAckOrDeleteInDryRun
```

## 12. Blockers and open questions

### 12.1 Blockers

1. **Fortis binding is blocked.** No public client, no KV auth, no leader routing, no published
   artifact (`docs/fortis-binding-assessment.md`). This adapter cannot be exercised end to end
   before that changes.
2. **Completeness depends on Fortis enumeration.** A revision-pinned, complete prefix scan (Fortis
   #45 / recl #14) or an equivalent durable dirty-key index is required. Without it, the worker can
   repair known contexts but cannot prove convergence.
3. **No `deletedAt` / `updatedAt` in `ContextAggregate`.** The tombstone GC gate cannot measure a
   retention window from authoritative data. Needs a schema/codec change (separate issue).
4. **No conditional delete in the port.** `delete(tenantId, contextId)` cannot express "only if
   revision <= N". The design accepts repair-after-the-fact; a stricter contract needs a port
   revision.
5. **No terminal failure result in the port.** A permanent 400/413 can only be reported as
   `Unavailable`, so poison points are re-selected forever.
6. **Qdrant cannot CAS.** `update_filter` protects existing points only. A stale insert after delete
   is unpreventable at the index layer and is handled by revalidation plus repair.

### 12.2 Open questions

| Question | Why it matters | Needs |
| --- | --- | --- |
| Which Qdrant version is supported? | Every API detail below is version-dependent. | Deployment decision + upgrade policy. |
| REST or gRPC client? | `SearchIndexPort` is synchronous; Qdrant clients are often async. | A blocking bridge or a suspend port decision, mirroring the Fortis sync/async gap. |
| Embedding model, dimension, distance, normalization? | Fixes the collection schema and reindex cadence. | Model selection issue. |
| Single collection with tenant partitioning, or collection per tenant? | Isolation, cost, blast radius, alias count. | Architecture decision. |
| Is `title`/`summary` duplication acceptable? | Security review of derived data. | Review. |
| Is `wait=true` + `consistency=majority` sufficient in the deployed topology? | Read-after-write guarantee. | Real-Qdrant test. |
| Are alias actions in one request atomic? | Swap gap / missing alias window. | Real-Qdrant test. |
| Does `update_filter` exist and behave as documented on the pinned version? | Stale-writer guard. | Real-Qdrant test. |
| What are the payload and request size limits? | Batch size, summary truncation, 413 policy. | Measurement. |
| How is Qdrant authenticated and isolated per tenant? | Payload filters are not a security boundary. | Deployment/security design. |
| What is the ACK lag SLO and orphan threshold? | Alert thresholds. | Operations decision. |
| Where do reindex jobs run? | Same worker or separate controller; affects rate limiting and dual write. | Deployment design. |

### 12.3 Suggested follow-up issues

1. `index`: extend `SearchIndexPort` with an expected revision for `upsert`/`delete`, and a terminal
   failure result.
2. `core`: add `deletedAtEpochMillis` / `updatedAtEpochMillis` and bump the aggregate schema.
3. `index`: add a `QdrantSearchIndex` adapter plus a `FakeQdrant` and the fake-based acceptance tests
   above (no network dependency).
4. `ops`: add the opt-in real-Qdrant integration suite and record the answers to §12.2.
5. `index`: add `ReindexCoordinator` and `TombstoneGcGate` once 2 and the Fortis enumeration gate
   are done.

## 13. References

Verified on 2026-11-05 against the Qdrant API reference v1.20.x:

- Upsert points — `PUT /collections/{collection_name}/points`; `wait`, `ordering`, `update_filter`,
  `update_mode`; result statuses `acknowledged` / `completed` / `wait_timeout`.
  <https://api.qdrant.tech/api-reference/points/upsert-points>
- Retrieve points — `POST /collections/{collection_name}/points`; `consistency`, `with_payload`,
  `with_vector`.
  <https://api.qdrant.tech/api-reference/points/get-points>
- Delete points — `POST /collections/{collection_name}/points/delete`; `wait`, `ordering`.
  <https://api.qdrant.tech/api-reference/points/delete-points>
- Scroll points — `POST /collections/{collection_name}/points/scroll`; `offset`, `limit`, `filter`.
  <https://api.qdrant.tech/api-reference/points/scroll-points>
- Set payload — `POST /collections/{collection_name}/points/payload`.
  <https://api.qdrant.tech/api-reference/points/set-payload>
- Update collection aliases — `POST /collections/aliases`; `delete_alias`, `create_alias`,
  `rename_alias`.
  <https://api.qdrant.tech/api-reference/aliases/update-aliases>
- List all aliases — `GET /aliases`.
  <https://api.qdrant.tech/api-reference/aliases/get-collections-aliases>
- Create payload index — `PUT /collections/{collection_name}/index`; `keyword`, `integer`, `bool`,
  `is_tenant`.
  <https://api.qdrant.tech/api-reference/indexes/create-field-index>
- Collections — vector size/distance, `Cosine` normalization.
  <https://qdrant.tech/documentation/concepts/collections/>

Repository references:

- `index/src/main/kotlin/net/kigawa/recl/index/SearchIndexPort.kt`
- `index/src/main/kotlin/net/kigawa/recl/index/IndexReconciler.kt`
- `index/src/test/kotlin/net/kigawa/recl/index/InMemorySearchIndex.kt`
- `index/src/test/kotlin/net/kigawa/recl/index/IndexReconcilerTest.kt`
- `docs/index-reconciliation.md`
- `docs/design.md` §7, §8, §11
- `docs/fortis-binding-assessment.md`
