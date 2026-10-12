package net.kigawa.recl.core

/** A public view intentionally excludes physical CAS generations, receipts and index bookkeeping. */
data class ContextSnapshot(
    val id: String,
    val kind: ContextKind,
    val status: ContextStatus,
    val title: String,
    val summary: String,
    val content: String,
    val scopes: Set<String>,
    val contentRevision: Long,
)

sealed interface ContextReadResult {
    data class Found(val context: ContextSnapshot) : ContextReadResult
    data object NotFound : ContextReadResult
    data object Unavailable : ContextReadResult
    data object Forbidden : ContextReadResult
    data object InvalidRequest : ContextReadResult
}

/**
 * Authoritative direct-ID retrieval. No Qdrant or index snapshot may override a read failure.
 * The current policy is owner-only. Future ACL/RBAC must be enforced at this same boundary.
 */
class ContextReader(private val storage: ContextStorage) {
    fun get(principal: Principal, contextId: String): ContextReadResult {
        val stored = when (val result = storage.get(principal.tenantId, contextId)) {
            is ReadResult.Found -> result.stored.value
            ReadResult.NotFound -> return ContextReadResult.NotFound
            ReadResult.Unavailable -> return ContextReadResult.Unavailable
        }
        if (stored.tenantId != principal.tenantId || stored.ownerId != principal.id || stored.deleted) {
            // Hide the existence of a Context the caller cannot see.
            return ContextReadResult.NotFound
        }
        return ContextReadResult.Found(ContextSnapshot(
            id = stored.id,
            kind = stored.kind,
            status = stored.status,
            title = stored.title,
            summary = stored.summary,
            content = stored.content,
            scopes = stored.scopes.toSet(),
            contentRevision = stored.contentRevision,
        ))
    }
}

/** Internal authenticated port. This is NOT an HTTP/MCP server or a production authenticator. */
class TrustedContextQueryGateway(
    private val reader: ContextReader,
    private val authenticator: SessionAuthenticator,
) {
    fun get(bearerCredential: String, contextId: String): ContextReadResult {
        if (bearerCredential.isBlank()) return ContextReadResult.Forbidden
        val principal = authenticator.authenticate(bearerCredential) ?: return ContextReadResult.Forbidden
        val canonicalId = ContextIds.canonicalUuidV7(contextId) ?: return ContextReadResult.InvalidRequest
        return reader.get(principal, canonicalId)
    }
}
