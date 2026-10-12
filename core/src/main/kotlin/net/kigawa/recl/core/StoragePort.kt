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

/** The external Fortis adapter must prove these primitives are atomic and linearizable. */
interface ContextStorage {
    fun get(tenantId: String, id: String): ReadResult
    fun createIfAbsent(value: ContextAggregate): WriteResult
    fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult
}
