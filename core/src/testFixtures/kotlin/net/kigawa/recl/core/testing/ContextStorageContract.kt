package net.kigawa.recl.core.testing

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextSource
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.OperationReceipt
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.SourceTrust
import net.kigawa.recl.core.SourceType
import net.kigawa.recl.core.StoredContext
import net.kigawa.recl.core.WriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioural contract shared by every [ContextStorage] adapter. A subclass supplies a fresh,
 * empty storage for each test. Only cases that every adapter must satisfy live here; adapter
 * specific outcomes (OutcomeUnknown, Unavailable, Unsupported, ID validation) belong in the
 * adapter's own tests.
 *
 * Writes must carry the receipt for the request they belong to (the last entry of
 * `operationReceipts`), as [net.kigawa.recl.core.ContextService] does.
 */
abstract class ContextStorageContract {
    protected abstract fun newStorage(): ContextStorage

    protected fun aggregate(
        tenant: String = TENANT_A,
        id: String = CTX_1,
        title: String = "Initial",
        receipts: List<OperationReceipt> = listOf(OperationReceipt("create-1", "digest-create-1", 1)),
        revision: Long = 1,
    ) = ContextAggregate(
        tenantId = tenant, id = id, ownerId = OWNER,
        kind = ContextKind.DECISION, status = ContextStatus.CONFIRMED,
        title = title, summary = "Summary", content = "Content",
        contentRevision = revision,
        operationReceipts = receipts,
    )

    protected fun nextOf(current: ContextAggregate, title: String): ContextAggregate = current.copy(
        title = title,
        contentRevision = current.contentRevision + 1,
        // Each distinct request gets its own requestId; reusing one with other content is a DigestMismatch.
        operationReceipts = current.operationReceipts +
            OperationReceipt("req-$title", "digest-$title", current.contentRevision + 1),
    )

    @Test
    fun `created context reads back with a positive storage revision`() {
        val storage = newStorage()
        val value = aggregate()
        assertInstanceOf(WriteResult.Applied::class.java, storage.createIfAbsent(value))
        val stored = found(storage, TENANT_A, CTX_1)
        assertEquals(value, stored.value)
        assertTrue(stored.storageRevision >= 1)
    }

    @Test
    fun `duplicate create conflicts and keeps the original`() {
        val storage = newStorage()
        storage.createIfAbsent(aggregate(title = "Original"))
        val replacement = aggregate(title = "Replacement", receipts = listOf(OperationReceipt("create-2", "digest-create-2", 1)))
        assertEquals(WriteResult.Conflict, storage.createIfAbsent(replacement))
        assertEquals("Original", found(storage, TENANT_A, CTX_1).value.title)
    }

    @Test
    fun `missing context is not found`() {
        assertEquals(ReadResult.NotFound, newStorage().get(TENANT_A, CTX_1))
    }

    @Test
    fun `compare and swap with the current storage revision applies and advances it`() {
        val storage = newStorage()
        storage.createIfAbsent(aggregate())
        val before = found(storage, TENANT_A, CTX_1)
        val next = nextOf(before.value, "Updated")
        val result = storage.compareAndSwap(TENANT_A, CTX_1, before.storageRevision, next)
        val applied = assertInstanceOf(WriteResult.Applied::class.java, result).stored
        assertTrue(applied.storageRevision > before.storageRevision)
        assertEquals(next, found(storage, TENANT_A, CTX_1).value)
    }

    @Test
    fun `stale compare and swap conflicts and leaves the stored value unchanged`() {
        val storage = newStorage()
        storage.createIfAbsent(aggregate())
        val first = found(storage, TENANT_A, CTX_1)
        val firstNext = nextOf(first.value, "First")
        assertInstanceOf(WriteResult.Applied::class.java,
            storage.compareAndSwap(TENANT_A, CTX_1, first.storageRevision, firstNext))

        val stale = storage.compareAndSwap(TENANT_A, CTX_1, first.storageRevision, nextOf(first.value, "Stale"))
        assertEquals(WriteResult.Conflict, stale)
        assertEquals(firstNext, found(storage, TENANT_A, CTX_1).value)
    }

    @Test
    fun `compare and swap on a missing context conflicts`() {
        val storage = newStorage()
        val result = storage.compareAndSwap(TENANT_A, CTX_1, 1, aggregate())
        assertEquals(WriteResult.Conflict, result)
        assertEquals(ReadResult.NotFound, storage.get(TENANT_A, CTX_1))
    }

    @Test
    fun `tenants are isolated even when context ids collide`() {
        val storage = newStorage()
        storage.createIfAbsent(aggregate(tenant = TENANT_A, title = "A"))
        assertEquals(ReadResult.NotFound, storage.get(TENANT_B, CTX_1))
        assertInstanceOf(WriteResult.Applied::class.java,
            storage.createIfAbsent(aggregate(tenant = TENANT_B, title = "B")))
        assertEquals("A", found(storage, TENANT_A, CTX_1).value.title)
        assertEquals("B", found(storage, TENANT_B, CTX_1).value.title)
    }

    @Test
    fun `enumeration returns only the requested tenant as a complete set`() {
        val storage = newStorage()
        storage.createIfAbsent(aggregate(id = CTX_1))
        storage.createIfAbsent(aggregate(id = CTX_2))
        storage.createIfAbsent(aggregate(tenant = TENANT_B, id = CTX_3))

        val result = storage.enumerateTenant(TENANT_A, maxItems = 10)
        val complete = assertInstanceOf(EnumerationResult.Complete::class.java, result)
        assertEquals(setOf(CTX_1, CTX_2), complete.contexts.map { it.value.id }.toSet())
        assertTrue(complete.contexts.all { it.value.tenantId == TENANT_A })
    }

    @Test
    fun `enumeration beyond maxItems is TooLarge rather than a truncated page`() {
        val storage = newStorage()
        storage.createIfAbsent(aggregate(id = CTX_1))
        storage.createIfAbsent(aggregate(id = CTX_2))
        storage.createIfAbsent(aggregate(id = CTX_3))
        assertEquals(EnumerationResult.TooLarge, storage.enumerateTenant(TENANT_A, maxItems = 2))
    }

    @Test
    fun `rich aggregate survives a write and read unchanged`() {
        val storage = newStorage()
        val value = aggregate(
            receipts = listOf(
                OperationReceipt("create-1", "digest-create-1", 1),
                OperationReceipt("update-2", "digest-update-2", 2),
            ),
        ).copy(
            scopes = setOf("repo:kigawa-net/recl", "area:storage"),
            pinned = true,
            validFromEpochMillis = 1_000L,
            validUntilEpochMillis = 2_000L,
            sources = listOf(
                ContextSource(
                    sourceId = "src-1", type = SourceType.GITHUB_PR,
                    uri = "https://github.com/kigawa-net/recl/pull/1", locator = "L10",
                    retrievedAtEpochMillis = 500L, contentDigest = "sha256:" + "a".repeat(64),
                    trust = SourceTrust.USER_STATED,
                ),
            ),
            sensitivity = Sensitivity.CONFIDENTIAL,
        )
        assertInstanceOf(WriteResult.Applied::class.java, storage.createIfAbsent(value))
        assertEquals(value, found(storage, TENANT_A, CTX_1).value)
    }

    protected fun found(storage: ContextStorage, tenant: String, id: String): StoredContext {
        val result = storage.get(tenant, id)
        assertFalse(result is ReadResult.NotFound, "expected context $id to exist")
        return assertInstanceOf(ReadResult.Found::class.java, result).stored
    }

    companion object {
        const val TENANT_A = "tenant-a"
        const val TENANT_B = "tenant-b"
        const val OWNER = "owner-1"
        /** Canonical lowercase UUIDv7 values; adapters may reject any other shape. */
        const val CTX_1 = "0190d6c2-1a2b-7c3d-8e4f-000000000001"
        const val CTX_2 = "0190d6c2-1a2b-7c3d-8e4f-000000000002"
        const val CTX_3 = "0190d6c2-1a2b-7c3d-8e4f-000000000003"
    }
}
