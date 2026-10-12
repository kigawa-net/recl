package net.kigawa.recl.core

enum class ContextKind { DECISION, ARCHITECTURE, CONSTRAINT, TASK, FINDING, REFERENCE, PREFERENCE }
enum class ContextStatus { PROPOSED, CONFIRMED, SUPERSEDED, ARCHIVED }
enum class IndexAction { UPSERT, DELETE }
/** Aligns with Kivra's Memory Sensitivity. RESTRICTED bodies must never be persisted to recl. */
enum class Sensitivity { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }

/** A tenant is established by trusted authentication, never by a caller-supplied search filter. */
data class Principal(val tenantId: String, val id: String) {
    init { require(tenantId.isNotBlank() && id.isNotBlank()) }
}

data class OperationReceipt(
    val requestId: String,
    val payloadDigest: String,
    val resultingContentRevision: Long,
)

/** MVP: all authorization-relevant fields and index intent belong to one authoritative key. */
data class ContextAggregate(
    val tenantId: String,
    val id: String,
    val ownerId: String,
    val kind: ContextKind,
    val status: ContextStatus,
    val title: String,
    val summary: String,
    val content: String,
    val scopes: Set<String> = emptySet(),
    val pinned: Boolean = false,
    val sensitivity: Sensitivity = Sensitivity.INTERNAL,
    /** Inclusive UTC Unix epoch milliseconds; transaction time is separate. */
    val validFromEpochMillis: Long? = null,
    /** Exclusive UTC Unix epoch milliseconds. */
    val validUntilEpochMillis: Long? = null,
    /** Citation metadata is authoritative only as a record of what the client submitted. */
    val sources: List<ContextSource> = emptyList(),
    val contentRevision: Long = 1,
    val indexedContentRevision: Long = 0,
    val indexAction: IndexAction = IndexAction.UPSERT,
    val deleted: Boolean = false,
    val operationReceipts: List<OperationReceipt> = emptyList(),
) {
    init {
        require(tenantId.isNotBlank() && id.isNotBlank() && ownerId.isNotBlank())
        require(contentRevision >= 1 && indexedContentRevision in 0..contentRevision)
        require(operationReceipts.size <= MAX_RECEIPTS)
        require(sources.size <= SourceReferencePolicy.MAX_PER_CONTEXT)
        require(sources.map { it.sourceId }.distinct().size == sources.size)
        require(status != ContextStatus.CONFIRMED ||
            sources.none { it.trust == SourceTrust.AGENT_INFERRED &&
                it.verification != VerificationState.VERIFIED }) {
            "unverified agent inference cannot be confirmed"
        }
        require(sensitivity != Sensitivity.RESTRICTED) { "RESTRICTED Context bodies cannot be persisted" }
        require(validFromEpochMillis == null || validUntilEpochMillis == null ||
            validFromEpochMillis < validUntilEpochMillis) { "validity interval must be nonempty" }
    }

    fun isValidAt(nowEpochMillis: Long): Boolean =
        (validFromEpochMillis == null || nowEpochMillis >= validFromEpochMillis) &&
        (validUntilEpochMillis == null || nowEpochMillis < validUntilEpochMillis)

    /** Automatic injection is stricter than explicit owner-authorized Direct Get. */
    fun eligibleForAutomaticInjection(nowEpochMillis: Long): Boolean =
        pinned && !deleted && status == ContextStatus.CONFIRMED &&
        sensitivity in setOf(Sensitivity.PUBLIC, Sensitivity.INTERNAL) && isValidAt(nowEpochMillis) &&
        // Unverified primary/agent evidence is not promoted to a confirmed prompt assertion.
        sources.all { it.verification == VerificationState.VERIFIED }

    companion object { const val MAX_RECEIPTS = 32 }
}

data class ContextPatch(
    val title: String? = null,
    val summary: String? = null,
    val content: String? = null,
    val status: ContextStatus? = null,
    val scopes: Set<String>? = null,
    val pinned: Boolean? = null,
    val sensitivity: Sensitivity? = null,
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
    /** null: unchanged, empty: clear, nonempty: replace all source citations. */
    val sources: List<SourceSubmission>? = null,
) {
    fun applyTo(context: ContextAggregate): ContextAggregate = context.copy(
        title = title ?: context.title,
        summary = summary ?: context.summary,
        content = content ?: context.content,
        status = status ?: context.status,
        scopes = scopes?.toSet() ?: context.scopes,
        pinned = pinned ?: context.pinned,
        sensitivity = sensitivity ?: context.sensitivity,
        validFromEpochMillis = validFromEpochMillis ?: context.validFromEpochMillis,
        validUntilEpochMillis = validUntilEpochMillis ?: context.validUntilEpochMillis,
        sources = sources?.let { SourceReferencePolicy.normalizeMany(it).map { source -> source.unverified() } }
            ?: context.sources,
    )
}
