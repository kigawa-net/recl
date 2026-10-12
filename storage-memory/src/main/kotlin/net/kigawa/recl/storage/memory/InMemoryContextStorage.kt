package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.StoredContext
import net.kigawa.recl.core.WriteResult
import net.kigawa.recl.core.SourceGrantFence
import net.kigawa.recl.core.GrantFencedContextCommit
import net.kigawa.recl.core.GrantFencedWriteResult
import net.kigawa.recl.core.VerificationState
import net.kigawa.recl.core.TrustedGrantEpochReader
import net.kigawa.recl.core.GrantEpochReadResult
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.ContextSnapshot
import net.kigawa.recl.core.TrustedPromptOutputBarrier
import net.kigawa.recl.core.TrustedPromptSink
import net.kigawa.recl.core.TrustedPromptSendResult
import net.kigawa.recl.core.PromptOutputRequest
import net.kigawa.recl.core.PromptOutputResult
import net.kigawa.recl.core.ContextTokenCounter
import net.kigawa.recl.core.SourceGrantReadAdmission
import net.kigawa.recl.core.ReadAdmissionDecision
import net.kigawa.recl.core.Sensitivity
import java.time.Clock

/** Ephemeral, JVM-only test adapter. NEVER use as a production failover. */
class InMemoryContextStorage(private val clock: Clock = Clock.systemUTC()) : ContextStorage, GrantFencedContextCommit, TrustedGrantEpochReader {
    private val lock = Any()
    private val data = mutableMapOf<Pair<String, String>, StoredContext>()
    private data class GrantState(val fence: SourceGrantFence, val revoked: Boolean)
    private val grantStates = mutableMapOf<Triple<String, String, Long>, GrantState>()

    /** Deterministic test-only authority; a real system MUST use Fortis single-group apply. */
    fun installGrantForTestOnly(fence: SourceGrantFence) = synchronized(lock) {
        val key = Triple(fence.tenantId, fence.ownerId, fence.repositoryId)
        val old = grantStates[key]
        require(old == null || fence.generation > old.fence.generation) {
            "regrant must advance the immutable generation even after revocation"
        }
        grantStates[key] = GrantState(fence, revoked = false)
    }

    fun revokeGrantForTestOnly(fence: SourceGrantFence) = synchronized(lock) {
        val key = Triple(fence.tenantId, fence.ownerId, fence.repositoryId)
        val old = grantStates[key] ?: return@synchronized
        grantStates[key] = old.copy(revoked = true)
    }

    /** Test-only linearizable observation, protected by the same local lock as revocation. */
    override fun check(principal: Principal, expected: SourceGrantFence): GrantEpochReadResult =
        synchronized(lock) {
            if (principal.tenantId != expected.tenantId || principal.id != expected.ownerId ||
                expected.expiresAtEpochMillis <= clock.millis()
            ) return@synchronized GrantEpochReadResult.RevokedOrChanged
            val state = grantStates[Triple(expected.tenantId, expected.ownerId, expected.repositoryId)]
                ?: return@synchronized GrantEpochReadResult.RevokedOrChanged
            if (state.revoked || state.fence != expected)
                GrantEpochReadResult.RevokedOrChanged
            else GrantEpochReadResult.Current
        }

    /** Both grant and Context checks occur under the SAME test-local lock. NEVER a production adapter. */
    override fun compareAndSwapWithGrant(
        tenantId: String,
        contextId: String,
        expectedStorageRevision: Long,
        updated: ContextAggregate,
        fence: SourceGrantFence,
    ): GrantFencedWriteResult = synchronized(lock) {
        val key = Triple(fence.tenantId, fence.ownerId, fence.repositoryId)
        val state = grantStates[key]
        if (state == null || state.revoked || state.fence != fence ||
            fence.tenantId != tenantId || fence.expiresAtEpochMillis <= clock.millis()
        ) return@synchronized GrantFencedWriteResult.GrantRevoked
        val current = data[tenantId to contextId]
            ?: return@synchronized GrantFencedWriteResult.Conflict
        if (current.storageRevision != expectedStorageRevision ||
            updated.tenantId != tenantId || updated.id != contextId ||
            updated.ownerId != fence.ownerId ||
            updated.contentRevision != current.value.contentRevision + 1
        ) return@synchronized GrantFencedWriteResult.Conflict
        if (updated.sources.none { it.verification == VerificationState.VERIFIED &&
                it.evidence?.sourceGrantFence == fence
            }
        ) return@synchronized GrantFencedWriteResult.Conflict
        data[tenantId to contextId] =
            StoredContext(snapshot(updated), current.storageRevision + 1)
        GrantFencedWriteResult.Applied
    }


    /**
     * Test-only output barrier: shares a single process monitor with grant revocations and
     * Context writes and holds it until the sink has returned. No network, crash, replica,
     * timeout or durable receipt semantics: DO NOT bind to a real production Model Gateway.
     */
    fun promptBarrierForTestOnly(tokenCounter: ContextTokenCounter): TrustedPromptOutputBarrier =
        TrustedPromptOutputBarrier { request, sink ->
            synchronized(lock) {
                if (request.query.tokenBudget !in 1..100_000 ||
                    request.query.maxContexts !in 1..100 ||
                    request.query.scopes.size > 32 ||
                    request.query.scopes.any { it.isBlank() || it.length > 256 } ||
                    request.selection.contexts.size > request.query.maxContexts ||
                    request.selection.usedTokens < 0 ||
                    request.selection.usedTokens > request.query.tokenBudget
                ) return@synchronized PromptOutputResult.InvalidRequest
                val selection = request.selection.contexts.toList()
                if (selection.map { it.id }.distinct().size != selection.size)
                    return@synchronized PromptOutputResult.InvalidRequest
                if (selection.isEmpty()) return@synchronized PromptOutputResult.NotSent
                val now = clock.millis()
                val admitted = ArrayList<ContextSnapshot>(selection.size)
                var cost = 0
                val readAdmission = SourceGrantReadAdmission(this)
                for (requested in selection) {
                    val stored = data[request.principal.tenantId to requested.id]
                        ?: return@synchronized PromptOutputResult.Forbidden
                    val current = stored.value
                    if (current.tenantId != request.principal.tenantId ||
                        current.ownerId != request.principal.id ||
                        !current.eligibleForAutomaticInjection(now) ||
                        current.sensitivity !in setOf(Sensitivity.PUBLIC, Sensitivity.INTERNAL) ||
                        (request.query.scopes.isNotEmpty() &&
                         "global" !in current.scopes &&
                         current.scopes.none { it in request.query.scopes })
                    ) return@synchronized PromptOutputResult.Forbidden
                    when (readAdmission.check(request.principal, current, now)) {
                        ReadAdmissionDecision.Allowed -> Unit
                        ReadAdmissionDecision.Denied -> return@synchronized PromptOutputResult.Forbidden
                        ReadAdmissionDecision.Unsupported -> return@synchronized PromptOutputResult.Unsupported
                        ReadAdmissionDecision.Unavailable -> return@synchronized PromptOutputResult.Unavailable
                    }
                    val fresh = ContextSnapshot(
                        id = current.id, kind = current.kind, status = current.status,
                        title = current.title, summary = current.summary,
                        content = current.content, scopes = current.scopes.toSet(),
                        contentRevision = current.contentRevision,
                        sensitivity = current.sensitivity,
                        validFromEpochMillis = current.validFromEpochMillis,
                        validUntilEpochMillis = current.validUntilEpochMillis,
                        sources = current.sources.map { it.publicAt(now) },
                    )
                    if (fresh != requested) return@synchronized PromptOutputResult.Forbidden
                    val itemCost = try { tokenCounter.count(fresh) } catch (_: Exception) {
                        return@synchronized PromptOutputResult.Unavailable
                    }
                    if (itemCost <= 0 || itemCost > request.query.tokenBudget - cost)
                        return@synchronized PromptOutputResult.InvalidRequest
                    cost += itemCost
                    admitted += fresh
                }
                if (cost != request.selection.usedTokens)
                    return@synchronized PromptOutputResult.InvalidRequest
                // The single-process test lock gives a deterministic ordering between
                // revocation and the synchronous send. Production needs a distributed
                // lease/barrier protocol with bounded blocking and a durable send journal.
                try {
                    when (sink.send(request.destination, admitted.toList())) {
                        TrustedPromptSendResult.Accepted -> PromptOutputResult.Accepted
                        TrustedPromptSendResult.DefinitelyNotSent -> PromptOutputResult.NotSent
                        TrustedPromptSendResult.OutcomeUnknown -> PromptOutputResult.OutcomeUnknown
                    }
                } catch (_: Exception) {
                    // A network exception does not prove the remote side did not receive bytes.
                    PromptOutputResult.OutcomeUnknown
                }
            }
        }

    override fun get(tenantId: String, id: String): ReadResult = synchronized(lock) {
        val stored = data[tenantId to id] ?: return@synchronized ReadResult.NotFound
        ReadResult.Found(stored.copy(value = snapshot(stored.value)))
    }

    override fun createIfAbsent(value: ContextAggregate): WriteResult = synchronized(lock) {
        val key = value.tenantId to value.id
        if (key in data) return@synchronized WriteResult.Conflict
        val stored = StoredContext(snapshot(value), 1)
        data[key] = stored
        WriteResult.Applied(stored)
    }

    override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult = synchronized(lock) {
        if (value.tenantId != tenantId || value.id != id) return@synchronized WriteResult.Conflict
        val key = tenantId to id
        val old = data[key] ?: return@synchronized WriteResult.Conflict
        if (old.storageRevision != expectedStorageRevision) return@synchronized WriteResult.Conflict
        val stored = StoredContext(snapshot(value), old.storageRevision + 1)
        data[key] = stored
        WriteResult.Applied(stored)
    }

    override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult = synchronized(lock) {
        require(maxItems in 1..10_000)
        val matches = data.filterKeys { it.first == tenantId }
        // Do not report a truncated prefix as a complete, authoritative enumeration.
        if (matches.size > maxItems) return@synchronized EnumerationResult.TooLarge
        EnumerationResult.Complete(matches.values
            .sortedBy { it.value.id }
            .map { it.copy(value = snapshot(it.value)) })
    }

    private fun snapshot(value: ContextAggregate): ContextAggregate = value.copy(
        scopes = value.scopes.toSet(),
        operationReceipts = value.operationReceipts.toList(),
        sources = value.sources.toList(),
    )
}
