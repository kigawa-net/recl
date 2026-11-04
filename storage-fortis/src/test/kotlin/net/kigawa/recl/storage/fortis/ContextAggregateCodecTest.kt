package net.kigawa.recl.storage.fortis

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextSource
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.OperationReceipt
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.SourceGrantFence
import net.kigawa.recl.core.SourceTrust
import net.kigawa.recl.core.SourceType
import net.kigawa.recl.core.VerificationEvidence
import net.kigawa.recl.core.VerificationState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContextAggregateCodecTest {
    private val fence = SourceGrantFence(
        tenantId = "tenant-a", ownerId = "owner-1", repositoryOwner = "kigawa-net", repositoryName = "recl",
        repositoryId = 42, installationId = 7, generation = 3, expiresAtEpochMillis = 9_000_000_000L,
    )
    private val evidence = VerificationEvidence(
        tenantId = "tenant-a", contextId = "0190d6c2-1a2b-7c3d-8e4f-000000000001", sourceId = "src-1",
        canonicalUri = "https://github.com/kigawa-net/recl/pull/1", contentDigest = "sha256:" + "b".repeat(64),
        boundContentRevision = 2, providerIdentity = "github-app:1", audience = "recl",
        attestationId = "att-1", verifiedAtEpochMillis = 1_000, expiresAtEpochMillis = 5_000,
        sourceGrantFence = fence,
    )
    private val rich = ContextAggregate(
        tenantId = "tenant-a", id = "0190d6c2-1a2b-7c3d-8e4f-000000000001", ownerId = "owner-1",
        kind = ContextKind.ARCHITECTURE, status = ContextStatus.CONFIRMED,
        title = "Title あ", summary = "", content = "body\nwith lines",
        scopes = setOf("repo:kigawa-net/recl", "area:storage"),
        pinned = true, sensitivity = Sensitivity.CONFIDENTIAL,
        validFromEpochMillis = 1_000, validUntilEpochMillis = 2_000,
        sources = listOf(
            ContextSource(
                sourceId = "src-1", type = SourceType.GITHUB_PR, uri = "https://github.com/kigawa-net/recl/pull/1",
                locator = null, retrievedAtEpochMillis = null, contentDigest = "sha256:" + "b".repeat(64),
                trust = SourceTrust.PRIMARY_SOURCE, verification = VerificationState.VERIFIED, evidence = evidence,
            ),
        ),
        contentRevision = 2, indexedContentRevision = 1,
        operationReceipts = listOf(
            OperationReceipt("create-1", "d1", 1),
            OperationReceipt("update-2", "d2", 2),
        ),
    )

    @Test
    fun `round trip preserves every field including attestation evidence`() {
        assertEquals(rich, ContextAggregateCodec.decode(ContextAggregateCodec.encode(rich)))
    }

    @Test
    fun `encoding is deterministic regardless of scope insertion order`() {
        val reordered = rich.copy(scopes = linkedSetOf("area:storage", "repo:kigawa-net/recl"))
        assertArrayEquals(ContextAggregateCodec.encode(rich), ContextAggregateCodec.encode(reordered))
    }

    @Test
    fun `unknown schema version is rejected`() {
        val bytes = ContextAggregateCodec.encode(rich)
        bytes[7] = 2 // big-endian int 1 -> 2 at offsets 4..7
        assertThrows(CorruptContextRecord::class.java) { ContextAggregateCodec.decode(bytes) }
    }

    @Test
    fun `truncated and trailing bytes are rejected`() {
        val bytes = ContextAggregateCodec.encode(rich)
        assertThrows(CorruptContextRecord::class.java) { ContextAggregateCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(CorruptContextRecord::class.java) { ContextAggregateCodec.decode(bytes + 0) }
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        assertThrows(CorruptContextRecord::class.java) {
            ContextAggregateCodec.decode(ByteArray(ContextAggregateCodec.MAX_ENCODED_BYTES + 1))
        }
    }
}
