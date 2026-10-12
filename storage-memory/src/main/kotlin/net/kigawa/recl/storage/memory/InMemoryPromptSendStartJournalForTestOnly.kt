package net.kigawa.recl.storage.memory

import java.time.Clock
import java.util.UUID
import net.kigawa.recl.core.*

/**
 * Deterministic, process-local protocol model. NEVER a production journal: no durable
 * send-start receipt, cross-process mutex, failover, or authoritatively fenced dispatch.
 */
class InMemoryPromptSendStartJournalForTestOnly(
    private val storage: InMemoryContextStorage,
    private val tokens: ContextTokenCounter,
    private val clock: Clock = Clock.systemUTC(),
    private val maxEntries: Int = 10_000,
    private val maxLeaseMillis: Long = 2_000,
) : TrustedPromptSendStartJournal {
    init {
        require(maxEntries in 1..10_000)
        require(maxLeaseMillis in 1..5_000)
    }

    private data class Identity(val tenant: String, val principal: String, val operation: String)
    private data class Record(
        val permit: PromptSendStartPermit,
        val requestDigest: String,
        val outcome: TrustedPromptSendResult? = null,
    )
    private val journalLock = Any()
    private val records = mutableMapOf<Identity, Record>()

    fun observedOutcomeForTestOnly(
        tenantId: String, principalId: String, operationId: String,
    ): TrustedPromptSendResult? = synchronized(journalLock) {
        records[Identity(tenantId, principalId, operationId)]?.outcome
    }

    override fun tryStart(
        request: PromptOutputRequest,
        requestDigest: String,
    ): PromptDispatchStartResult {
        if (requestDigest != PromptDispatchBinding.digest(request))
            return PromptDispatchStartResult.RequestConflict
        var result: PromptDispatchStartResult? = null
        // Reuse the existing test-only single-JVM Context+Grant authority. This synchronizes
        // its snapshot admission and the below journal insertion with revoke/regrant.
        val checked = storage.promptBarrierForTestOnly(tokens).authorizeAndSend(
            request,
            TrustedPromptSink { _, contexts ->
                synchronized(journalLock) {
                    val identity = Identity(
                        request.principal.tenantId,
                        request.principal.id,
                        request.destination.operationId,
                    )
                    val previous = records[identity]
                    if (previous != null) {
                        result = if (previous.requestDigest == requestDigest)
                            PromptDispatchStartResult.AlreadyRecorded
                        else PromptDispatchStartResult.RequestConflict
                        TrustedPromptSendResult.DefinitelyNotSent
                    } else if (records.size >= maxEntries) {
                        // Never evict a receipt to make room for an unsafe duplicate dispatch.
                        result = PromptDispatchStartResult.Unsupported
                        TrustedPromptSendResult.DefinitelyNotSent
                    } else {
                        val now = clock.millis()
                        // Trusted evidence/grants may expire before the intended lease.
                        val proofExpiry = contexts.minOfOrNull { snapshot ->
                            val raw = when (val got =
                                storage.get(request.principal.tenantId, snapshot.id)
                            ) {
                                is ReadResult.Found -> got.stored.value
                                ReadResult.NotFound, ReadResult.Unavailable -> null
                            }
                            raw?.sources?.minOfOrNull { src ->
                                minOf(src.evidence?.expiresAtEpochMillis ?: now,
                                    src.evidence?.sourceGrantFence?.expiresAtEpochMillis ?: now)
                            } ?: now
                        } ?: now
                        val expires = minOf(now + maxLeaseMillis, proofExpiry)
                        if (expires <= now) {
                            result = PromptDispatchStartResult.Forbidden
                            TrustedPromptSendResult.DefinitelyNotSent
                        } else {
                            val permit = PromptSendStartPermit(
                                permitId = UUID.randomUUID().toString(),
                                tenantId = request.principal.tenantId,
                                principalId = request.principal.id,
                                destination = request.destination,
                                requestDigest = requestDigest,
                                startedAtEpochMillis = now,
                                expiresAtEpochMillis = expires,
                            )
                            records[identity] = Record(permit, requestDigest)
                            result = PromptDispatchStartResult.Started(permit, contexts.toList())
                            // Represents a durable logical send-start INTENT in this test
                            // model, NOT that a remote model has received anything.
                            TrustedPromptSendResult.Accepted
                        }
                    }
                }
            },
        )
        return result ?: when (checked) {
            PromptOutputResult.Accepted -> PromptDispatchStartResult.OutcomeUnknown
            PromptOutputResult.NotSent -> PromptDispatchStartResult.InvalidRequest
            PromptOutputResult.OutcomeUnknown -> PromptDispatchStartResult.OutcomeUnknown
            PromptOutputResult.Forbidden -> PromptDispatchStartResult.Forbidden
            PromptOutputResult.InvalidRequest -> PromptDispatchStartResult.InvalidRequest
            PromptOutputResult.Unsupported -> PromptDispatchStartResult.Unsupported
            PromptOutputResult.Unavailable -> PromptDispatchStartResult.Unavailable
        }
    }

    override fun settle(
        permit: PromptSendStartPermit,
        outcome: TrustedPromptSendResult,
    ): PromptDispatchSettlement = synchronized(journalLock) {
        val identity = Identity(permit.tenantId, permit.principalId, permit.destination.operationId)
        val previous = records[identity] ?: return@synchronized PromptDispatchSettlement.Rejected
        if (previous.permit != permit)
            return@synchronized PromptDispatchSettlement.Rejected
        if (previous.outcome != null)
            return@synchronized if (previous.outcome == outcome)
                PromptDispatchSettlement.Recorded else PromptDispatchSettlement.Rejected
        records[identity] = previous.copy(outcome = outcome)
        PromptDispatchSettlement.Recorded
    }
}
