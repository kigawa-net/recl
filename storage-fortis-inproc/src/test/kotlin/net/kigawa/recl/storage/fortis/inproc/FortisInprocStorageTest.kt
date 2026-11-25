package net.kigawa.recl.storage.fortis.inproc

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.kigawa.fortis.io.fs.Fs
import net.kigawa.fortis.raft.log.FileRaftLog
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.OpenedStorage
import net.kigawa.recl.core.WriteResult
import net.kigawa.recl.core.testing.ContextStorageContract
import net.kigawa.recl.storage.fortis.FortisCondition
import net.kigawa.recl.storage.fortis.FortisConditionalBatch
import net.kigawa.recl.storage.fortis.FortisLookupResult
import net.kigawa.recl.storage.fortis.FortisMutation
import net.kigawa.recl.storage.fortis.FortisOutcome
import net.kigawa.recl.storage.fortis.FortisRead
import net.kigawa.recl.storage.fortis.FortisScanResult
import net.kigawa.recl.storage.fortis.FortisSubmitResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class FortisInprocStorageTest : ContextStorageContract() {
    @field:TempDir
    lateinit var dir: Path
    private var opened: OpenedStorage? = null

    override fun newStorage(): ContextStorage = open().also { opened = it }.storage

    @AfterEach
    fun closeStorage() {
        opened?.close()
    }

    @Test
    fun `close and reopen preserves context revision and deduplicates a resend`() {
        val value = aggregate()
        val original = open().use { first ->
            assertIs<WriteResult.Applied>(first.storage.createIfAbsent(value)).stored
        }
        open().use { reopened ->
            assertEquals(original, found(reopened.storage, value.tenantId, value.id))
            assertEquals(original, assertIs<WriteResult.Applied>(reopened.storage.createIfAbsent(value)).stored)
            assertEquals(original, found(reopened.storage, value.tenantId, value.id))
            assertEquals(
                WriteResult.OutcomeUnknown,
                reopened.storage.createIfAbsent(value.copy(title = "Different payload, same request ID")),
            )
        }
    }

    @Test
    fun `durable log alone replays a committed write and its receipt`() {
        val value = aggregate()
        val recoveryDir = Files.createDirectory(dir.resolve("recovery"))
        val stored = FortisStorageProvider().open(mapOf("dir" to dir.toString(), "snapshotEvery" to "3600")).use { first ->
            val applied = assertIs<WriteResult.Applied>(first.storage.createIfAbsent(value)).stored
            // Capture the quiescent log and state before a snapshot or orderly close.
            listOf("log", "state").forEach { name -> Files.copy(dir.resolve(name), recoveryDir.resolve(name)) }
            applied
        }
        FortisStorageProvider().open(mapOf("dir" to recoveryDir.toString())).use { reopened ->
            assertEquals(stored, found(reopened.storage, value.tenantId, value.id))
            assertEquals(stored, assertIs<WriteResult.Applied>(reopened.storage.createIfAbsent(value)).stored)
        }
    }

    @Test
    fun `periodic snapshot compacts the log before shutdown and recovers the stored revision`() {
        val value = aggregate(title = "A durable context")
        val stored = FortisStorageProvider().open(mapOf("dir" to dir.toString(), "snapshotEvery" to "1")).use { first ->
            val applied = assertIs<WriteResult.Applied>(first.storage.createIfAbsent(value)).stored
            runBlocking {
                withTimeout(10.seconds) {
                    val path = dir.resolve("log")
                    val file = Fs.getPath(path.toString()).toFile()
                    while (true) {
                        // No more writes are in flight here. Snapshot compaction replaces the file atomically.
                        if (Files.exists(dir.resolve("snapshot"))) {
                            val compacted = FileRaftLog.open(file)
                            if ((compacted.snapshotMetadata()?.lastIncludedIndex ?: 0) >= applied.storageRevision) {
                                assertNull(compacted.get(applied.storageRevision))
                                break
                            }
                        }
                        delay(20)
                    }
                }
            }
            applied
        }
        open().use { reopened ->
            assertEquals(stored, found(reopened.storage, value.tenantId, value.id))
        }
    }

    @Test
    fun `a single directory has one owner and close is idempotent`() {
        val first = open()
        first.use {
            val failure = assertFailsWith<IOException> { open().close() }
            assertTrue(failure.message.orEmpty().contains("Another durable writer"))
        }
        open().use { second ->
            first.close()
            assertFailsWith<IOException> { open().close() }
            assertEquals("fortis (in-process, single-node, durable)", second.mode)
        }
    }

    @Test
    fun `reads immediately see the applied value and its CAS revision`() {
        open().use {
            val value = aggregate()
            val original = assertIs<WriteResult.Applied>(it.storage.createIfAbsent(value)).stored
            val updated = assertIs<WriteResult.Applied>(
                it.storage.compareAndSwap(value.tenantId, value.id, original.storageRevision, nextOf(value, "Updated")),
            ).stored
            assertTrue(updated.storageRevision > original.storageRevision)
            assertEquals("Updated", updated.value.title)
            assertEquals(updated, found(it.storage, value.tenantId, value.id))
        }
    }

    @Test
    fun `lookup preserves rejected receipt index and uses Fortis request encoding`() {
        val first = batch("first", "prefix/1")
        val rejected = batch("rejected", "prefix/1")
        val receipt = node().use { node ->
            assertEquals(FortisLookupResult.ReceiptExpiredOrUnknown, node.port.lookup(first))
            val applied = assertIs<FortisSubmitResult.Stored>(node.port.submit(first)).receipt
            assertEquals(FortisOutcome.Applied, applied.outcome)
            val stored = assertIs<FortisSubmitResult.Stored>(node.port.submit(rejected)).receipt
            assertTrue(stored.logIndex > applied.logIndex)
            assertEquals(FortisOutcome.Rejected(0, applied.logIndex), stored.outcome)
            stored
        }
        node().use { node ->
            val found = assertIs<FortisLookupResult.Found>(node.port.lookup(rejected)).receipt
            assertEquals(receipt.logIndex, found.logIndex)
            assertEquals(receipt.outcome, found.outcome)
            val different = batch("rejected", "prefix/1", "different")
            assertEquals(FortisLookupResult.DigestMismatch, node.port.lookup(different))
            assertEquals(FortisSubmitResult.DigestMismatch, node.port.submit(different))
        }
    }

    @Test
    fun `scan continuation keeps its revision across writes`() {
        node().use { node ->
            assertIs<FortisSubmitResult.Stored>(node.port.submit(batch("first", "prefix/1")))
            val second = assertIs<FortisSubmitResult.Stored>(node.port.submit(batch("second", "prefix/2"))).receipt
            val firstPage = assertIs<FortisScanResult.Page>(node.port.scanPrefix("prefix/", 1, null, null))
            assertEquals("prefix/1", firstPage.entries.single().key)
            val cursor = assertNotNull(firstPage.nextCursor)

            assertIs<FortisSubmitResult.Stored>(node.port.submit(batch("third", "prefix/3")))
            val next = assertIs<FortisScanResult.Page>(node.port.scanPrefix("prefix/", 1, firstPage.revision, cursor))
            assertEquals(firstPage.revision, next.revision)
            assertEquals("prefix/2", next.entries.single().key)
            assertEquals(second.logIndex, next.entries.single().revision)
            assertContentEquals("value".encodeToByteArray(), next.entries.single().value)
            assertNull(next.nextCursor)
            assertIs<FortisScanResult.InvalidCursor>(node.port.scanPrefix("other/", 1, firstPage.revision, cursor))
            assertIs<FortisScanResult.Unavailable>(node.port.scanPrefix("prefix/", 1, Long.MAX_VALUE, null))
        }
    }

    @Test
    fun `closed node reports unavailability through the synchronous port`() {
        val port = node().use { it.port }
        val write = batch("first", "prefix/1")
        assertEquals(FortisRead.Unavailable, port.getLinearizable("prefix/1"))
        assertEquals(FortisSubmitResult.OutcomeUnknown, port.submit(write))
        assertEquals(FortisLookupResult.Unavailable("STOPPED"), port.lookup(write))
        assertEquals(FortisScanResult.Unavailable("STOPPED"), port.scanPrefix("prefix/", 1, null, null))
    }

    private fun open() = FortisStorageProvider().open(mapOf("dir" to dir.toString()))

    private fun node() = FortisInprocNode.open(dir, "recl", 1.hours)

    private fun batch(requestId: String, key: String, value: String = "value") = FortisConditionalBatch(
        "principal",
        requestId,
        listOf(FortisCondition.Absent(key)),
        listOf(FortisMutation.Put(key, value.encodeToByteArray())),
    )
}
