package net.kigawa.recl.core

/**
 * Server-authenticated lookup only; it is NOT a public GET-by-operation-ID endpoint.
 * A memory-write mutation and a model send are different journal operation kinds.
 */
data class PromptDispatchReceiptQuery(
    val principal: Principal,
    val destination: PromptDestination,
    val requestDigest: String,
) {
    init {
        require(requestDigest.matches(Regex("sha256:[0-9a-f]{64}")))
    }
}

/**
 * Distinguishes the Fortis SEND_STARTED intent from a provider-accepted transport,
 * and from a separately proven no-effect operation. None proves model completion.
 */
enum class PromptDispatchReceiptState {
    STARTED_PENDING,
    TRANSPORT_ACCEPTED,
    DEFINITELY_NOT_SENT,
    OUTCOME_UNKNOWN,
}

sealed interface PromptDispatchReceiptRead {
    data class Found(
        val state: PromptDispatchReceiptState,
        val requestDigest: String,
        val sendStartEpochMillis: Long,
        val settledAtEpochMillis: Long? = null,
    ) : PromptDispatchReceiptRead

    /** Never evidence that a model request was not sent: records may be lost or compacted. */
    data object NotFound : PromptDispatchReceiptRead
    data object BindingConflict : PromptDispatchReceiptRead
    data object Unsupported : PromptDispatchReceiptRead
    data object Unavailable : PromptDispatchReceiptRead
    data object OutcomeUnknown : PromptDispatchReceiptRead
}

/**
 * Must provide a durable, authoritative, authenticated lookup across leader changes.
 * A cache-only or unverified replica read is not a valid production implementation.
 */
fun interface TrustedPromptDispatchReceiptReader {
    fun lookup(query: PromptDispatchReceiptQuery): PromptDispatchReceiptRead
}

/**
 * Read-only reconciliation never retries a model request. A DEFINITELY_NOT_SENT
 * record still needs independently trusted transport evidence before a human/worker
 * starts a NEW authorized operation; the old OperationId is permanently reserved.
 */
sealed interface PromptDispatchReconciliation {
    data class Observed(val state: PromptDispatchReceiptState) : PromptDispatchReconciliation
    data object HeldNoReplay : PromptDispatchReconciliation
    data object BindingConflict : PromptDispatchReconciliation
    data object Unsupported : PromptDispatchReconciliation
    data object Unavailable : PromptDispatchReconciliation
}

class TrustedPromptDispatchReconciler(
    private val reader: TrustedPromptDispatchReceiptReader? = null,
) {
    fun reconcile(query: PromptDispatchReceiptQuery): PromptDispatchReconciliation {
        val authority = reader ?: return PromptDispatchReconciliation.Unsupported
        val receipt = try {
            authority.lookup(query)
        } catch (_: Exception) {
            return PromptDispatchReconciliation.Unavailable
        }
        return when (receipt) {
            is PromptDispatchReceiptRead.Found -> {
                // Do not trust inconsistent receipts, and never leak their metadata.
                if (receipt.requestDigest != query.requestDigest ||
                    receipt.sendStartEpochMillis < 0 ||
                    (receipt.settledAtEpochMillis != null &&
                        receipt.settledAtEpochMillis < receipt.sendStartEpochMillis) ||
                    (receipt.state == PromptDispatchReceiptState.STARTED_PENDING &&
                        receipt.settledAtEpochMillis != null) ||
                    (receipt.state != PromptDispatchReceiptState.STARTED_PENDING &&
                        receipt.settledAtEpochMillis == null)
                ) PromptDispatchReconciliation.HeldNoReplay
                else PromptDispatchReconciliation.Observed(receipt.state)
            }
            PromptDispatchReceiptRead.NotFound,
            PromptDispatchReceiptRead.OutcomeUnknown -> PromptDispatchReconciliation.HeldNoReplay
            PromptDispatchReceiptRead.BindingConflict -> PromptDispatchReconciliation.BindingConflict
            PromptDispatchReceiptRead.Unsupported -> PromptDispatchReconciliation.Unsupported
            PromptDispatchReceiptRead.Unavailable -> PromptDispatchReconciliation.Unavailable
        }
    }
}
