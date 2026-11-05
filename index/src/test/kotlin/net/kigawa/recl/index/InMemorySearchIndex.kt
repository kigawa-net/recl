package net.kigawa.recl.index

/**
 * Test double for [SearchIndexPort]. It models the failure modes the reconciler must survive:
 * reads/writes becoming unavailable, an unsupported port, and a write that is visible before the
 * caller can acknowledge it (or not visible at all).
 */
class InMemorySearchIndex : SearchIndexPort {
    private val lock = Any()
    private val points = mutableMapOf<Pair<String, String>, IndexPoint>()

    var readsUnavailable = false
    var writesUnavailable = false
    var unsupported = false

    var upserts = 0
        private set
    var deletes = 0
        private set

    /** Runs after a successful upsert, e.g. to simulate a concurrent writer. */
    var afterUpsert: ((IndexPoint) -> Unit)? = null
    var afterDelete: ((String, String) -> Unit)? = null

    /** When false an upsert reports success but the point never becomes visible. */
    var upsertIsVisible = true

    /** When false a delete reports success but the point stays visible. */
    var deleteIsVisible = true

    fun point(tenantId: String, contextId: String): IndexPoint? = synchronized(lock) {
        points[tenantId to contextId]
    }

    fun put(point: IndexPoint) = synchronized(lock) {
        points[point.tenantId to point.contextId] = point
    }

    fun remove(tenantId: String, contextId: String) = synchronized(lock) {
        points.remove(tenantId to contextId)
    }

    fun size(): Int = synchronized(lock) { points.size }

    override fun inspect(tenantId: String, contextId: String): IndexInspection {
        if (unsupported) return IndexInspection.Unsupported
        if (readsUnavailable) return IndexInspection.Unavailable
        val point = synchronized(lock) { points[tenantId to contextId] }
            ?: return IndexInspection.NotFound
        return IndexInspection.Found(point)
    }

    override fun upsert(point: IndexPoint): IndexWriteResult {
        if (unsupported) return IndexWriteResult.Unsupported
        if (writesUnavailable) return IndexWriteResult.Unavailable
        if (upsertIsVisible) put(point)
        synchronized(lock) { upserts++ }
        afterUpsert?.invoke(point)
        return IndexWriteResult.Applied
    }

    override fun delete(tenantId: String, contextId: String): IndexWriteResult {
        if (unsupported) return IndexWriteResult.Unsupported
        if (writesUnavailable) return IndexWriteResult.Unavailable
        if (deleteIsVisible) remove(tenantId, contextId)
        synchronized(lock) { deletes++ }
        afterDelete?.invoke(tenantId, contextId)
        return IndexWriteResult.Applied
    }
}
