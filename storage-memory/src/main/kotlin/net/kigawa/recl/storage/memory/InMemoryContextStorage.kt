package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.StoredContext
import net.kigawa.recl.core.WriteResult

/** Ephemeral, JVM-only test adapter. NEVER use as a production failover. */
class InMemoryContextStorage : ContextStorage {
    private val lock = Any()
    private val data = mutableMapOf<Pair<String, String>, StoredContext>()

    override fun get(tenantId: String, id: String): ReadResult = synchronized(lock) {
        val stored = data[tenantId to id] ?: return@synchronized ReadResult.NotFound
        ReadResult.Found(stored.copy(value = snapshot(stored.value)))
    }

    override fun createIfAbsent(value: ContextAggregate): WriteResult = synchronized(lock) {
        val key = value.tenantId to value.id
        if (key in data) return@synchronized WriteResult.Conflict
        val stored = StoredContext(snapshot(value), 1)
        data[key] = stored
        WriteResult.Applied(stored)
    }

    override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult = synchronized(lock) {
        if (value.tenantId != tenantId || value.id != id) return@synchronized WriteResult.Conflict
        val key = tenantId to id
        val old = data[key] ?: return@synchronized WriteResult.Conflict
        if (old.storageRevision != expectedStorageRevision) return@synchronized WriteResult.Conflict
        val stored = StoredContext(snapshot(value), old.storageRevision + 1)
        data[key] = stored
        WriteResult.Applied(stored)
    }

    private fun snapshot(value: ContextAggregate): ContextAggregate = value.copy(
        scopes = value.scopes.toSet(),
        operationReceipts = value.operationReceipts.toList(),
    )
}
