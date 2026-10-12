package net.kigawa.recl.core

enum class ContextKind { DECISION, ARCHITECTURE, CONSTRAINT, TASK, FINDING, REFERENCE, PREFERENCE }
enum class ContextStatus { PROPOSED, CONFIRMED, SUPERSEDED, ARCHIVED }
enum class IndexAction { UPSERT, DELETE }

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
    }

    companion object { const val MAX_RECEIPTS = 32 }
}

data class ContextPatch(
    val title: String? = null,
    val summary: String? = null,
    val content: String? = null,
    val status: ContextStatus? = null,
    val scopes: Set<String>? = null,
    val pinned: Boolean? = null,
) {
    fun applyTo(context: ContextAggregate): ContextAggregate = context.copy(
        title = title ?: context.title,
        summary = summary ?: context.summary,
        content = content ?: context.content,
        status = status ?: context.status,
        scopes = scopes?.toSet() ?: context.scopes,
        pinned = pinned ?: context.pinned,
    )
}
