package net.kigawa.recl.storage.memory

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrantFencedSourceVerificationTest {
    private val epoch = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(epoch), ZoneOffset.UTC)
    private val tenant = "tenant-a"
    private val owner = "owner-a"
    private val id = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a81"
    private val sourceId = "github-1"
    private val digest = "sha256:" + "b".repeat(64)
    private val uri = "https://github.com/kigawa-net/recl/commit/" + "a".repeat(40)
    private val source = SourceSubmission(sourceId, SourceType.GITHUB_COMMIT, uri,
        contentDigest = digest, trust = SourceTrust.PRIMARY_SOURCE).unverified()
    private val fence = SourceGrantFence(tenant, owner, "kigawa-net", "recl",
        repositoryId = 4321, installationId = 9876, generation = 7,
        expiresAtEpochMillis = epoch + 60_000)
    private val store = InMemoryContextStorage(clock)

    private fun initialize() {
        assertTrue(store.createIfAbsent(ContextAggregate(
            tenant, id, owner, ContextKind.FINDING, ContextStatus.CONFIRMED,
            "Title", "Summary", "Content", setOf("project:recl"),
            pinned = true, sources = listOf(source)
        )) is WriteResult.Applied)
        store.installGrantForTestOnly(fence)
    }

    private fun receipt(boundFence: SourceGrantFence? = fence): VerificationEvidence =
        VerificationEvidence(tenant, id, sourceId, uri, digest, 1, "github-app",
            "recl-source-verifier", "attestation-1", epoch,
            epoch + 30_000, sourceGrantFence = boundFence)

    private fun verify(
        attestor: TrustedSourceAttestor = TrustedSourceAttestor { _, _ ->
            SourceAttestationResult.Verified(receipt())
        },
        commit: GrantFencedContextCommit? = store,
        required: Boolean = true,
    ) = SourceVerificationService(store, attestor, "recl-source-verifier", clock,
        requireGrantFenceForVerified = required, fencedCommit = commit)
            .verify(tenant, id, sourceId, 1)

    private fun current() = (store.get(tenant, id) as ReadResult.Found).stored.value

    @Test fun `atomic conditional grant and Context commit succeeds only for current generation`() {
        initialize()
        assertEquals(SourceVerificationResult.Applied(2), verify())
        assertEquals(VerificationState.VERIFIED, current().sources.single().verification)
        assertEquals(7L, current().sources.single().evidence!!.sourceGrantFence!!.generation)
        assertEquals(2L, current().contentRevision)
    }

    @Test fun `revoke after attestation but before atomic CAS rejects stale proof`() {
        initialize()
        val concurrent = object : GrantFencedContextCommit {
            override fun compareAndSwapWithGrant(
                tenantId: String, contextId: String, expectedStorageRevision: Long,
                updated: ContextAggregate, fence: SourceGrantFence,
            ): GrantFencedWriteResult {
                store.revokeGrantForTestOnly(fence)
                return store.compareAndSwapWithGrant(
                    tenantId, contextId, expectedStorageRevision, updated, fence
                )
            }
        }
        assertEquals(SourceVerificationResult.GrantRevoked, verify(commit = concurrent))
        assertEquals(VerificationState.UNVERIFIED, current().sources.single().verification)
        assertEquals(1L, current().contentRevision)
    }

    @Test fun `regrant of same repo at new generation rejects old worker even when allowed`() {
        initialize()
        val changed = fence.copy(generation = 8, installationId = 9877)
        val concurrent = GrantFencedContextCommit { tenantId, contextId, revision, updated, prior ->
            store.installGrantForTestOnly(changed)
            store.compareAndSwapWithGrant(tenantId, contextId, revision, updated, prior)
        }
        assertEquals(SourceVerificationResult.GrantRevoked, verify(commit = concurrent))
        assertEquals(VerificationState.UNVERIFIED, current().sources.single().verification)
    }

    @Test fun `production required fencing without proof or atomic adapter fails closed`() {
        initialize()
        assertEquals(SourceVerificationResult.CapabilityUnsupported, verify(commit = null))
        assertEquals(SourceVerificationResult.CapabilityUnsupported, verify(
            attestor = TrustedSourceAttestor { _, _ ->
                SourceAttestationResult.Verified(receipt(boundFence = null))
            }
        ))
        assertEquals(1L, current().contentRevision)
    }

    @Test fun `unavailable Fortis single group batch never falls back to unfenced CAS`() {
        initialize()
        assertEquals(SourceVerificationResult.CapabilityUnsupported,
            verify(commit = GrantFencedContextCommit { _, _, _, _, _ ->
                GrantFencedWriteResult.Unsupported
            }))
        assertEquals(1L, current().contentRevision)
    }

    @Test fun `lost write response is UNKNOWN and never automatically resent`() {
        initialize()
        var writes = 0
        val adapter = GrantFencedContextCommit { _, _, _, _, _ ->
            writes++
            GrantFencedWriteResult.OutcomeUnknown
        }
        assertEquals(SourceVerificationResult.OutcomeUnknown, verify(commit = adapter))
        assertEquals(1, writes)
        assertEquals(1L, current().contentRevision)
    }

    @Test fun `forged tenant owner repository and expired fence are rejected`() {
        initialize()
        for (bad in listOf(
            fence.copy(tenantId = "tenant-b"),
            fence.copy(ownerId = "other-owner"),
            fence.copy(repositoryName = "another-repo"),
            fence.copy(expiresAtEpochMillis = epoch),
        )) {
            assertEquals(SourceVerificationResult.RejectedEvidence, verify(
                attestor = TrustedSourceAttestor { _, _ ->
                    SourceAttestationResult.Verified(receipt(bad))
                }
            ))
        }
        assertEquals(1L, current().contentRevision)
    }

    @Test fun `old generation cannot be reinstalled after a revoke`() {
        initialize()
        store.revokeGrantForTestOnly(fence)
        assertThrows(IllegalArgumentException::class.java) {
            store.installGrantForTestOnly(fence)
        }
        store.installGrantForTestOnly(fence.copy(generation = 8))
        assertEquals(SourceVerificationResult.GrantRevoked, verify())
    }

    @Test fun `source metadata mutation during verification fences the old proof`() {
        initialize()
        val verifier = TrustedSourceAttestor { _, _ ->
            assertEquals(MutationResult.Applied(2),
                ContextService(store).update(Principal(tenant, owner), id, 1,
                    "mutation-1", "digest-1", ContextPatch(content = "Changed")))
            SourceAttestationResult.Verified(receipt())
        }
        assertEquals(SourceVerificationResult.Conflict(2), verify(attestor = verifier))
        assertEquals(VerificationState.UNVERIFIED, current().sources.single().verification)
    }
}
