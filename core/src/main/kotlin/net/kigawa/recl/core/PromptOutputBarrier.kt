package net.kigawa.recl.core

/**
 * Internal destination binding. A model dispatch must not be redirected after admission.
 * These identifiers are non-secret; provider credentials belong only in a trusted transport.
 */
data class PromptDestination(
    val provider: String,
    val model: String,
    val operationId: String,
) {
    init {
        val identifier = Regex("[A-Za-z0-9._:/-]{1,128}")
        require(identifier.matches(provider) && identifier.matches(model) &&
            identifier.matches(operationId))
    }
}

data class PromptOutputRequest(
    /** Established by trusted authentication, not by a caller-provided tenant header. */
    val principal: Principal,
    val destination: PromptDestination,
    val query: PinnedQuery,
    val selection: PinnedContextResult.Found,
)

/**
 * Trusted transport outcome. Accepted only means that the destination accepted the send;
 * it does NOT guarantee the external model completed its execution.
 */
sealed interface TrustedPromptSendResult {
    data object Accepted : TrustedPromptSendResult
    /** This result is valid only when the trusted transport proves that no bytes were sent. */
    data object DefinitelyNotSent : TrustedPromptSendResult
    /** The send may have reached the destination. No automatic retry is safe. */
    data object OutcomeUnknown : TrustedPromptSendResult
}

/** The destination, operation ID and the authorized exact snapshots are bound to the same call. */
fun interface TrustedPromptSink {
    fun send(destination: PromptDestination, contexts: List<ContextSnapshot>): TrustedPromptSendResult
}

sealed interface PromptOutputResult {
    data object Accepted : PromptOutputResult
    data object NotSent : PromptOutputResult
    data object OutcomeUnknown : PromptOutputResult
    data object Forbidden : PromptOutputResult
    data object InvalidRequest : PromptOutputResult
    data object Unsupported : PromptOutputResult
    data object Unavailable : PromptOutputResult
}

/**
 * Production implementations MUST serialize authorization with grant revocation up to a
 * documented send-start linearization point, or provide an explicit bounded lease.
 *
 * A getGrant() followed by sink.send() without a coordinated revocation barrier MUST return
 * Unsupported, even if both reads are linearizable. Fortis batch cannot atomically cover
 * an external HTTP/model side effect.
 */
fun interface TrustedPromptOutputBarrier {
    fun authorizeAndSend(request: PromptOutputRequest, sink: TrustedPromptSink): PromptOutputResult
}

/**
 * Server-only dispatch entry point. A missing verified barrier never falls back to an
 * ordinary memory-to-prompt copy or to the earlier PinnedContextReader.list() result.
 */
class TrustedPromptOutputService(private val barrier: TrustedPromptOutputBarrier? = null) {
    fun dispatch(request: PromptOutputRequest, sink: TrustedPromptSink): PromptOutputResult =
        barrier?.authorizeAndSend(request, sink) ?: PromptOutputResult.Unsupported
}
