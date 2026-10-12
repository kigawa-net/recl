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
) : TrustedPromptSendStartJournal, TrustedPromptDispatchReceiptReader, TrustedPromptPermitAdmission {
    init {
        require(maxEntries in 1..10_000)
        require(maxLeaseMillis in 1..5_000)
    }

    private data class Identity(val tenant: String, val principal: String, val operation: String)
    private data class Record(
        val permit: PromptSendStartPermit,
        val requestDigest: String,
        /** Safe metadata only; never persist raw ContextSnapshot/model prompt in receipts. */
        val query: PinnedQuery,
        val usedTokens: Int,
        val limited: Boolean,
        val outcome: TrustedPromptSendResult? = null,
        val settledAtEpochMillis: Long? = null,
    )
    private val journalLock = Any()
    private val records = mutableMapOf<Identity, Record>()

    fun observedOutcomeForTestOnly(
        tenantId: String, principalId: String, operationId: String,
    ): TrustedPromptSendResult? = synchronized(journalLock) {
        records[Identity(tenantId, principalId, operationId)]?.outcome
    }


    override fun lookup(query: PromptDispatchReceiptQuery): PromptDispatchReceiptRead =
        synchronized(journalLock) {
            val identity = Identity(query.principal.tenantId, query.principal.id,
                query.destination.operationId)
            val record = records[identity]
                ?: return@synchronized PromptDispatchReceiptRead.NotFound
            if (record.permit.destination != query.destination ||
                record.requestDigest != query.requestDigest
            ) return@synchronized PromptDispatchReceiptRead.BindingConflict
            val state = when (record.outcome) {
                null -> PromptDispatchReceiptState.STARTED_PENDING
                TrustedPromptSendResult.Accepted -> PromptDispatchReceiptState.TRANSPORT_ACCEPTED
                TrustedPromptSendResult.DefinitelyNotSent ->
                    PromptDispatchReceiptState.DEFINITELY_NOT_SENT
                TrustedPromptSendResult.OutcomeUnknown -> PromptDispatchReceiptState.OUTCOME_UNKNOWN
            }
            PromptDispatchReceiptRead.Found(
                state = state,
                requestDigest = record.requestDigest,
                sendStartEpochMillis = record.permit.startedAtEpochMillis,
                settledAtEpochMillis = record.settledAtEpochMillis,
            )
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
                            records[identity] = Record(
                                permit, requestDigest,
                                request.query.copy(scopes = request.query.scopes.toSet()),
                                request.selection.usedTokens, request.selection.limited,
                            )
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

    /**
     * Test-only send-start preflight: validate the exact previously issued permit,
     * recorded operation identity, current grant epoch and *all* selected snapshots under
     * the test Context/Grant monitor. Do not call the external model from this callback.
     *
     * The check ends before a later downstream send; a real distributed transport MUST
     * serialize authorization/lease with the actual wire-send-start operation itself.
     */
    override fun check(
        permit: PromptSendStartPermit,
        contexts: List<ContextSnapshot>,
    ): PromptPermitAdmissionDecision {
        val key = Identity(permit.tenantId, permit.principalId, permit.destination.operationId)
        val record = synchronized(journalLock) { records[key] }
            ?: return PromptPermitAdmissionDecision.Denied
        if (record.permit != permit || record.outcome != null ||
            clock.millis() < permit.startedAtEpochMillis ||
            clock.millis() >= permit.expiresAtEpochMillis
        ) return PromptPermitAdmissionDecision.Denied

        val request = PromptOutputRequest(
            principal = Principal(permit.tenantId, permit.principalId),
            destination = permit.destination,
            query = record.query,
            selection = PinnedContextResult.Found(
                contexts.toList(), record.usedTokens, record.limited
            ),
        )
        if (try { PromptDispatchBinding.digest(request) != permit.requestDigest }
            catch (_: Exception) { true }
        ) return PromptPermitAdmissionDecision.Denied

        var epochMatched = false
        val checked = storage.promptBarrierForTestOnly(tokens).authorizeAndSend(
            request,
            TrustedPromptSink { _, validated ->
                val grantRefs = validated.flatMap { snapshot ->
                    val raw = (storage.get(permit.tenantId, snapshot.id)
                        as? ReadResult.Found)?.stored?.value
                    raw?.sources?.mapNotNull { source ->
                        source.evidence?.sourceGrantFence?.let { fence ->
                            PromptSourceGrantEpoch(snapshot.id, source.sourceId, fence)
                        }
                    } ?: emptyList()
                }
                epochMatched = grantRefs.isNotEmpty() &&
                    grantRefs.size == validated.sumOf { it.sources.size } &&
                    try { PromptGrantEpochBinding.digest(grantRefs) == permit.grantEpochDigest }
                    catch (_: Exception) { false } &&
                    synchronized(journalLock) {
                        records[key]?.permit == permit && records[key]?.outcome == null
                    }
                // The test preflight never calls a model, even on successful admission.
                TrustedPromptSendResult.DefinitelyNotSent
            },
        )
        return when (checked) {
            PromptOutputResult.NotSent ->
                if (epochMatched) PromptPermitAdmissionDecision.Allowed
                else PromptPermitAdmissionDecision.Denied
            PromptOutputResult.Unsupported -> PromptPermitAdmissionDecision.Unsupported
            PromptOutputResult.Unavailable -> PromptPermitAdmissionDecision.Unavailable
            else -> PromptPermitAdmissionDecision.Denied
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
        records[identity] = previous.copy(outcome = outcome, settledAtEpochMillis = clock.millis())
        PromptDispatchSettlement.Recorded
    }
}
