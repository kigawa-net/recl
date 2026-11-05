package net.kigawa.recl.index

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.ReadResult
import java.util.UUID

enum class EnumerationStatus { COMPLETE, UNSUPPORTED, UNAVAILABLE, TOO_LARGE }

enum class ReconcileStatus { UP_TO_DATE, UPSERTED, DELETED, RETRY_REQUIRED, UNAVAILABLE, UNSUPPORTED }

data class ContextReconcileResult(
    val contextId: String,
    val status: ReconcileStatus,
    val targetContentRevision: Long? = null,
    val detail: String? = null,
)

data class TenantReconcileReport(
    val enumeration: EnumerationStatus,
    val results: List<ContextReconcileResult>,
)

/**
 * Converges the derived search index to authoritative Context state (recl #5/#13, design §8).
 *
 * Invariants:
 * - authoritative storage is re-read before acting and again before acknowledging;
 * - an ACK only advances `indexedContentRevision` via the core CAS, never `contentRevision`;
 * - a failed/ambiguous index operation is retried a bounded number of times and left unacknowledged;
 * - a tombstone is deleted from the index before the ACK, so GC cannot run ahead of the index;
 * - enumeration failures never produce a partial "complete" report.
 *
 * This class does not implement Qdrant, watch wake-ups, backoff scheduling or multi-group routing.
 * The port and this loop are test-only until those production gates are validated.
 */
class IndexReconciler(
    private val storage: ContextStorage,
    private val index: SearchIndexPort,
    private val embeddings: EmbeddingProvider,
    private val generations: IndexGenerationSource = IndexGenerationSource { UUID.randomUUID().toString() },
    private val maxEnumeratedObjects: Int = 10_000,
    private val maxAttempts: Int = 3,
) {
    init {
        require(maxEnumeratedObjects in 1..10_000)
        require(maxAttempts >= 1)
    }

    private val acknowledgements = ContextService(storage)

    fun reconcileTenant(tenantId: String): TenantReconcileReport {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        return when (val enumeration = storage.enumerateTenant(tenantId, maxEnumeratedObjects)) {
            is EnumerationResult.Complete -> TenantReconcileReport(
                enumeration = EnumerationStatus.COMPLETE,
                results = enumeration.contexts.map { reconcileContext(tenantId, it.value.id) },
            )
            EnumerationResult.Unsupported -> TenantReconcileReport(EnumerationStatus.UNSUPPORTED, emptyList())
            EnumerationResult.Unavailable -> TenantReconcileReport(EnumerationStatus.UNAVAILABLE, emptyList())
            EnumerationResult.TooLarge -> TenantReconcileReport(EnumerationStatus.TOO_LARGE, emptyList())
        }
    }

    fun reconcileContext(tenantId: String, contextId: String): ContextReconcileResult {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(contextId.isNotBlank()) { "contextId must not be blank" }
        val stored = when (val read = storage.get(tenantId, contextId)) {
            is ReadResult.Found -> read.stored
            ReadResult.NotFound -> return removeOrphan(tenantId, contextId)
            ReadResult.Unavailable -> return unavailable(contextId, "authoritative read unavailable")
        }
        return if (stored.value.deleted) {
            reconcileDelete(tenantId, stored.value)
        } else {
            reconcileUpsert(tenantId, stored.value)
        }
    }

    /** A missing authoritative key means the index point must not survive it. */
    private fun removeOrphan(tenantId: String, contextId: String): ContextReconcileResult =
        when (index.delete(tenantId, contextId)) {
            IndexWriteResult.Unsupported -> unsupported(contextId)
            IndexWriteResult.Unavailable -> unavailable(contextId, "index delete unavailable")
            IndexWriteResult.Applied -> when (index.inspect(tenantId, contextId)) {
                IndexInspection.NotFound -> result(contextId, ReconcileStatus.DELETED, detail = "authoritative key absent")
                is IndexInspection.Found -> retry(contextId, "index entry still visible after delete")
                IndexInspection.Unavailable -> unavailable(contextId, "index read unavailable after delete")
                IndexInspection.Unsupported -> unsupported(contextId)
            }
        }

    private fun reconcileDelete(tenantId: String, context: ContextAggregate): ContextReconcileResult {
        val target = context.contentRevision
        // Even when the ACK says the delete is done, verify the point is actually gone.
        if (context.indexedContentRevision >= target) {
            when (index.inspect(tenantId, context.id)) {
                IndexInspection.NotFound -> return upToDate(context.id, target)
                is IndexInspection.Found -> Unit
                IndexInspection.Unavailable -> return unavailable(context.id, "index read unavailable")
                IndexInspection.Unsupported -> return unsupported(context.id)
            }
        }

        repeat(maxAttempts) {
            when (index.delete(tenantId, context.id)) {
                IndexWriteResult.Unavailable -> return unavailable(context.id, "index delete unavailable")
                IndexWriteResult.Unsupported -> return unsupported(context.id)
                IndexWriteResult.Applied -> Unit
            }
            when (index.inspect(tenantId, context.id)) {
                IndexInspection.NotFound -> Unit
                is IndexInspection.Found -> return@repeat
                IndexInspection.Unavailable -> return unavailable(context.id, "index read unavailable after delete")
                IndexInspection.Unsupported -> return unsupported(context.id)
            }
            val latest = storage.get(tenantId, context.id)
            if (latest is ReadResult.NotFound) {
                // No authoritative key remains, so no ACK is possible; the point is already gone.
                return result(context.id, ReconcileStatus.DELETED, target, "authoritative key vanished")
            }
            if (latest !is ReadResult.Found) return unavailable(context.id, "authoritative read unavailable after delete")
            val value = latest.stored.value
            if (!value.deleted || value.contentRevision != target) {
                return retry(context.id, "authoritative state changed during delete; no acknowledgement")
            }
            if (!acknowledgements.acknowledgeIndex(tenantId, context.id, target)) {
                return retry(context.id, "index delete acknowledgement pending")
            }
            return result(context.id, ReconcileStatus.DELETED, target)
        }
        return retry(context.id, "index delete did not converge")
    }

    private fun reconcileUpsert(tenantId: String, context: ContextAggregate): ContextReconcileResult {
        val target = context.contentRevision

        when (val initial = index.inspect(tenantId, context.id)) {
            is IndexInspection.Found -> if (initial.point.contentRevision == target) {
                // Same content revision: acknowledge a lagging checkpoint, otherwise it is done.
                return if (context.indexedContentRevision >= target ||
                    acknowledgements.acknowledgeIndex(tenantId, context.id, target)
                ) {
                    upToDate(context.id, target)
                } else {
                    retry(context.id, "index acknowledgement pending")
                }
            }
            IndexInspection.NotFound -> Unit
            IndexInspection.Unavailable -> return unavailable(context.id, "index read unavailable")
            IndexInspection.Unsupported -> return unsupported(context.id)
        }

        repeat(maxAttempts) {
            val generation = generations.next()
            if (generation.isBlank()) return retry(context.id, "index generation is blank")

            val embedding = try {
                embeddings.embed(render(context))
            } catch (_: Exception) {
                return unavailable(context.id, "embedding provider failed")
            }
            if (embedding.isEmpty() || embedding.any { !it.isFinite() }) {
                return unavailable(context.id, "embedding provider returned an empty or non-finite vector")
            }

            val point = IndexPoint(
                tenantId = tenantId,
                contextId = context.id,
                contentRevision = target,
                indexGeneration = generation,
                status = context.status,
                title = context.title,
                summary = context.summary,
                content = context.content,
                embedding = embedding,
            )
            when (index.upsert(point)) {
                IndexWriteResult.Unavailable -> return unavailable(context.id, "index write unavailable")
                IndexWriteResult.Unsupported -> return unsupported(context.id)
                IndexWriteResult.Applied -> Unit
            }

            when (val after = index.inspect(tenantId, context.id)) {
                is IndexInspection.Found -> if (after.point.contentRevision != target) return@repeat
                IndexInspection.NotFound -> return@repeat
                IndexInspection.Unavailable -> return unavailable(context.id, "index read unavailable after upsert")
                IndexInspection.Unsupported -> return unsupported(context.id)
            }

            // Never ACK a projection whose target was superseded while the index was being written.
            val latest = storage.get(tenantId, context.id)
            if (latest is ReadResult.NotFound) {
                return retry(context.id, "authoritative key vanished during projection")
            }
            if (latest !is ReadResult.Found) return unavailable(context.id, "authoritative read unavailable after upsert")
            val value = latest.stored.value
            if (value.deleted || value.contentRevision != target) {
                return retry(context.id, "authoritative state changed during projection; no acknowledgement")
            }
            if (!acknowledgements.acknowledgeIndex(tenantId, context.id, target)) {
                return retry(context.id, "index acknowledgement pending")
            }
            return result(context.id, ReconcileStatus.UPSERTED, target)
        }
        return retry(context.id, "index projection did not converge")
    }

    private fun render(context: ContextAggregate): String =
        "${context.title}\n${context.summary}\n${context.content}"

    private fun result(
        contextId: String,
        status: ReconcileStatus,
        target: Long? = null,
        detail: String? = null,
    ) = ContextReconcileResult(contextId, status, target, detail)

    private fun upToDate(contextId: String, target: Long) =
        result(contextId, ReconcileStatus.UP_TO_DATE, target)

    private fun retry(contextId: String, detail: String) =
        result(contextId, ReconcileStatus.RETRY_REQUIRED, detail = detail)

    private fun unavailable(contextId: String, detail: String) =
        result(contextId, ReconcileStatus.UNAVAILABLE, detail = detail)

    private fun unsupported(contextId: String) =
        result(contextId, ReconcileStatus.UNSUPPORTED, detail = "the index port does not support this operation")
}
