package net.kigawa.recl.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Clock

/**
 * A deterministic, versioned hash of the precise prompt admission request.
 * No raw prompt, credential, attestation ID or source content is persisted in the journal.
 */
object PromptDispatchBinding {
    fun digest(request: PromptOutputRequest): String {
        require(request.selection.contexts.size <= 100 &&
            request.query.scopes.size <= 32 &&
            request.query.maxContexts in 1..100 &&
            request.query.tokenBudget in 1..100_000
        ) { "oversize prompt request" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            fun put(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 1_000_000 &&
                    output.size().toLong() + bytes.size + 4L <= 4_000_000L
                ) { "oversize binding payload" }
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            fun putOptional(value: Long?) {
                out.writeBoolean(value != null)
                if (value != null) out.writeLong(value)
            }
            put("recl.prompt.send-start.v1")
            put(request.principal.tenantId)
            put(request.principal.id)
            put(request.destination.provider)
            put(request.destination.model)
            put(request.destination.operationId)
            out.writeInt(request.query.tokenBudget)
            out.writeInt(request.query.maxContexts)
            out.writeInt(request.query.scopes.size)
            request.query.scopes.sorted().forEach(::put)
            out.writeInt(request.selection.usedTokens)
            out.writeBoolean(request.selection.limited)
            out.writeInt(request.selection.contexts.size)
            request.selection.contexts.forEach { context ->
                put(context.id)
                put(context.kind.name)
                put(context.status.name)
                put(context.title)
                put(context.summary)
                put(context.content)
                out.writeLong(context.contentRevision)
                put(context.sensitivity.name)
                putOptional(context.validFromEpochMillis)
                putOptional(context.validUntilEpochMillis)
                out.writeInt(context.scopes.size)
                context.scopes.sorted().forEach(::put)
                out.writeInt(context.sources.size)
                context.sources.forEach { source ->
                    put(source.sourceId)
                    put(source.type.name)
                    put(source.uri)
                    put(source.locator ?: "")
                    putOptional(source.retrievedAtEpochMillis)
                    put(source.contentDigest ?: "")
                    put(source.trust.name)
                    put(source.verification.name)
                }
            }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
        return "sha256:" + sha.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * Server-origin permit; its ID is NOT a bearer credential or public API field.
 * A production sink must verify permit expiry, operation binding and issuer's state
 * at its actual network send-start boundary.
 */
data class PromptSendStartPermit(
    val permitId: String,
    val tenantId: String,
    val principalId: String,
    val destination: PromptDestination,
    val requestDigest: String,
    val startedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(permitId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(tenantId.isNotBlank() && principalId.isNotBlank())
        require(requestDigest.matches(Regex("sha256:[0-9a-f]{64}")))
        require(startedAtEpochMillis >= 0 && expiresAtEpochMillis > startedAtEpochMillis)
        require(expiresAtEpochMillis - startedAtEpochMillis <= 5_000) {
            "unbounded admission lease is forbidden"
        }
    }
}

sealed interface PromptDispatchStartResult {
    data class Started(
        val permit: PromptSendStartPermit,
        /** Exact snapshots already re-authorized by this trusted authority. */
        val contexts: List<ContextSnapshot>,
    ) : PromptDispatchStartResult
    /** A stored send-start record exists. Never retry just because settlement is unknown. */
    data object AlreadyRecorded : PromptDispatchStartResult
    /** Same operation identity with a different principal/destination/content binding. */
    data object RequestConflict : PromptDispatchStartResult
    data object Forbidden : PromptDispatchStartResult
    data object InvalidRequest : PromptDispatchStartResult
    data object Unsupported : PromptDispatchStartResult
    data object Unavailable : PromptDispatchStartResult
    data object OutcomeUnknown : PromptDispatchStartResult
}

sealed interface PromptDispatchSettlement {
    data object Recorded : PromptDispatchSettlement
    data object Rejected : PromptDispatchSettlement
    data object Unavailable : PromptDispatchSettlement
    data object OutcomeUnknown : PromptDispatchSettlement
}

/**
 * An authoritative durable journal MUST atomically (1) re-authorize full selection and grants,
 * (2) fence model/operation/payload identity, and (3) store SEND_STARTED.
 *
 * Neither separate read+write nor local process maps satisfy production requirements.
 * The operation may have started if the response is lost: return OutcomeUnknown.
 */
interface TrustedPromptSendStartJournal {
    fun tryStart(request: PromptOutputRequest, requestDigest: String): PromptDispatchStartResult
    fun settle(permit: PromptSendStartPermit, outcome: TrustedPromptSendResult): PromptDispatchSettlement
}

/**
 * Trusted model transport, not a caller-supplied callback. It MUST check permit identity
 * and deadline again at the point of actual network send-start. No automatic retries.
 */
fun interface TrustedLeaseBoundPromptSink {
    fun send(
        permit: PromptSendStartPermit,
        contexts: List<ContextSnapshot>,
    ): TrustedPromptSendResult
}

/**
 * No Journal or transport => Unsupported. Start is a durable logical send-start intent,
 * NOT evidence that HTTP bytes were emitted; Accepted means only transport acceptance.
 */
class TrustedPromptLeaseDispatcher(
    private val journal: TrustedPromptSendStartJournal? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun dispatch(request: PromptOutputRequest, sink: TrustedLeaseBoundPromptSink): PromptOutputResult {
        val authority = journal ?: return PromptOutputResult.Unsupported
        val digest = try { PromptDispatchBinding.digest(request) }
            catch (_: Exception) { return PromptOutputResult.InvalidRequest }
        val start = try { authority.tryStart(request, digest) }
            catch (_: Exception) { return PromptOutputResult.Unavailable }
        val begun = when (start) {
            is PromptDispatchStartResult.Started -> start
            PromptDispatchStartResult.AlreadyRecorded -> return PromptOutputResult.OutcomeUnknown
            PromptDispatchStartResult.RequestConflict,
            PromptDispatchStartResult.InvalidRequest -> return PromptOutputResult.InvalidRequest
            PromptDispatchStartResult.Forbidden -> return PromptOutputResult.Forbidden
            PromptDispatchStartResult.Unsupported -> return PromptOutputResult.Unsupported
            PromptDispatchStartResult.Unavailable -> return PromptOutputResult.Unavailable
            PromptDispatchStartResult.OutcomeUnknown -> return PromptOutputResult.OutcomeUnknown
        }
        val permit = begun.permit
        // Journal implementations are untrusted across the port boundary: validate binding.
        if (permit.tenantId != request.principal.tenantId ||
            permit.principalId != request.principal.id ||
            permit.destination != request.destination ||
            permit.requestDigest != digest ||
            begun.contexts != request.selection.contexts
        ) return PromptOutputResult.OutcomeUnknown
        if (permit.startedAtEpochMillis > clock.millis())
            return PromptOutputResult.OutcomeUnknown
        val outcome = if (clock.millis() >= permit.expiresAtEpochMillis) {
            // The trusted transport was never called; record this exact no-send outcome.
            TrustedPromptSendResult.DefinitelyNotSent
        } else try {
            sink.send(permit, begun.contexts.toList())
        } catch (_: Exception) {
            TrustedPromptSendResult.OutcomeUnknown
        }
        val settlement = try { authority.settle(permit, outcome) }
            catch (_: Exception) { PromptDispatchSettlement.OutcomeUnknown }
        if (settlement != PromptDispatchSettlement.Recorded)
            return PromptOutputResult.OutcomeUnknown
        return when (outcome) {
            TrustedPromptSendResult.Accepted -> PromptOutputResult.Accepted
            TrustedPromptSendResult.DefinitelyNotSent -> PromptOutputResult.NotSent
            TrustedPromptSendResult.OutcomeUnknown -> PromptOutputResult.OutcomeUnknown
        }
    }
}
