package net.kigawa.recl.core

/** The future transport adapter supplies a bearer credential, not self-reported Principal claims.
 * An implementation MUST verify issuer, audience, expiry, subject and tenant grants.
 * No authenticator is shipped with the Core bootstrap.
 */
fun interface SessionAuthenticator {
    fun authenticate(bearerCredential: String): Principal?
}

/** Public input contains no ownerId, tenantId, Principal, or payloadDigest. */
data class CreateContextMutation(
    val contextId: String,
    val requestId: String,
    val kind: ContextKind,
    val status: ContextStatus,
    val title: String,
    val summary: String,
    val content: String,
    val scopes: Set<String> = emptySet(),
    val pinned: Boolean = false,
    val sensitivity: Sensitivity = Sensitivity.INTERNAL,
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
)

data class UpdateContextMutation(
    val contextId: String,
    val requestId: String,
    val expectedContentRevision: Long,
    val patch: ContextPatch,
)

data class DeleteContextMutation(
    val contextId: String,
    val requestId: String,
    val expectedContentRevision: Long,
)

/** An internal boundary for REST/MCP to share. This is NOT itself a network server or an authenticator. */
class TrustedMutationGateway(
    private val contextService: ContextService,
    private val authenticator: SessionAuthenticator,
) {
    fun create(bearerCredential: String, request: CreateContextMutation): MutationResult {
        val principal = authenticate(bearerCredential) ?: return MutationResult.Forbidden
        if (!validIds(request.contextId, request.requestId) ||
            request.title.isBlank() || request.scopes.size > 32 || request.scopes.any { it.isBlank() || it.length > 256 } ||
            request.sensitivity == Sensitivity.RESTRICTED ||
            !validInterval(request.validFromEpochMillis, request.validUntilEpochMillis)
        ) return MutationResult.InvalidRequest

        val canonical = request.copy(
            contextId = canonicalId(request.contextId),
            requestId = canonicalId(request.requestId),
            title = normalize(request.title),
            summary = normalize(request.summary),
            content = normalize(request.content),
            scopes = request.scopes.map(::normalize).toSet(),
        )
        val digest = CanonicalMutationDigest.create(principal, canonical.requestId, canonical)
        val aggregate = ContextAggregate(
            tenantId = principal.tenantId,
            id = canonical.contextId,
            ownerId = principal.id,
            kind = canonical.kind,
            status = canonical.status,
            title = canonical.title,
            summary = canonical.summary,
            content = canonical.content,
            scopes = canonical.scopes,
            pinned = canonical.pinned,
            sensitivity = canonical.sensitivity,
            validFromEpochMillis = canonical.validFromEpochMillis,
            validUntilEpochMillis = canonical.validUntilEpochMillis,
        )
        return contextService.create(principal, aggregate, canonical.requestId, digest)
    }

    fun update(bearerCredential: String, request: UpdateContextMutation): MutationResult {
        val principal = authenticate(bearerCredential) ?: return MutationResult.Forbidden
        if (!validIds(request.contextId, request.requestId) ||
            request.expectedContentRevision < 1 ||
            (request.patch.scopes?.size ?: 0) > 32 ||
            request.patch.scopes?.any { it.isBlank() || it.length > 256 } == true ||
            request.patch.sensitivity == Sensitivity.RESTRICTED ||
            !validInterval(request.patch.validFromEpochMillis, request.patch.validUntilEpochMillis)
        ) return MutationResult.InvalidRequest

        val canonical = request.copy(
            contextId = canonicalId(request.contextId),
            requestId = canonicalId(request.requestId),
            patch = request.patch.copy(
                title = request.patch.title?.let(::normalize),
                summary = request.patch.summary?.let(::normalize),
                content = request.patch.content?.let(::normalize),
                scopes = request.patch.scopes?.map(::normalize)?.toSet(),
            ),
        )
        val digest = CanonicalMutationDigest.update(principal, canonical.requestId, canonical)
        return contextService.update(principal, canonical.contextId, canonical.expectedContentRevision,
            canonical.requestId, digest, canonical.patch)
    }

    fun delete(bearerCredential: String, request: DeleteContextMutation): MutationResult {
        val principal = authenticate(bearerCredential) ?: return MutationResult.Forbidden
        if (!validIds(request.contextId, request.requestId) || request.expectedContentRevision < 1)
            return MutationResult.InvalidRequest
        val canonical = request.copy(
            contextId = canonicalId(request.contextId),
            requestId = canonicalId(request.requestId),
        )
        val digest = CanonicalMutationDigest.delete(principal, canonical.requestId, canonical)
        return contextService.delete(principal, canonical.contextId, canonical.expectedContentRevision,
            canonical.requestId, digest)
    }

    private fun validInterval(from: Long?, until: Long?): Boolean =
        from == null || until == null || from < until

    private fun authenticate(credential: String): Principal? =
        if (credential.isBlank()) null else authenticator.authenticate(credential)

    private fun validIds(contextId: String, requestId: String) =
        ContextIds.isUuidV7(contextId) && ContextIds.isUuidV7(requestId)

    private fun canonicalId(id: String): String = ContextIds.canonicalUuidV7(id) ?: error("invalid id")

}
