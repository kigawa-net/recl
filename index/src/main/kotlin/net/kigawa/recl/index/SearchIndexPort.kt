package net.kigawa.recl.index

import net.kigawa.recl.core.ContextStatus

/**
 * One derived search-index point. It is a projection of an authoritative Context at a specific
 * `contentRevision`; it is never an authorization or content authority.
 *
 * The vector is kept as a `List<Float>` so points can be compared structurally in tests. A real
 * adapter may keep it opaque.
 */
data class IndexPoint(
    val tenantId: String,
    val contextId: String,
    val contentRevision: Long,
    /** Opaque per-attempt marker for diagnostics/fencing. Not a substitute for revision checks. */
    val indexGeneration: String,
    val status: ContextStatus,
    val title: String,
    val summary: String,
    val content: String,
    val embedding: List<Float>,
)

sealed interface IndexInspection {
    data class Found(val point: IndexPoint) : IndexInspection
    data object NotFound : IndexInspection
    data object Unavailable : IndexInspection
    data object Unsupported : IndexInspection
}

sealed interface IndexWriteResult {
    data object Applied : IndexWriteResult
    data object Unavailable : IndexWriteResult
    data object Unsupported : IndexWriteResult
}

/**
 * Search-index port (Qdrant-shaped, but storage-agnostic). This is a derived index only: callers
 * must revalidate every candidate against authoritative storage before returning it.
 */
interface SearchIndexPort {
    fun inspect(tenantId: String, contextId: String): IndexInspection

    fun upsert(point: IndexPoint): IndexWriteResult

    /** Idempotent removal of the point for a Context. Absence is success. */
    fun delete(tenantId: String, contextId: String): IndexWriteResult
}

/** The final embedding provider is not selected; an implementation must return a finite vector. */
fun interface EmbeddingProvider {
    fun embed(text: String): List<Float>
}

/** Produces an opaque marker per projection attempt, for inspection and diagnostics. */
fun interface IndexGenerationSource {
    fun next(): String
}
