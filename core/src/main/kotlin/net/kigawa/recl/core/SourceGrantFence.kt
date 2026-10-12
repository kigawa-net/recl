package net.kigawa.recl.core

/**
 * Trusted, immutable GitHub App grant identity. NEVER derived from the client's Source URI.
 * Repository ID is GitHub's immutable numeric repository identity; a name/owner is display data.
 * Installation ID and strictly monotonic generation distinguish revoke/regrant and transfer.
 */
data class SourceGrantFence(
    val tenantId: String,
    val ownerId: String,
    val repositoryOwner: String,
    val repositoryName: String,
    val repositoryId: Long,
    val installationId: Long,
    val generation: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        val slug = Regex("[A-Za-z0-9._-]{1,128}")
        require(tenantId.isNotBlank() && tenantId.length <= 128 && !tenantId.any(Char::isISOControl))
        require(ownerId.isNotBlank() && ownerId.length <= 128 && !ownerId.any(Char::isISOControl))
        require(slug.matches(repositoryOwner) && slug.matches(repositoryName))
        require(repositoryId > 0 && installationId > 0 && generation > 0 && expiresAtEpochMillis > 0)
    }

    fun authorizes(tenant: String, owner: String, source: ContextSource, now: Long): Boolean {
        if (tenantId != tenant || ownerId != owner || expiresAtEpochMillis <= now) return false
        if (source.type !in setOf(SourceType.GITHUB_COMMIT, SourceType.GITHUB_PR, SourceType.GITHUB_ISSUE))
            return false
        val prefix = "https://github.com/${repositoryOwner.lowercase(java.util.Locale.ROOT)}/${repositoryName.lowercase(java.util.Locale.ROOT)}/"
        return source.uri.startsWith(prefix)
    }
}

/**
 * MUST perform grant-generation and Context-revision predicates as ONE atomic state machine
 * operation, including result/receipt durability. A separate getGrant then CAS is invalid.
 * If a real Fortis single-group batch is unavailable, return Unsupported.
 */
fun interface GrantFencedContextCommit {
    fun compareAndSwapWithGrant(
        tenantId: String,
        contextId: String,
        expectedStorageRevision: Long,
        updated: ContextAggregate,
        fence: SourceGrantFence,
    ): GrantFencedWriteResult
}

sealed interface GrantFencedWriteResult {
    data object Applied : GrantFencedWriteResult
    data object Conflict : GrantFencedWriteResult
    data object GrantRevoked : GrantFencedWriteResult
    data object Unsupported : GrantFencedWriteResult
    data object Unavailable : GrantFencedWriteResult
    data object OutcomeUnknown : GrantFencedWriteResult
}
