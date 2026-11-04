package net.kigawa.recl.storage.fortis

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.OperationReceipt
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.WriteResult
import net.kigawa.recl.core.testing.ContextStorageContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FortisContextStorageTest {
    private val fake = FaithfulFortisKvFake()
    private val storage = FortisContextStorage(fake)
    private val t = ContextStorageContract.TENANT_A
    private val c1 = ContextStorageContract.CTX_1
    private val c2 = ContextStorageContract.CTX_2
    private val c3 = ContextStorageContract.CTX_3

    private fun agg(
        tenant: String = t,
        id: String = c1,
        title: String = "Initial",
        receiptId: String = "create-1",
        digest: String = "digest-$title",
    ) = ContextAggregate(
        tenantId = tenant, id = id, ownerId = "owner-1",
        kind = ContextKind.TASK, status = ContextStatus.CONFIRMED,
        title = title, summary = "s", content = "c",
        operationReceipts = listOf(OperationReceipt(receiptId, digest, 1)),
    )

    @Test
    fun `applied create reports the Fortis log index as storage revision`() {
        val result = storage.createIfAbsent(agg())
        val applied = assertInstanceOf(WriteResult.Applied::class.java, result).stored
        val read = assertInstanceOf(ReadResult.Found::class.java, storage.get(t, c1)).stored
        assertEquals(read.storageRevision, applied.storageRevision)
        assertTrue(applied.storageRevision >= 1)
    }

    @Test
    fun `key uses the documented layout`() {
        storage.createIfAbsent(agg())
        val raw = fake.getLinearizable("recl/v1/t/tenant-a/c/$c1")
        assertInstanceOf(net.kigawa.recl.storage.fortis.FortisRead.Value::class.java, raw)
    }

    @Test
    fun `timeout after apply is resolved as Applied through the receipt without resubmitting`() {
        fake.submitFaults.add(FaithfulFortisKvFake.SubmitFault.TIMEOUT_AFTER_APPLY)
        val result = storage.createIfAbsent(agg())
        assertInstanceOf(WriteResult.Applied::class.java, result)
        assertEquals(1, fake.submitCalls)
        assertEquals("Initial", (assertInstanceOf(ReadResult.Found::class.java, storage.get(t, c1)).stored.value.title))
    }

    @Test
    fun `timeout before apply without a receipt is OutcomeUnknown and not a success`() {
        fake.submitFaults.add(FaithfulFortisKvFake.SubmitFault.TIMEOUT_BEFORE_APPLY)
        assertEquals(WriteResult.OutcomeUnknown, storage.createIfAbsent(agg()))
        assertEquals(ReadResult.NotFound, storage.get(t, c1))
    }

    @Test
    fun `unconfirmed submit stays OutcomeUnknown when the receipt lookup itself fails`() {
        fake.submitFaults.add(FaithfulFortisKvFake.SubmitFault.TIMEOUT_AFTER_APPLY)
        fake.lookupUnavailable = true
        assertEquals(WriteResult.OutcomeUnknown, storage.createIfAbsent(agg()))
    }

    @Test
    fun `unknown outcome of a rejected compare and swap resolves to Conflict through the receipt`() {
        val first = assertInstanceOf(WriteResult.Applied::class.java, storage.createIfAbsent(agg())).stored
        val next = agg().copy(title = "Next", contentRevision = 2,
            operationReceipts = agg().operationReceipts + OperationReceipt("update-2", "d2", 2))
        assertInstanceOf(WriteResult.Applied::class.java, storage.compareAndSwap(t, c1, first.storageRevision, next))

        val stale = next.copy(title = "Stale", operationReceipts = next.operationReceipts.dropLast(1) +
            OperationReceipt("update-stale", "d-stale", 2))
        fake.submitFaults.add(FaithfulFortisKvFake.SubmitFault.TIMEOUT_AFTER_APPLY)
        assertEquals(WriteResult.Conflict, storage.compareAndSwap(t, c1, first.storageRevision, stale))
        assertEquals("Next", assertInstanceOf(ReadResult.Found::class.java, storage.get(t, c1)).stored.value.title)
    }

    @Test
    fun `unavailable submit is Unavailable and writes nothing`() {
        fake.submitFaults.add(FaithfulFortisKvFake.SubmitFault.UNAVAILABLE)
        assertEquals(WriteResult.Unavailable, storage.createIfAbsent(agg()))
        assertEquals(ReadResult.NotFound, storage.get(t, c1))
    }

    @Test
    fun `same requestId with different content is DigestMismatch and maps to OutcomeUnknown`() {
        storage.createIfAbsent(agg(title = "First"))
        assertEquals(WriteResult.OutcomeUnknown, storage.createIfAbsent(agg(title = "Second", digest = "other")))
        assertEquals("First", assertInstanceOf(ReadResult.Found::class.java, storage.get(t, c1)).stored.value.title)
    }

    @Test
    fun `same client requestId on two contexts does not collide inside Fortis`() {
        assertInstanceOf(WriteResult.Applied::class.java, storage.createIfAbsent(agg(id = c1, receiptId = "same")))
        assertInstanceOf(WriteResult.Applied::class.java, storage.createIfAbsent(agg(id = c2, receiptId = "same")))
    }

    @Test
    fun `read unavailable is Unavailable`() {
        fake.getUnavailable = true
        assertEquals(ReadResult.Unavailable, storage.get(t, c1))
    }

    @Test
    fun `compare and swap with revision zero never reaches Fortis`() {
        storage.createIfAbsent(agg())
        val before = fake.submitCalls
        assertEquals(WriteResult.Conflict, storage.compareAndSwap(t, c1, 0, agg(title = "x")))
        assertEquals(before, fake.submitCalls)
    }

    @Test
    fun `write without a receipt is refused before reaching Fortis`() {
        val noReceipt = agg().copy(operationReceipts = emptyList())
        assertThrows(IllegalArgumentException::class.java) { storage.createIfAbsent(noReceipt) }
        assertEquals(0, fake.submitCalls)
    }

    @Test
    fun `compare and swap whose aggregate identity differs from the key is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            storage.compareAndSwap(t, c1, 1, agg(id = c2))
        }
        assertEquals(0, fake.submitCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Tenant-A", "tenant_a", "a/b", "", "-a", "a.b", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "tenant a"])
    fun `non-canonical tenant ids are rejected before reaching Fortis`(tenant: String) {
        assertThrows(IllegalArgumentException::class.java) { storage.get(tenant, c1) }
        assertThrows(IllegalArgumentException::class.java) { storage.createIfAbsent(agg(tenant = tenant)) }
        assertThrows(IllegalArgumentException::class.java) { storage.enumerateTenant(tenant, 5) }
        assertEquals(0, fake.submitCalls)
        assertEquals(0, fake.scanCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "ctx-a",
        "0190D6C2-1A2B-7C3D-8E4F-000000000001",
        "0190d6c2-1a2b-4c3d-8e4f-000000000001",
        "../0190d6c2-1a2b-7c3d-8e4f-000000000001",
        "0190d6c2-1a2b-7c3d-8e4f-00000000000",
    ])
    fun `non-canonical context ids are rejected before reaching Fortis`(id: String) {
        assertThrows(IllegalArgumentException::class.java) { storage.get(t, id) }
        assertThrows(IllegalArgumentException::class.java) { storage.createIfAbsent(agg(id = id)) }
        assertEquals(0, fake.submitCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad id", "a/b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"])
    fun `non-canonical request ids are rejected before reaching Fortis`(requestId: String) {
        assertThrows(IllegalArgumentException::class.java) { storage.createIfAbsent(agg(receiptId = requestId)) }
        assertEquals(0, fake.submitCalls)
    }

    @Test
    fun `enumeration is Complete across several Fortis pages`() {
        val paged = FaithfulFortisKvFake(pageCap = 2)
        val s = FortisContextStorage(paged)
        listOf(c1, c2, c3, "0190d6c2-1a2b-7c3d-8e4f-000000000004", "0190d6c2-1a2b-7c3d-8e4f-000000000005")
            .forEach { s.createIfAbsent(agg(id = it, receiptId = "r-$it")) }
        val complete = assertInstanceOf(EnumerationResult.Complete::class.java, s.enumerateTenant(t, 10))
        assertEquals(5, complete.contexts.size)
        assertEquals(3, paged.scanCalls)
    }

    @Test
    fun `enumeration pins one revision so writes between pages are not mixed in`() {
        val paged = FaithfulFortisKvFake(pageCap = 2)
        val s = FortisContextStorage(paged)
        listOf(c1, c2, c3).forEach { s.createIfAbsent(agg(id = it, receiptId = "r-$it")) }
        paged.afterPage = { served ->
            if (served == 1) s.createIfAbsent(agg(id = "0190d6c2-1a2b-7c3d-8e4f-0000000000ff", receiptId = "late"))
        }
        val complete = assertInstanceOf(EnumerationResult.Complete::class.java, s.enumerateTenant(t, 10))
        assertEquals(setOf(c1, c2, c3), complete.contexts.map { it.value.id }.toSet())
    }

    @Test
    fun `enumeration failing on a later page is Unavailable and never a partial Complete`() {
        val paged = FaithfulFortisKvFake(pageCap = 2)
        val s = FortisContextStorage(paged)
        listOf(c1, c2, c3).forEach { s.createIfAbsent(agg(id = it, receiptId = "r-$it")) }
        paged.scanFailAfterPages = 1
        assertEquals(EnumerationResult.Unavailable, s.enumerateTenant(t, 10))
    }

    @Test
    fun `enumeration beyond maxItems is TooLarge`() {
        storage.createIfAbsent(agg(id = c1))
        storage.createIfAbsent(agg(id = c2, receiptId = "r2"))
        storage.createIfAbsent(agg(id = c3, receiptId = "r3"))
        assertEquals(EnumerationResult.TooLarge, storage.enumerateTenant(t, 2))
    }

    @Test
    fun `enumeration is Unsupported when the scan capability is absent`() {
        fake.scanUnsupported = true
        assertEquals(EnumerationResult.Unsupported, storage.enumerateTenant(t, 5))
    }

    @Test
    fun `tenant prefix does not match a longer tenant name`() {
        storage.createIfAbsent(agg(tenant = "tenant-a", id = c1))
        storage.createIfAbsent(agg(tenant = "tenant-ab", id = c2, receiptId = "other"))
        val complete = assertInstanceOf(EnumerationResult.Complete::class.java, storage.enumerateTenant("tenant-a", 5))
        assertEquals(listOf(c1), complete.contexts.map { it.value.id })
    }

    @Test
    fun `corrupt stored bytes are Unavailable for read and enumeration`() {
        fake.putRawForTest("recl/v1/t/$t/c/$c1", "not a record".toByteArray())
        assertEquals(ReadResult.Unavailable, storage.get(t, c1))
        assertEquals(EnumerationResult.Unavailable, storage.enumerateTenant(t, 5))
    }

    @Test
    fun `a valid record stored under another context key is Unavailable`() {
        fake.putRawForTest("recl/v1/t/$t/c/$c1", ContextAggregateCodec.encode(agg(id = c2)))
        assertEquals(ReadResult.Unavailable, storage.get(t, c1))
    }
}
