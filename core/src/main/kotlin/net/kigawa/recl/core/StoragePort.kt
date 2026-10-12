package net.kigawa.recl.core

/** Physical generation is an opaque CAS token; it is not contentRevision. */
data class StoredContext(val value: ContextAggregate, val storageRevision: Long)

sealed interface ReadResult {
    data class Found(val stored: StoredContext) : ReadResult
    data object NotFound : ReadResult
    data object Unavailable : ReadResult
}

sealed interface WriteResult {
    data class Applied(val stored: StoredContext) : WriteResult
    data object Conflict : WriteResult
    data object OutcomeUnknown : WriteResult
    data object Unavailable : WriteResult
}

/** A complete, authoritative snapshot of tenant keys, or an explicit lack of capability.
 * Partial pages must never be represented as Complete. Fortis must offer a provable
 * consistent scan or an atomic index before implementing this interface.
 */
sealed interface EnumerationResult {
    data class Complete(val contexts: List<StoredContext>) : EnumerationResult
    data object Unsupported : EnumerationResult
    data object Unavailable : EnumerationResult
    data object TooLarge : EnumerationResult
}

/** The external Fortis adapter must prove these primitives are atomic and linearizable. */
interface ContextStorage {
    fun get(tenantId: String, id: String): ReadResult
    fun createIfAbsent(value: ContextAggregate): WriteResult
    fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult

    /** Returns only an exhaustive snapshot or a failure. This is not a Qdrant lookup. */
    fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult = EnumerationResult.Unsupported
}
