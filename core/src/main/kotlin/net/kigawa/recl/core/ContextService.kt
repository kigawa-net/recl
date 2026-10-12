package net.kigawa.recl.core

sealed interface MutationResult {
    data class Applied(val contentRevision: Long, val replayed: Boolean = false) : MutationResult
    data class Conflict(val actualRevision: Long?) : MutationResult
    data object Forbidden : MutationResult
    data object InvalidRequest : MutationResult
    data object NotFound : MutationResult
    data object RequestIdReused : MutationResult
    data object OutcomeUnknown : MutationResult
    data object Unavailable : MutationResult
}

/** Handles one-key operations only; supersede across two keys is deliberately unsupported. */
class ContextService(private val storage: ContextStorage, private val maxRetries: Int = 12) {
    init { require(maxRetries > 0) }

    fun create(principal: Principal, newContext: ContextAggregate, requestId: String, digest: String): MutationResult {
        checkRequest(requestId, digest)
        if (!mayEdit(principal, newContext)) return MutationResult.Forbidden
        if (newContext.operationReceipts.isNotEmpty() || newContext.contentRevision != 1L || newContext.deleted) {
            return MutationResult.Conflict(null)
        }
        val next = newContext.copy(operationReceipts = listOf(OperationReceipt(requestId, digest, 1)))
        return when (storage.createIfAbsent(next)) {
            is WriteResult.Applied -> MutationResult.Applied(1)
            WriteResult.Conflict -> existingCreate(principal, newContext.id, requestId, digest)
            WriteResult.OutcomeUnknown -> reconcile(principal, newContext.id, requestId, digest)
            WriteResult.Unavailable -> MutationResult.Unavailable
        }
    }

    fun update(principal: Principal, id: String, expectedContentRevision: Long, requestId: String, digest: String, patch: ContextPatch): MutationResult {
        checkRequest(requestId, digest)
        if (expectedContentRevision < 1) return MutationResult.Conflict(null)
        repeat(maxRetries) {
            val current = when (val r = storage.get(principal.tenantId, id)) {
                is ReadResult.Found -> r.stored
                ReadResult.NotFound -> return MutationResult.NotFound
                ReadResult.Unavailable -> return MutationResult.Unavailable
            }
            if (!mayEdit(principal, current.value)) return MutationResult.Forbidden
            val receipt = current.value.operationReceipts.firstOrNull { it.requestId == requestId }
            if (receipt != null) return if (receipt.payloadDigest == digest) MutationResult.Applied(receipt.resultingContentRevision, replayed = true) else MutationResult.RequestIdReused
            if (current.value.deleted) return MutationResult.NotFound
            if (current.value.contentRevision != expectedContentRevision) return MutationResult.Conflict(current.value.contentRevision)
            val nextRevision = current.value.contentRevision + 1
            val next = patch.applyTo(current.value).copy(
                contentRevision = nextRevision,
                indexAction = IndexAction.UPSERT,
                operationReceipts = appendReceipt(current.value.operationReceipts, requestId, digest, nextRevision),
            )
            when (storage.compareAndSwap(principal.tenantId, id, current.storageRevision, next)) {
                is WriteResult.Applied -> return MutationResult.Applied(nextRevision)
                WriteResult.Conflict -> Unit // re-read and reauthorize; ACK-only conflicts are retryable
                WriteResult.OutcomeUnknown -> return reconcile(principal, id, requestId, digest)
                WriteResult.Unavailable -> return MutationResult.Unavailable
            }
        }
        return MutationResult.OutcomeUnknown
    }

    fun delete(principal: Principal, id: String, expectedContentRevision: Long, requestId: String, digest: String): MutationResult {
        checkRequest(requestId, digest)
        repeat(maxRetries) {
            val current = when (val r = storage.get(principal.tenantId, id)) {
                is ReadResult.Found -> r.stored
                ReadResult.NotFound -> return MutationResult.NotFound
                ReadResult.Unavailable -> return MutationResult.Unavailable
            }
            if (!mayEdit(principal, current.value)) return MutationResult.Forbidden
            val receipt = current.value.operationReceipts.firstOrNull { it.requestId == requestId }
            if (receipt != null) return if (receipt.payloadDigest == digest) MutationResult.Applied(receipt.resultingContentRevision, replayed = true) else MutationResult.RequestIdReused
            if (current.value.deleted) return MutationResult.NotFound
            if (current.value.contentRevision != expectedContentRevision) return MutationResult.Conflict(current.value.contentRevision)
            val revision = current.value.contentRevision + 1
            val next = current.value.copy(
                contentRevision = revision,
                deleted = true,
                indexAction = IndexAction.DELETE,
                operationReceipts = appendReceipt(current.value.operationReceipts, requestId, digest, revision),
            )
            when (storage.compareAndSwap(principal.tenantId, id, current.storageRevision, next)) {
                is WriteResult.Applied -> return MutationResult.Applied(revision)
                WriteResult.Conflict -> Unit
                WriteResult.OutcomeUnknown -> return reconcile(principal, id, requestId, digest)
                WriteResult.Unavailable -> return MutationResult.Unavailable
            }
        }
        return MutationResult.OutcomeUnknown
    }

    /** Projection ACK is bookkeeping only: the logical context version must not advance. */
    fun acknowledgeIndex(tenantId: String, id: String, targetContentRevision: Long): Boolean {
        repeat(maxRetries) {
            val current = (storage.get(tenantId, id) as? ReadResult.Found)?.stored ?: return false
            if (targetContentRevision > current.value.contentRevision || targetContentRevision < 1) return false
            if (current.value.indexedContentRevision >= targetContentRevision) return true
            val next = current.value.copy(indexedContentRevision = targetContentRevision)
            when (storage.compareAndSwap(tenantId, id, current.storageRevision, next)) {
                is WriteResult.Applied -> return true
                WriteResult.Conflict -> Unit
                else -> return false
            }
        }
        return false
    }

    private fun reconcile(principal: Principal, id: String, requestId: String, digest: String): MutationResult {
        val current = when (val r = storage.get(principal.tenantId, id)) {
            is ReadResult.Found -> r.stored.value
            ReadResult.NotFound -> return MutationResult.OutcomeUnknown
            ReadResult.Unavailable -> return MutationResult.OutcomeUnknown
        }
        if (!mayEdit(principal, current)) return MutationResult.Forbidden
        val receipt = current.operationReceipts.firstOrNull { it.requestId == requestId }
        if (receipt != null) return if (receipt.payloadDigest == digest) MutationResult.Applied(receipt.resultingContentRevision, replayed = true) else MutationResult.RequestIdReused
        // An absent receipt cannot prove the write did not happen: a later update may have evicted it.
        return MutationResult.OutcomeUnknown
    }

    private fun existingCreate(principal: Principal, id: String, requestId: String, digest: String): MutationResult {
        val current = when (val r = storage.get(principal.tenantId, id)) {
            is ReadResult.Found -> r.stored.value
            ReadResult.NotFound, ReadResult.Unavailable -> return MutationResult.OutcomeUnknown
        }
        if (!mayEdit(principal, current)) return MutationResult.Forbidden
        val receipt = current.operationReceipts.firstOrNull { it.requestId == requestId }
        if (receipt != null) return if (receipt.payloadDigest == digest) MutationResult.Applied(receipt.resultingContentRevision, replayed = true) else MutationResult.RequestIdReused
        return MutationResult.Conflict(current.contentRevision)
    }

    private fun mayEdit(p: Principal, c: ContextAggregate): Boolean = p.tenantId == c.tenantId && p.id == c.ownerId

    private fun checkRequest(requestId: String, digest: String) {
        require(requestId.isNotBlank() && digest.isNotBlank())
    }

    private fun appendReceipt(previous: List<OperationReceipt>, requestId: String, digest: String, rev: Long): List<OperationReceipt> =
        (previous + OperationReceipt(requestId, digest, rev)).takeLast(ContextAggregate.MAX_RECEIPTS)
}
