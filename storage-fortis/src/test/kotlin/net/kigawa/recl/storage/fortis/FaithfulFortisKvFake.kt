package net.kigawa.recl.storage.fortis

/**
 * In-process model of the Fortis single-group contracts, used only by tests.
 *
 * Modelled faithfully: revisions are the Raft log index of the last write to a key; every proposed
 * batch consumes a log index and leaves a receipt, including REJECTED ones; a receipt is matched on
 * (principal, requestId) with exact canonical-digest comparison and never re-executed; scans pin a
 * revision and read versions at it; a page cap mimics the page-size limit.
 *
 * Not modelled: Raft, durability, leader changes. Those are expressed through injected faults:
 * [SubmitFault.TIMEOUT_AFTER_APPLY] is the "applied but the caller cannot know" case.
 */
class FaithfulFortisKvFake(private val pageCap: Int = 1000) : FortisKvPort {
    enum class SubmitFault { UNAVAILABLE, TIMEOUT_BEFORE_APPLY, TIMEOUT_AFTER_APPLY }

    private val lock = Any()
    private var logIndex = 0L
    private val versions = HashMap<String, MutableList<Pair<Long, ByteArray>>>()
    private val receipts = HashMap<Pair<String, String>, Pair<ByteArray, FortisReceipt>>()

    val submitFaults = ArrayDeque<SubmitFault>()
    var lookupUnavailable = false
    var getUnavailable = false
    var scanUnsupported = false
    /** Number of successful pages before scans start failing; null means never. */
    var scanFailAfterPages: Int? = null
    /** Hook run after each page is served, e.g. to write between pages. */
    var afterPage: ((servedPages: Int) -> Unit)? = null

    var submitCalls = 0
        private set
    var scanCalls = 0
        private set
    private var pagesServed = 0

    /** Writes raw bytes at [key] as if by an out-of-band writer. Used to plant corrupt records. */
    fun putRawForTest(key: String, bytes: ByteArray): Unit = synchronized(lock) {
        logIndex++
        versions.getOrPut(key) { mutableListOf() }.add(logIndex to bytes.copyOf())
    }

    override fun getLinearizable(key: String): FortisRead = synchronized(lock) {
        if (getUnavailable) return FortisRead.Unavailable
        val latest = versions[key]?.lastOrNull() ?: return FortisRead.Absent
        FortisRead.Value(latest.second.copyOf(), latest.first)
    }

    override fun submit(batch: FortisConditionalBatch): FortisSubmitResult = synchronized(lock) {
        submitCalls++
        val fault = submitFaults.removeFirstOrNull()
        if (fault == SubmitFault.UNAVAILABLE) return FortisSubmitResult.Unavailable("injected: not proposed")
        val digest = batch.canonicalDigest()
        val existing = receipts[batch.principal to batch.requestId]
        if (existing != null) {
            if (fault != null) return FortisSubmitResult.OutcomeUnknown
            return if (existing.first.contentEquals(digest)) FortisSubmitResult.Stored(existing.second)
            else FortisSubmitResult.DigestMismatch
        }
        if (fault == SubmitFault.TIMEOUT_BEFORE_APPLY) return FortisSubmitResult.OutcomeUnknown

        val index = ++logIndex
        val failed = batch.conditions.withIndex().firstOrNull { (_, c) -> !holds(c) }
        val receipt = if (failed == null) {
            for (m in batch.mutations) when (m) {
                is FortisMutation.Put -> versions.getOrPut(m.key) { mutableListOf() }.add(index to m.value.copyOf())
            }
            FortisReceipt(index, FortisOutcome.Applied)
        } else {
            FortisReceipt(index, FortisOutcome.Rejected(failed.index, revisionOf(failed.value.key)))
        }
        receipts[batch.principal to batch.requestId] = digest to receipt
        return if (fault == SubmitFault.TIMEOUT_AFTER_APPLY) FortisSubmitResult.OutcomeUnknown
        else FortisSubmitResult.Stored(receipt)
    }

    override fun lookup(batch: FortisConditionalBatch): FortisLookupResult = synchronized(lock) {
        if (lookupUnavailable) return FortisLookupResult.Unavailable("injected")
        val existing = receipts[batch.principal to batch.requestId] ?: return FortisLookupResult.ReceiptExpiredOrUnknown
        return if (existing.first.contentEquals(batch.canonicalDigest())) FortisLookupResult.Found(existing.second)
        else FortisLookupResult.DigestMismatch
    }

    override fun scanPrefix(prefix: String, pageSize: Int, readRevision: Long?, cursor: ByteArray?): FortisScanResult =
        synchronized(lock) {
            scanCalls++
            require(pageSize in 1..1000)
            if (scanUnsupported) return FortisScanResult.Unsupported("injected")
            val failAfter = scanFailAfterPages
            if (failAfter != null && pagesServed >= failAfter) return FortisScanResult.Unavailable("injected")

            val revision: Long
            var afterKey: String? = null
            if (cursor == null) {
                revision = readRevision ?: logIndex
            } else {
                val parts = String(cursor, Charsets.UTF_8).split("|")
                if (parts.size != 3 || !parts[0].startsWith("rev=") || parts[1] != "prefix=$prefix" ||
                    !parts[2].startsWith("after=")) return FortisScanResult.InvalidCursor("malformed")
                revision = parts[0].removePrefix("rev=").toLong()
                afterKey = parts[2].removePrefix("after=")
                if (readRevision != null && readRevision != revision) return FortisScanResult.InvalidCursor("revision mismatch")
            }
            if (revision > logIndex) return FortisScanResult.Unavailable("revision $revision not applied")

            val visible = versions.keys.asSequence()
                .filter { it.startsWith(prefix) && (afterKey == null || it > afterKey) }
                .sorted()
                .mapNotNull { key -> versions[key]?.lastOrNull { it.first <= revision }?.let { key to it } }
                .take(minOf(pageSize, pageCap) + 1)
                .toList()
            val limit = minOf(pageSize, pageCap)
            val pageItems = visible.take(limit)
            val entries = pageItems.map { (key, v) -> FortisScanEntry(key, v.second.copyOf(), v.first) }
            val next = if (visible.size > limit) {
                "rev=$revision|prefix=$prefix|after=${pageItems.last().first}".toByteArray(Charsets.UTF_8)
            } else null

            pagesServed++
            afterPage?.invoke(pagesServed)
            FortisScanResult.Page(entries, revision, next)
        }

    private fun holds(condition: FortisCondition): Boolean = when (condition) {
        is FortisCondition.Absent -> versions[condition.key].isNullOrEmpty()
        is FortisCondition.RevisionEquals -> revisionOf(condition.key) == condition.revision
    }

    private fun revisionOf(key: String): Long = versions[key]?.lastOrNull()?.first ?: 0L
}
