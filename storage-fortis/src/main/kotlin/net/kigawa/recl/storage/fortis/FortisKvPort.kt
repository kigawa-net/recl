package net.kigawa.recl.storage.fortis

/**
 * recl-side view of the Fortis single-group KV surface. It mirrors the fortis contracts
 * (`docs/conditional-batch-contract.md`, `docs/revision-scan-contract.md` on fortis
 * origin/feature/raft) but is NOT a binding to them: no fortis code is linked in yet.
 * See docs/fortis-adapter.md.
 *
 * Revisions are Fortis per-key revisions, i.e. the Raft log index of the last write to that key.
 * Implementations must never synthesize a conditional write from a read followed by a plain put.
 */
interface FortisKvPort {
    /** Linearizable single-key read. */
    fun getLinearizable(key: String): FortisRead

    /** Submit a conditional batch. Must not re-execute a request whose receipt already exists. */
    fun submit(batch: FortisConditionalBatch): FortisSubmitResult

    /** Look up the receipt for (batch.principal, batch.requestId). Never executes the batch. */
    fun lookup(batch: FortisConditionalBatch): FortisLookupResult

    /**
     * One page of a prefix scan. `readRevision == null` pins the latest applied revision on the
     * first page; later pages pass the pinned revision together with [cursor].
     */
    fun scanPrefix(prefix: String, pageSize: Int, readRevision: Long?, cursor: ByteArray?): FortisScanResult
}

sealed interface FortisRead {
    class Value(val value: ByteArray, val revision: Long) : FortisRead
    data object Absent : FortisRead
    data object Unavailable : FortisRead
}

sealed interface FortisCondition {
    val key: String

    /** The key has never been written (a deleted key also counts as absent). */
    data class Absent(override val key: String) : FortisCondition

    /** The key's last-write revision equals [revision]. Revision 0 means never written. */
    data class RevisionEquals(override val key: String, val revision: Long) : FortisCondition
}

sealed interface FortisMutation {
    val key: String

    class Put(override val key: String, val value: ByteArray) : FortisMutation
}

/**
 * Mirrors fortis `ConditionalBatchRequest` limits: keys 1..1024 bytes, values <= 1 MiB,
 * at most 64 conditions and 64 mutations, keys unique within each list, at least one mutation.
 */
class FortisConditionalBatch(
    val principal: String,
    val requestId: String,
    val conditions: List<FortisCondition>,
    val mutations: List<FortisMutation>,
) {
    init {
        require(principal.isNotEmpty() && requestId.isNotEmpty())
        require(mutations.isNotEmpty()) { "a conditional batch needs at least one mutation" }
        require(conditions.size <= MAX_LIST && mutations.size <= MAX_LIST) { "too many conditions or mutations" }
        require(conditions.map { it.key }.distinct().size == conditions.size) { "duplicate condition key" }
        require(mutations.map { it.key }.distinct().size == mutations.size) { "duplicate mutation key" }
        for (condition in conditions) require(condition.key.toByteArray(Charsets.UTF_8).size in 1..MAX_KEY_BYTES)
        for (mutation in mutations) when (mutation) {
            is FortisMutation.Put -> {
                require(mutation.key.toByteArray(Charsets.UTF_8).size in 1..MAX_KEY_BYTES)
                require(mutation.value.size <= MAX_VALUE_BYTES) { "value exceeds $MAX_VALUE_BYTES bytes" }
            }
        }
    }

    /**
     * Deterministic canonical form of conditions and mutations. Used as the exact-match request
     * digest by the in-process fake; a real binding must use the fortis codec for its own digest.
     */
    fun canonicalDigest(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(out)
        data.writeInt(conditions.size)
        for (c in conditions) when (c) {
            is FortisCondition.Absent -> { data.writeByte(0); data.writeUTF(c.key) }
            is FortisCondition.RevisionEquals -> { data.writeByte(1); data.writeUTF(c.key); data.writeLong(c.revision) }
        }
        data.writeInt(mutations.size)
        for (m in mutations) when (m) {
            is FortisMutation.Put -> { data.writeByte(2); data.writeUTF(m.key); data.writeInt(m.value.size); data.write(m.value) }
        }
        data.flush()
        return out.toByteArray()
    }

    companion object {
        const val MAX_KEY_BYTES = 1024
        const val MAX_VALUE_BYTES = 1 shl 20
        const val MAX_LIST = 64
    }
}

sealed interface FortisOutcome {
    data object Applied : FortisOutcome
    data class Rejected(val failedConditionIndex: Int, val observedRevision: Long) : FortisOutcome
}

/** A stored result. [logIndex] is the Raft log index at which the batch was applied or rejected. */
class FortisReceipt(val logIndex: Long, val outcome: FortisOutcome)

sealed interface FortisSubmitResult {
    data class Stored(val receipt: FortisReceipt) : FortisSubmitResult
    /** Same principal and requestId were recorded with a different canonical request. */
    data object DigestMismatch : FortisSubmitResult
    /** The result could not be confirmed (timeout, leader change, quorum loss). Not a failure. */
    data object OutcomeUnknown : FortisSubmitResult
    /** Known not to have been proposed. */
    data class Unavailable(val reason: String) : FortisSubmitResult
}

sealed interface FortisLookupResult {
    data class Found(val receipt: FortisReceipt) : FortisLookupResult
    data object DigestMismatch : FortisLookupResult
    /** No receipt. This is NOT proof that the batch never ran. */
    data object ReceiptExpiredOrUnknown : FortisLookupResult
    data class Unavailable(val reason: String) : FortisLookupResult
}

class FortisScanEntry(val key: String, val value: ByteArray, val revision: Long)

sealed interface FortisScanResult {
    /** [nextCursor] is null only when the enumeration of the prefix is complete. */
    class Page(val entries: List<FortisScanEntry>, val revision: Long, val nextCursor: ByteArray?) : FortisScanResult
    data class RevisionCompacted(val floor: Long) : FortisScanResult
    data class InvalidCursor(val reason: String) : FortisScanResult
    data class Unavailable(val reason: String) : FortisScanResult
    data class Unsupported(val reason: String) : FortisScanResult
}
