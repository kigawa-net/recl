package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContextServiceTest {
    private val principal = Principal("tenant-a", "owner-1")
    private fun initial() = ContextAggregate(
        tenantId = "tenant-a", id = "ctx-a", ownerId = principal.id,
        kind = ContextKind.DECISION, status = ContextStatus.CONFIRMED,
        title = "Initial", summary = "Summary", content = "Content",
    )

    @Test fun `two writers cannot both commit same expected revision`() {
        val storage = InMemoryContextStorage()
        val service = ContextService(storage)
        assertEquals(MutationResult.Applied(1), service.create(principal, initial(), "create", "sha-create"))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val result = (1..2).map { n -> pool.submit<MutationResult> {
                start.await()
                service.update(principal, "ctx-a", 1, "update-$n", "sha-$n", ContextPatch(title = "Title $n"))
            } }
            start.countDown()
            val actual = result.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, actual.count { it is MutationResult.Applied })
            assertEquals(1, actual.count { it is MutationResult.Conflict })
            val stored = storage.get("tenant-a", "ctx-a") as ReadResult.Found
            assertEquals(2L, stored.stored.value.contentRevision)
        } finally { pool.shutdownNow() }
    }

    @Test fun `worker ack changes physical generation without changing content revision`() {
        val storage = InMemoryContextStorage()
        val service = ContextService(storage)
        service.create(principal, initial(), "create", "sha-create")
        val before = (storage.get("tenant-a", "ctx-a") as ReadResult.Found).stored
        assertTrue(service.acknowledgeIndex("tenant-a", "ctx-a", 1))
        val after = (storage.get("tenant-a", "ctx-a") as ReadResult.Found).stored
        assertEquals(1L, after.value.contentRevision)
        assertEquals(1L, after.value.indexedContentRevision)
        assertTrue(after.storageRevision > before.storageRevision)
        assertEquals(MutationResult.Applied(2), service.update(principal, "ctx-a", 1, "edit", "sha-edit", ContextPatch(title = "New")))
        assertTrue(service.acknowledgeIndex("tenant-a", "ctx-a", 1))
        val final = (storage.get("tenant-a", "ctx-a") as ReadResult.Found).stored.value
        assertEquals(2L, final.contentRevision)
        assertEquals(1L, final.indexedContentRevision)
    }

    @Test fun `a repeated request cannot mutate twice or change digest`() {
        val storage = InMemoryContextStorage()
        val service = ContextService(storage)
        service.create(principal, initial(), "create", "sha-create")
        val patch = ContextPatch(content = "Updated")
        assertEquals(MutationResult.Applied(2), service.update(principal, "ctx-a", 1, "edit", "digest", patch))
        assertEquals(MutationResult.Applied(2, replayed = true), service.update(principal, "ctx-a", 1, "edit", "digest", patch))
        assertEquals(MutationResult.RequestIdReused, service.update(principal, "ctx-a", 1, "edit", "different-digest", patch))
        assertEquals(2L, (storage.get("tenant-a", "ctx-a") as ReadResult.Found).stored.value.contentRevision)
    }

    @Test fun `different tenant or owner cannot update context`() {
        val storage = InMemoryContextStorage()
        val service = ContextService(storage)
        service.create(principal, initial(), "create", "sha-create")
        assertEquals(MutationResult.Forbidden, service.update(Principal("tenant-a", "intruder"), "ctx-a", 1, "e1", "d1", ContextPatch(title = "Bad")))
        assertEquals(MutationResult.NotFound, service.update(Principal("tenant-b", "intruder"), "ctx-a", 1, "e2", "d2", ContextPatch(title = "Bad")))
    }

    @Test fun `deletion is a tombstone with a new content revision`() {
        val storage = InMemoryContextStorage()
        val service = ContextService(storage)
        service.create(principal, initial(), "create", "sha-create")
        assertEquals(MutationResult.Applied(2), service.delete(principal, "ctx-a", 1, "delete", "sha-delete"))
        val stored = (storage.get("tenant-a", "ctx-a") as ReadResult.Found).stored.value
        assertTrue(stored.deleted)
        assertEquals(IndexAction.DELETE, stored.indexAction)
        assertEquals(2L, stored.contentRevision)
        assertEquals(MutationResult.NotFound, service.update(principal, "ctx-a", 2, "edit", "new", ContextPatch(title = "Revive")))
    }

    @Test fun `unknown write outcome is reconciled with its receipt`() {
        val delegate = InMemoryContextStorage()
        val storage = object : ContextStorage by delegate {
            override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult {
                val result = delegate.compareAndSwap(tenantId, id, expectedStorageRevision, value)
                return if (result is WriteResult.Applied) WriteResult.OutcomeUnknown else result
            }
        }
        val service = ContextService(storage)
        service.create(principal, initial(), "create", "sha-create")
        assertEquals(MutationResult.Applied(2, replayed = true), service.update(principal, "ctx-a", 1, "edit", "sha-edit", ContextPatch(title = "After ACK loss")))
        assertEquals(2L, (delegate.get("tenant-a", "ctx-a") as ReadResult.Found).stored.value.contentRevision)
    }
}
