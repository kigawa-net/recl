package net.kigawa.recl.core

import java.time.Clock

/**
 * A token counter must be supplied by the eventual MCP/REST context renderer.
 * The count must include the exact rendered prompt, citation metadata and separators.
 * A character count or a UTF-8 byte heuristic is NOT proof of a model token budget.
 */
fun interface ContextTokenCounter {
    fun count(context: ContextSnapshot): Int
}

data class PinnedQuery(
    /** Exact scope names are relevance filters, not permissions. Empty means all authorized scopes. */
    val scopes: Set<String> = emptySet(),
    val tokenBudget: Int,
    val maxContexts: Int,
)

sealed interface PinnedContextResult {
    data class Found(
        val contexts: List<ContextSnapshot>,
        val usedTokens: Int,
        /** True if eligible items did not fit. Never counts unauthorized items. */
        val limited: Boolean,
    ) : PinnedContextResult

    data object Forbidden : PinnedContextResult
    data object InvalidRequest : PinnedContextResult
    data object Unsupported : PinnedContextResult
    data object Unavailable : PinnedContextResult
    data object TooLarge : PinnedContextResult
}

/**
 * Internal, owner-only pinned retrieval. Does not rely on Qdrant.
 * A complete authoritative enumeration is mandatory: never treat a partial scan as a full result.
 */
class PinnedContextReader(
    private val storage: ContextStorage,
    private val tokenCounter: ContextTokenCounter,
    private val maxEnumeratedObjects: Int = 10_000,
    private val clock: Clock = Clock.systemUTC(),
) {
    init { require(maxEnumeratedObjects in 1..10_000) }

    fun list(principal: Principal, query: PinnedQuery): PinnedContextResult {
        if (query.tokenBudget !in 1..100_000 ||
            query.maxContexts !in 1..100 ||
            query.scopes.size > 32 ||
            query.scopes.any { it.isBlank() || it.length > 256 }
        ) return PinnedContextResult.InvalidRequest

        val enumeration = when (val result = storage.enumerateTenant(principal.tenantId, maxEnumeratedObjects)) {
            is EnumerationResult.Complete -> result.contexts
            EnumerationResult.Unsupported -> return PinnedContextResult.Unsupported
            EnumerationResult.Unavailable -> return PinnedContextResult.Unavailable
            EnumerationResult.TooLarge -> return PinnedContextResult.TooLarge
        }

        if (enumeration.any { it.value.tenantId != principal.tenantId }) return PinnedContextResult.Unavailable
        val ids = enumeration.map { it.value.id }
        if (ids.distinct().size != ids.size) return PinnedContextResult.Unavailable

        val selected = ArrayList<ContextSnapshot>()
        var used = 0
        var limited = false
        // A deterministic traversal ensures stable selection when the authoritative state is unchanged.
        for (id in ids.sorted()) {
            val context = when (val result = storage.get(principal.tenantId, id)) {
                is ReadResult.Found -> result.stored.value
                ReadResult.NotFound -> continue
                ReadResult.Unavailable -> return PinnedContextResult.Unavailable
            }
            if (context.tenantId != principal.tenantId || context.ownerId != principal.id ||
                !context.eligibleForAutomaticInjection(clock.millis())
            ) continue

            // The global scope belongs to the same tenant: it is not a cross-tenant grant.
            if (query.scopes.isNotEmpty() &&
                "global" !in context.scopes &&
                context.scopes.none { it in query.scopes }
            ) continue

            val view = ContextSnapshot(
                id = context.id, kind = context.kind, status = context.status,
                title = context.title, summary = context.summary, content = context.content,
                scopes = context.scopes.toSet(), contentRevision = context.contentRevision,
                sensitivity = context.sensitivity,
                validFromEpochMillis = context.validFromEpochMillis,
                validUntilEpochMillis = context.validUntilEpochMillis,
                sources = context.sources.toList(),
            )
            val cost = try { tokenCounter.count(view) } catch (_: Exception) {
                return PinnedContextResult.Unavailable
            }
            if (cost <= 0) return PinnedContextResult.Unavailable
            if (selected.size >= query.maxContexts || cost > query.tokenBudget - used) {
                limited = true
                continue
            }
            selected += view
            used += cost
        }
        return PinnedContextResult.Found(selected, used, limited)
    }
}

/** Internal auth boundary; a verified production SessionAuthenticator is not yet available. */
class TrustedPinnedQueryGateway(
    private val reader: PinnedContextReader,
    private val authenticator: SessionAuthenticator,
) {
    fun list(bearerCredential: String, query: PinnedQuery): PinnedContextResult {
        if (bearerCredential.isBlank()) return PinnedContextResult.Forbidden
        val principal = authenticator.authenticate(bearerCredential) ?: return PinnedContextResult.Forbidden
        return reader.list(principal, query)
    }
}
