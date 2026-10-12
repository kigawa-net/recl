package net.kigawa.recl.storage.memory

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceVerificationServiceTest {
    private val now = 1_800_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val tenant = "tenant-a"
    private val id = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a71"
    private val sourceId = "github-1"
    private val digest = "sha256:" + "a".repeat(64)
    private val principal = Principal(tenant, "owner-a")
    private val uri = "https://github.com/kigawa-net/recl/commit/" + "b".repeat(40)
    private val store = InMemoryContextStorage()
    private val source = SourceSubmission(
        sourceId, SourceType.GITHUB_COMMIT, uri,
        contentDigest = digest, trust = SourceTrust.PRIMARY_SOURCE,
    ).unverified()
    private val audience = "recl-source-verifier"

    private fun create(
        trust: SourceTrust = SourceTrust.PRIMARY_SOURCE,
        status: ContextStatus = ContextStatus.CONFIRMED,
    ) {
        val context = ContextAggregate(tenant, id, principal.id, ContextKind.FINDING,
            status, "Evidence", "summary", "content", setOf("project:recl"),
            pinned = true, sources = listOf(source.copy(trust = trust)))
        assertTrue(store.createIfAbsent(context) is WriteResult.Applied)
    }

    private fun evidence(
        contentRevision: Long = 1,
        contextId: String = id,
        tenantId: String = tenant,
        sourceRef: ContextSource = source,
        boundAudience: String = audience,
        boundDigest: String = digest,
        verifiedAt: Long = now,
        expiresAt: Long = now + 60_000,
    ) = VerificationEvidence(
        tenantId = tenantId, contextId = contextId, sourceId = sourceRef.sourceId,
        canonicalUri = sourceRef.uri, contentDigest = boundDigest,
        boundContentRevision = contentRevision, providerIdentity = "github-app",
        audience = boundAudience, attestationId = "attestation-1",
        verifiedAtEpochMillis = verifiedAt, expiresAtEpochMillis = expiresAt,
    )

    private fun verifier(
        outcome: SourceAttestationResult = SourceAttestationResult.Verified(evidence()),
        storage: ContextStorage = store,
        clockOverride: Clock = clock,
    ) = SourceVerificationService(
        storage, TrustedSourceAttestor { _, _ -> outcome },
        expectedAudience = audience, clock = clockOverride
    )

    private fun pinned(
        storage: ContextStorage = store, at: Clock = clock,
    ) = PinnedContextReader(storage, ContextTokenCounter { 1 }, clock = at)
        .list(principal, PinnedQuery(setOf("project:recl"), 10, 10))
        as PinnedContextResult.Found

    @Test fun `verified evidence becomes usable and receipt never leaks through public DTO`() {
        create()
        assertTrue(pinned().contexts.isEmpty())
        assertEquals(SourceVerificationResult.Applied(2),
            verifier().verify(tenant, id, sourceId, 1))
        val direct = ContextReader(store, clock).get(principal, id) as ContextReadResult.Found
        assertEquals(VerificationState.VERIFIED, direct.context.sources.single().verification)
        assertFalse(SourceCitation::class.java.declaredFields.any {
            it.name == "evidence" || it.name == "attestationId" || it.name == "providerIdentity"
        })
        assertEquals(listOf(id), pinned().contexts.map { it.id })
        val stored = (store.get(tenant, id) as ReadResult.Found).stored.value
        assertEquals(2L, stored.contentRevision)
        assertEquals(IndexAction.UPSERT, stored.indexAction)
        assertEquals(digest, stored.sources.single().evidence!!.contentDigest)
    }

    @Test fun `expired verification becomes stale and is never pinned`() {
        create()
        assertEquals(SourceVerificationResult.Applied(2),
            verifier().verify(tenant, id, sourceId, 1))
        val expired = Clock.offset(clock, Duration.ofMinutes(2))
        val direct = ContextReader(store, expired).get(principal, id) as ContextReadResult.Found
        assertEquals(VerificationState.STALE, direct.context.sources.single().verification)
        assertTrue(pinned(at = expired).contexts.isEmpty())
        // The underlying receipt remains for a future trusted re-attestation, not an indefinite grant.
        val stored = (store.get(tenant, id) as ReadResult.Found).stored.value
        assertEquals(VerificationState.VERIFIED, stored.sources.single().verification)
    }

    @Test fun `forged tenant context revision audience and digest are all rejected`() {
        create()
        val invalid = listOf(
            evidence(tenantId = "tenant-b"),
            evidence(contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a72"),
            evidence(contentRevision = 99),
            evidence(boundAudience = "other-service"),
            evidence(boundDigest = "sha256:" + "c".repeat(64)),
            evidence(verifiedAt = now + 100, expiresAt = now + 500),
            evidence(expiresAt = now + 2L * 24 * 60 * 60 * 1000),
        )
        for (v in invalid) {
            assertEquals(SourceVerificationResult.RejectedEvidence,
                verifier(SourceAttestationResult.Verified(v))
                    .verify(tenant, id, sourceId, 1))
        }
        assertEquals(1L, (store.get(tenant, id) as ReadResult.Found).stored.value.contentRevision)
        assertTrue(pinned().contexts.isEmpty())
    }

    @Test fun `provider unavailability or unknown never creates a verified source`() {
        create()
        assertEquals(SourceVerificationResult.Unavailable,
            verifier(SourceAttestationResult.Unavailable).verify(tenant, id, sourceId, 1))
        assertEquals(SourceVerificationResult.OutcomeUnknown,
            verifier(SourceAttestationResult.OutcomeUnknown).verify(tenant, id, sourceId, 1))
        assertEquals(1L, (store.get(tenant, id) as ReadResult.Found).stored.value.contentRevision)
        assertTrue(pinned().contexts.isEmpty())
    }

    @Test fun `old source attestation cannot pass a concurrent semantic content change`() {
        create()
        val attestor = TrustedSourceAttestor { _, _ ->
            assertEquals(MutationResult.Applied(2), ContextService(store).update(
                principal, id, 1, "update-1", "digest-1", ContextPatch(content = "new content")
            ))
            SourceAttestationResult.Verified(evidence())
        }
        val service = SourceVerificationService(store, attestor, audience, clock)
        assertEquals(SourceVerificationResult.Conflict(2),
            service.verify(tenant, id, sourceId, 1))
        assertEquals(VerificationState.UNVERIFIED,
            (store.get(tenant, id) as ReadResult.Found).stored.value.sources.single().verification)
        assertTrue(pinned().contexts.isEmpty())
    }

    @Test fun `bookkeeping only CAS conflict is safe to retry`() {
        create()
        val racing = object : ContextStorage by store {
            var once = true
            override fun compareAndSwap(tenantId: String, contextId: String,
                expectedStorageRevision: Long, value: ContextAggregate): WriteResult {
                if (once) {
                    once = false
                    assertTrue(ContextService(store).acknowledgeIndex(tenant, id, 1))
                }
                return store.compareAndSwap(tenantId, contextId, expectedStorageRevision, value)
            }
        }
        assertEquals(SourceVerificationResult.Applied(2),
            verifier(storage = racing).verify(tenant, id, sourceId, 1))
        assertEquals(listOf(id), pinned().contexts.map { it.id })
    }

    @Test fun `storage response lost never claims success`() {
        create()
        val unknown = object : ContextStorage by store {
            override fun compareAndSwap(tenantId: String, contextId: String,
                expectedStorageRevision: Long, value: ContextAggregate): WriteResult =
                WriteResult.OutcomeUnknown
        }
        assertEquals(SourceVerificationResult.OutcomeUnknown,
            verifier(storage = unknown).verify(tenant, id, sourceId, 1))
        assertTrue(pinned().contexts.isEmpty())
    }

    @Test fun `stale and invalid attestor results revoke prior verification`() {
        create()
        assertEquals(SourceVerificationResult.Applied(2),
            verifier().verify(tenant, id, sourceId, 1))
        assertEquals(SourceVerificationResult.Applied(3),
            verifier(SourceAttestationResult.Stale).verify(tenant, id, sourceId, 2))
        assertTrue(pinned().contexts.isEmpty())
        var stored = (store.get(tenant, id) as ReadResult.Found).stored.value
        assertEquals(VerificationState.STALE, stored.sources.single().verification)
        assertEquals(null, stored.sources.single().evidence)
        assertEquals(SourceVerificationResult.Applied(4),
            verifier(SourceAttestationResult.Invalid).verify(tenant, id, sourceId, 3))
        stored = (store.get(tenant, id) as ReadResult.Found).stored.value
        assertEquals(VerificationState.INVALID, stored.sources.single().verification)
        assertEquals(null, stored.sources.single().evidence)
    }

    @Test fun `source claim edit invalidates earlier verification and cannot preserve proof`() {
        create()
        assertEquals(SourceVerificationResult.Applied(2),
            verifier().verify(tenant, id, sourceId, 1))
        assertEquals(MutationResult.Applied(3),
            ContextService(store).update(principal, id, 2, "edit-1", "digest-edit",
                ContextPatch(summary = "Changed assertion")))
        assertTrue(pinned().contexts.isEmpty())
        val current = (store.get(tenant, id) as ReadResult.Found).stored.value
        assertEquals(VerificationState.UNVERIFIED, current.sources.single().verification)
        assertEquals(null, current.sources.single().evidence)
        assertEquals(SourceVerificationResult.Conflict(3),
            verifier().verify(tenant, id, sourceId, 1))
    }

    @Test fun `verified inferred source can confirm then revocation downgrades to proposed`() {
        create(trust = SourceTrust.AGENT_INFERRED, status = ContextStatus.PROPOSED)
        assertEquals(SourceVerificationResult.Applied(2),
            verifier().verify(tenant, id, sourceId, 1))
        assertEquals(MutationResult.Applied(3),
            ContextService(store).update(principal, id, 2, "confirm-1", "digest-confirm",
                ContextPatch(status = ContextStatus.CONFIRMED)))
        assertEquals(listOf(id), pinned().contexts.map { it.id })
        assertEquals(SourceVerificationResult.Applied(4),
            verifier(SourceAttestationResult.Invalid).verify(tenant, id, sourceId, 3))
        val current = (store.get(tenant, id) as ReadResult.Found).stored.value
        assertEquals(ContextStatus.PROPOSED, current.status)
        assertEquals(VerificationState.INVALID, current.sources.single().verification)
        assertTrue(pinned().contexts.isEmpty())
    }

    @Test fun `deleted, wrong tenant and changed version cannot be verified`() {
        create()
        assertEquals(SourceVerificationResult.NotFound,
            verifier().verify("tenant-b", id, sourceId, 1))
        assertEquals(SourceVerificationResult.Conflict(1),
            verifier().verify(tenant, id, sourceId, 5))
        assertEquals(MutationResult.Applied(2),
            ContextService(store).delete(principal, id, 1, "delete-1", "digest-delete"))
        assertEquals(SourceVerificationResult.NotFound,
            verifier().verify(tenant, id, sourceId, 2))
    }

    @Test fun `unavailable authoritative read fails closed`() {
        create()
        val unavailable = object : ContextStorage by store {
            override fun get(tenantId: String, contextId: String): ReadResult = ReadResult.Unavailable
        }
        assertEquals(SourceVerificationResult.Unavailable,
            verifier(storage = unavailable).verify(tenant, id, sourceId, 1))
    }
}
