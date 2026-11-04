package net.kigawa.recl.storage.fortis

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.StoredContext
import net.kigawa.recl.core.WriteResult

/**
 * [ContextStorage] over a [FortisKvPort]. One Context is one Fortis key; every write is a single
 * ConditionalBatch, so there is no read-then-put path.
 *
 * Request identity: writes carry their receipt in `operationReceipts`. The Fortis requestId is the
 * last receipt's requestId, scoped to the Context id, so no core contract change is needed. The
 * Fortis digest is the batch's canonical form, which includes the full new value.
 *
 * Fail-closed rules: invalid tenant/context/request identifiers and mis-scoped aggregates throw
 * [IllegalArgumentException] before anything reaches Fortis. Corrupt stored values and partial
 * enumerations surface as `Unavailable`, never as data or `Complete`.
 */
class FortisContextStorage(private val port: FortisKvPort) : ContextStorage {

    override fun get(tenantId: String, id: String): ReadResult {
        val key = FortisContextKeys.contextKey(tenantId, id)
        return when (val read = port.getLinearizable(key)) {
            FortisRead.Absent -> ReadResult.NotFound
            FortisRead.Unavailable -> ReadResult.Unavailable
            is FortisRead.Value -> {
                val value = decodeFor(tenantId, id, read.value) ?: return ReadResult.Unavailable
                ReadResult.Found(StoredContext(value, read.revision))
            }
        }
    }

    override fun createIfAbsent(value: ContextAggregate): WriteResult {
        val key = FortisContextKeys.contextKey(value.tenantId, value.id)
        val batch = batchFor(value, key, FortisCondition.Absent(key))
        return writeResult(batch, value)
    }

    override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult {
        val key = FortisContextKeys.contextKey(tenantId, id)
        require(value.tenantId == tenantId && value.id == id) { "aggregate identity does not match the target key" }
        // Revision 0 means "never written". Sending it would turn a CAS into a create, so refuse here.
        if (expectedStorageRevision < 1) return WriteResult.Conflict
        val batch = batchFor(value, key, FortisCondition.RevisionEquals(key, expectedStorageRevision))
        return writeResult(batch, value)
    }

    override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult {
        require(maxItems in 1..MAX_ENUMERATE_ITEMS) { "maxItems must be in 1..$MAX_ENUMERATE_ITEMS" }
        val prefix = FortisContextKeys.tenantPrefix(tenantId)
        val pageSize = minOf(MAX_PAGE_SIZE, maxItems + 1)
        val collected = mutableListOf<StoredContext>()
        val seenKeys = HashSet<String>()
        var pinnedRevision: Long? = null
        var cursor: ByteArray? = null
        while (true) {
            val page = when (val result = port.scanPrefix(prefix, pageSize, pinnedRevision, cursor)) {
                is FortisScanResult.Page -> result
                is FortisScanResult.Unsupported -> return EnumerationResult.Unsupported
                is FortisScanResult.Unavailable,
                is FortisScanResult.RevisionCompacted,
                is FortisScanResult.InvalidCursor -> return EnumerationResult.Unavailable
            }
            // Every page must be served at the same revision, or the set is not one snapshot.
            if (pinnedRevision == null) pinnedRevision = page.revision
            else if (page.revision != pinnedRevision) return EnumerationResult.Unavailable
            for (entry in page.entries) {
                if (!entry.key.startsWith(prefix) || !seenKeys.add(entry.key)) return EnumerationResult.Unavailable
                val contextId = entry.key.removePrefix(prefix)
                val value = decodeFor(tenantId, contextId, entry.value) ?: return EnumerationResult.Unavailable
                collected.add(StoredContext(value, entry.revision))
                if (collected.size > maxItems) return EnumerationResult.TooLarge
            }
            val next = page.nextCursor ?: return EnumerationResult.Complete(collected)
            // A repeated cursor would loop forever; treat it as a broken scan rather than hang.
            if (cursor != null && next.contentEquals(cursor)) return EnumerationResult.Unavailable
            cursor = next
        }
    }

    private fun batchFor(value: ContextAggregate, key: String, condition: FortisCondition): FortisConditionalBatch {
        val receipt = value.operationReceipts.lastOrNull()
            ?: throw IllegalArgumentException("a write must carry the receipt of its request")
        val requestId = FortisContextKeys.fortisRequestId(value.id, receipt.requestId)
        val bytes = ContextAggregateCodec.encode(value)
        return FortisConditionalBatch(
            principal = value.tenantId,
            requestId = requestId,
            conditions = listOf(condition),
            mutations = listOf(FortisMutation.Put(key, bytes)),
        )
    }

    private fun writeResult(batch: FortisConditionalBatch, value: ContextAggregate): WriteResult =
        when (val submitted = port.submit(batch)) {
            is FortisSubmitResult.Stored -> outcomeOf(submitted.receipt, value)
            // A different request already owns this requestId; this write's own result is unknown.
            FortisSubmitResult.DigestMismatch -> WriteResult.OutcomeUnknown
            FortisSubmitResult.OutcomeUnknown -> reconcile(batch, value)
            is FortisSubmitResult.Unavailable -> WriteResult.Unavailable
        }

    /** Resolve an unconfirmed submit only through the receipt. Absence of a receipt proves nothing. */
    private fun reconcile(batch: FortisConditionalBatch, value: ContextAggregate): WriteResult =
        when (val looked = port.lookup(batch)) {
            is FortisLookupResult.Found -> outcomeOf(looked.receipt, value)
            FortisLookupResult.DigestMismatch,
            FortisLookupResult.ReceiptExpiredOrUnknown,
            is FortisLookupResult.Unavailable -> WriteResult.OutcomeUnknown
        }

    private fun outcomeOf(receipt: FortisReceipt, value: ContextAggregate): WriteResult =
        when (receipt.outcome) {
            FortisOutcome.Applied -> WriteResult.Applied(StoredContext(value, receipt.logIndex))
            is FortisOutcome.Rejected -> WriteResult.Conflict
        }

    /** Decodes and checks that the stored bytes really belong to the key they were read from. */
    private fun decodeFor(tenantId: String, contextId: String, bytes: ByteArray): ContextAggregate? = try {
        val decoded = ContextAggregateCodec.decode(bytes)
        if (decoded.tenantId == tenantId && decoded.id == contextId) decoded else null
    } catch (_: CorruptContextRecord) {
        null
    }

    private companion object {
        /** Fortis ScanRequest.MAX_PAGE_SIZE. */
        const val MAX_PAGE_SIZE = 1000
        const val MAX_ENUMERATE_ITEMS = 10_000
    }
}
