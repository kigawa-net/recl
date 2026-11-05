package net.kigawa.recl.index

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.IndexAction
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.WriteResult
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class IndexReconcilerTest {
    private val tenant = "tenant-a"
    private val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b"
    private val secondId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6c"
    private val missingId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4fffff"

    private val memory = InMemoryContextStorage()
    private val index = InMemorySearchIndex()
    private val generations = AtomicInteger(0)

    private fun reconciler(
        storage: ContextStorage = memory,
        attempts: Int = 3,
        maxEnumeratedObjects: Int = 10_000,
        generation: IndexGenerationSource = IndexGenerationSource { "gen-${generations.incrementAndGet()}" },
        embed: EmbeddingProvider = EmbeddingProvider { text -> listOf(text.length.toFloat(), 1f) },
    ) = IndexReconciler(storage, index, embed, generation, maxEnumeratedObjects, attempts)

    private fun create(
        id: String,
        storage: ContextStorage = memory,
        deleted: Boolean = false,
        content: String = "body",
    ): ContextAggregate {
        val value = ContextAggregate(
            tenantId = tenant,
            id = id,
            ownerId = "owner-a",
            kind = ContextKind.ARCHITECTURE,
            status = ContextStatus.CONFIRMED,
            title = "title",
            summary = "summary",
            content = content,
            contentRevision = 1,
            indexAction = if (deleted) IndexAction.DELETE else IndexAction.UPSERT,
            deleted = deleted,
        )
        val applied = storage.createIfAbsent(value)
        check(applied is WriteResult.Applied) { "test setup failed: $applied" }
        return value
    }

    /** Bumps contentRevision without acknowledging the index, like a normal write does. */
    private fun bump(id: String, storage: ContextStorage = memory, content: String = "body-v2"): ContextAggregate {
        val current = (storage.get(tenant, id) as ReadResult.Found).stored
        val next = current.value.copy(contentRevision = current.value.contentRevision + 1, content = content)
        check(storage.compareAndSwap(tenant, id, current.storageRevision, next) is WriteResult.Applied) {
            "test setup failed: revision bump"
        }
        return next
    }

    private fun tombstone(id: String, storage: ContextStorage = memory): ContextAggregate {
        val current = (storage.get(tenant, id) as ReadResult.Found).stored
        val next = current.value.copy(
            contentRevision = current.value.contentRevision + 1,
            deleted = true,
            indexAction = IndexAction.DELETE,
        )
        check(storage.compareAndSwap(tenant, id, current.storageRevision, next) is WriteResult.Applied) {
            "test setup failed: tombstone"
        }
        return next
    }

    private fun indexedRevision(id: String, storage: ContextStorage = memory): Long =
        (storage.get(tenant, id) as ReadResult.Found).stored.value.indexedContentRevision

    private fun contentRevision(id: String, storage: ContextStorage = memory): Long =
        (storage.get(tenant, id) as ReadResult.Found).stored.value.contentRevision

    // ---- happy path -------------------------------------------------------------------------

    @Test
    fun `projects a new context and acknowledges only after the point is visible`() {
        create(contextId)

        val result = reconciler().reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UPSERTED, result.status)
        assertEquals(1L, result.targetContentRevision)
        val point = index.point(tenant, contextId)!!
        assertEquals(1L, point.contentRevision)
        assertEquals(ContextStatus.CONFIRMED, point.status)
        assertEquals("title", point.title)
        assertEquals("summary", point.summary)
        assertEquals("body", point.content)
        assertEquals("gen-1", point.indexGeneration)
        assertTrue(point.embedding.isNotEmpty())
        // The ACK advances the projection checkpoint, never the logical content revision.
        assertEquals(1L, indexedRevision(contextId))
        assertEquals(1L, contentRevision(contextId))
    }

    @Test
    fun `is idempotent and does not rewrite a converged context`() {
        create(contextId)
        val reconciler = reconciler()
        reconciler.reconcileContext(tenant, contextId)
        val upsertsAfterFirstRun = index.upserts

        val second = reconciler.reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UP_TO_DATE, second.status)
        assertEquals(upsertsAfterFirstRun, index.upserts)
        assertEquals(1L, indexedRevision(contextId))
    }

    @Test
    fun `reprojects an updated revision and moves the acknowledgement forward`() {
        create(contextId)
        val reconciler = reconciler()
        reconciler.reconcileContext(tenant, contextId)
        bump(contextId)

        val result = reconciler.reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UPSERTED, result.status)
        assertEquals(2L, result.targetContentRevision)
        assertEquals(2L, index.point(tenant, contextId)!!.contentRevision)
        assertEquals("body-v2", index.point(tenant, contextId)!!.content)
        assertEquals(2L, indexedRevision(contextId))
        assertEquals(2L, contentRevision(contextId))
    }

    @Test
    fun `repairs a lagging projection left by another writer`() {
        create(contextId)
        index.put(
            IndexPoint(
                tenantId = tenant,
                contextId = contextId,
                contentRevision = 1,
                indexGeneration = "stale",
                status = ContextStatus.CONFIRMED,
                title = "old",
                summary = "old",
                content = "old",
                embedding = listOf(1f),
            ),
        )
        // Pretend an earlier attempt ACKed, so only the projection is wrong.
        assertEquals(0L, indexedRevision(contextId))
        bump(contextId)
        bump(contextId)

        val result = reconciler().reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UPSERTED, result.status)
        assertEquals(3L, index.point(tenant, contextId)!!.contentRevision)
        assertEquals(3L, indexedRevision(contextId))
    }

    // ---- deletion ---------------------------------------------------------------------------

    @Test
    fun `deletes the point for a tombstone before acknowledging`() {
        create(contextId)
        val reconciler = reconciler()
        reconciler.reconcileContext(tenant, contextId)
        tombstone(contextId)

        val result = reconciler.reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.DELETED, result.status)
        assertEquals(2L, result.targetContentRevision)
        assertNull(index.point(tenant, contextId))
        assertEquals(2L, indexedRevision(contextId))
        assertEquals(2L, contentRevision(contextId))
    }

    @Test
    fun `acknowledges a tombstone whose point is already absent`() {
        create(contextId, deleted = true)

        val result = reconciler().reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.DELETED, result.status)
        assertEquals(1L, indexedRevision(contextId))
    }

    @Test
    fun `removes an orphan point when the authoritative key is absent`() {
        index.put(
            IndexPoint(
                tenantId = tenant,
                contextId = missingId,
                contentRevision = 1,
                indexGeneration = "orphan",
                status = ContextStatus.CONFIRMED,
                title = "gone",
                summary = "gone",
                content = "gone",
                embedding = listOf(1f),
            ),
        )

        val result = reconciler().reconcileContext(tenant, missingId)

        assertEquals(ReconcileStatus.DELETED, result.status)
        assertNull(index.point(tenant, missingId))
    }

    @Test
    fun `does not acknowledge a delete whose point is still visible`() {
        create(contextId)
        val reconciler = reconciler(attempts = 2)
        reconciler.reconcileContext(tenant, contextId)
        tombstone(contextId)
        index.deleteIsVisible = false

        val result = reconciler.reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.RETRY_REQUIRED, result.status)
        assertEquals(2, index.deletes)
        assertTrue(index.point(tenant, contextId) != null)
        assertEquals(1L, indexedRevision(contextId))
        assertEquals(2L, contentRevision(contextId))
    }

    // ---- failure handling -------------------------------------------------------------------

    @Test
    fun `does not acknowledge when the authoritative read is unavailable`() {
        create(contextId)

        val result = reconciler(GetFailingStorage(memory)).reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UNAVAILABLE, result.status)
        assertNull(index.point(tenant, contextId))
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `does not acknowledge when the index read is unavailable`() {
        create(contextId)
        index.readsUnavailable = true

        val result = reconciler().reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UNAVAILABLE, result.status)
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `does not acknowledge when the index write is unavailable`() {
        create(contextId)
        index.writesUnavailable = true

        val result = reconciler().reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UNAVAILABLE, result.status)
        assertNull(index.point(tenant, contextId))
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `does not acknowledge a delete when the index write is unavailable`() {
        create(contextId)
        val reconciler = reconciler()
        reconciler.reconcileContext(tenant, contextId)
        tombstone(contextId)
        index.writesUnavailable = true

        val result = reconciler.reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UNAVAILABLE, result.status)
        assertTrue(index.point(tenant, contextId) != null)
        assertEquals(1L, indexedRevision(contextId))
    }

    @Test
    fun `gives up without acknowledgement when the upsert never becomes visible`() {
        create(contextId)
        index.upsertIsVisible = false

        val result = reconciler(attempts = 3).reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.RETRY_REQUIRED, result.status)
        assertEquals(3, index.upserts)
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `does not acknowledge a projection superseded while it was being written`() {
        create(contextId)
        index.afterUpsert = { bump(contextId) }

        val result = reconciler().reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.RETRY_REQUIRED, result.status)
        assertEquals(1L, index.point(tenant, contextId)!!.contentRevision)
        assertEquals(0L, indexedRevision(contextId))
        assertEquals(2L, contentRevision(contextId))
    }

    @Test
    fun `does not acknowledge when the acknowledgement CAS keeps conflicting`() {
        create(contextId)

        val result = reconciler(AckRefusingStorage(memory)).reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.RETRY_REQUIRED, result.status)
        assertEquals(1L, index.point(tenant, contextId)!!.contentRevision)
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `requires a non-blank index generation`() {
        create(contextId)

        val result = reconciler(generation = IndexGenerationSource { "" }).reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.RETRY_REQUIRED, result.status)
        assertEquals(0, index.upserts)
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `refuses empty non-finite and failed embeddings without acknowledging`() {
        create(contextId)
        val reconciler = reconciler()

        val empty = reconciler(embed = EmbeddingProvider { emptyList() }).reconcileContext(tenant, contextId)
        val nonFinite = reconciler(embed = EmbeddingProvider { listOf(Float.NaN) }).reconcileContext(tenant, contextId)
        val throws = reconciler(embed = EmbeddingProvider { throw IllegalStateException("model offline") })
            .reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UNAVAILABLE, empty.status)
        assertEquals(ReconcileStatus.UNAVAILABLE, nonFinite.status)
        assertEquals(ReconcileStatus.UNAVAILABLE, throws.status)
        assertNull(index.point(tenant, contextId))
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `reports unsupported when the index port lacks the operation`() {
        create(contextId)
        index.unsupported = true

        val result = reconciler().reconcileContext(tenant, contextId)

        assertEquals(ReconcileStatus.UNSUPPORTED, result.status)
        assertEquals(0L, indexedRevision(contextId))
    }

    // ---- tenant enumeration ------------------------------------------------------------------

    @Test
    fun `reconciles every context in a complete enumeration`() {
        create(contextId)
        create(secondId)

        val report = reconciler().reconcileTenant(tenant)

        assertEquals(EnumerationStatus.COMPLETE, report.enumeration)
        assertEquals(2, report.results.size)
        assertTrue(report.results.all { it.status == ReconcileStatus.UPSERTED })
        assertEquals(contextId, report.results[0].contextId)
        assertEquals(secondId, report.results[1].contextId)
    }

    @Test
    fun `never reports a partial enumeration as complete`() {
        create(contextId)
        create(secondId)

        val report = reconciler(maxEnumeratedObjects = 1).reconcileTenant(tenant)

        assertEquals(EnumerationStatus.TOO_LARGE, report.enumeration)
        assertTrue(report.results.isEmpty())
        assertNull(index.point(tenant, contextId))
    }

    @Test
    fun `surfaces enumeration unsupported and unavailable`() {
        create(contextId)

        val unsupported = reconciler(EnumerationUnsupportedStorage(memory)).reconcileTenant(tenant)
        val unavailable = reconciler(EnumerationUnavailableStorage(memory)).reconcileTenant(tenant)

        assertEquals(EnumerationStatus.UNSUPPORTED, unsupported.enumeration)
        assertTrue(unsupported.results.isEmpty())
        assertEquals(EnumerationStatus.UNAVAILABLE, unavailable.enumeration)
        assertTrue(unavailable.results.isEmpty())
        assertEquals(0L, indexedRevision(contextId))
    }

    @Test
    fun `rejects a blank tenant or context id`() {
        val reconciler = reconciler()
        assertThrows(IllegalArgumentException::class.java) { reconciler.reconcileTenant(" ") }
        assertThrows(IllegalArgumentException::class.java) { reconciler.reconcileContext(tenant, " ") }
    }

    // ---- wrappers ----------------------------------------------------------------------------

    private class GetFailingStorage(private val delegate: ContextStorage) : ContextStorage {
        override fun get(tenantId: String, id: String): ReadResult = ReadResult.Unavailable
        override fun createIfAbsent(value: ContextAggregate): WriteResult = delegate.createIfAbsent(value)
        override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult =
            delegate.compareAndSwap(tenantId, id, expectedStorageRevision, value)
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult =
            delegate.enumerateTenant(tenantId, maxItems)
    }

    /** Rejects ACK-only writes, as a lost CAS race would. */
    private class AckRefusingStorage(private val delegate: ContextStorage) : ContextStorage {
        override fun get(tenantId: String, id: String): ReadResult = delegate.get(tenantId, id)
        override fun createIfAbsent(value: ContextAggregate): WriteResult = delegate.createIfAbsent(value)
        override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult {
            val current = delegate.get(tenantId, id)
            if (current is ReadResult.Found) {
                val previous = current.stored
                if (previous.value.contentRevision == value.contentRevision &&
                    value.indexedContentRevision > previous.value.indexedContentRevision
                ) {
                    return WriteResult.Conflict
                }
            }
            return delegate.compareAndSwap(tenantId, id, expectedStorageRevision, value)
        }
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult =
            delegate.enumerateTenant(tenantId, maxItems)
    }

    private class EnumerationUnsupportedStorage(private val delegate: ContextStorage) : ContextStorage by delegate {
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult = EnumerationResult.Unsupported
    }

    private class EnumerationUnavailableStorage(private val delegate: ContextStorage) : ContextStorage by delegate {
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult = EnumerationResult.Unavailable
    }
}
