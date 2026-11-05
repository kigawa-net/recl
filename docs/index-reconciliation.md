# Index reconciliation

Status: **test-only slice**. It converges a derived search index to authoritative Context state using
injected ports; it is not wired to Qdrant, Watch, or a production scheduler yet.

Implements recl #5/#13 and [design §8](design.md#8-index-reconciliation).

## Shape

| Type | Purpose |
| --- | --- |
| `SearchIndexPort` | `inspect` / `upsert` / `delete` one point per Context |
| `EmbeddingProvider` | Turns the rendered Context text into a vector |
| `IndexGenerationSource` | Opaque per-attempt marker stored in the point payload |
| `IndexReconciler` | Enumeration + per-Context convergence loop |

Nothing in this module is authoritative. `ContextAggregate` in `:core` remains the only source of
truth; an index point is a projection at a specific `contentRevision`.

## Invariants

1. **Re-read before acting.** Each Context is read from `ContextStorage` before any index operation.
2. **ACK after verification.** A point is acknowledged only after `inspect` shows the expected
   `contentRevision`, and only after the authoritative record is re-read and still has that same
   revision and `deleted = false`.
3. **Never ACK a superseded projection.** If a writer advances `contentRevision` while the index is
   being written, the result is `RETRY_REQUIRED` and `indexedContentRevision` does not move.
4. **Delete before ACK.** A tombstone requires the point to be absent before the ACK, so GC can
   never run ahead of the index.
5. **Bounded retries.** `maxAttempts` (default 3) applies to a single Context; failures leave the
   work unacknowledged rather than pretending success.
6. **No partial completeness.** Enumeration returns only `COMPLETE`, `UNSUPPORTED`, `UNAVAILABLE`, or
   `TOO_LARGE`. A truncated scan is never reported as complete, and non-complete reports carry no
   per-Context results.
7. **ACK is bookkeeping.** `ContextService.acknowledgeIndex` advances `indexedContentRevision` via CAS
   and never changes `contentRevision` or `indexAction`.

## Outcomes

| `ReconcileStatus` | Meaning |
| --- | --- |
| `UP_TO_DATE` | The point already matches the target revision and the ACK is current |
| `UPSERTED` | The point was written at the target revision and acknowledged |
| `DELETED` | No point remains for a tombstone or absent key (acknowledged when a key exists) |
| `RETRY_REQUIRED` | A transient/racy condition prevented convergence; safe to run again |
| `UNAVAILABLE` | Storage, index, or embedding provider could not answer |
| `UNSUPPORTED` | The index port does not implement the operation |

`RETRY_REQUIRED`, `UNAVAILABLE`, and `UNSUPPORTED` never advance the ACK.

## What is deliberately absent

- Qdrant adapter and payload/schema decisions.
- Watch-based wake-ups; enumeration is the only candidate source for now.
- Backoff/scheduling, sharding, and multi-tenant routing.
- Production embedding model selection.

These are blocked on the Fortis binding gates in
[fortis-binding-assessment.md](fortis-binding-assessment.md).
