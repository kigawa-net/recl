package net.kigawa.recl.storage.memory

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinnedGrantReadAdmissionTest {
    private val now = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val principal = Principal("tenant-a", "owner-a")
    private val digest = "sha256:" + "b".repeat(64)
    private val uri = "https://github.com/kigawa-net/recl/commit/" + "a".repeat(40)
    private val sourceId = "src-1"
    private val grant = SourceGrantFence(principal.tenantId, principal.id,
        "kigawa-net", "recl", repositoryId = 4321, installationId = 9876,
        generation = 7, expiresAtEpochMillis = now + 600_000)
    private val store = InMemoryContextStorage(clock)

    private fun id(n: Int): String =
        "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a" + n.toString(16).padStart(2, '0')

    private fun add(n: Int, sources: Boolean = true, owner: String = principal.id): String {
        val contextId = id(n)
        val citation = SourceSubmission(sourceId, SourceType.GITHUB_COMMIT, uri,
            contentDigest = digest, trust = SourceTrust.PRIMARY_SOURCE).unverified()
        assertTrue(store.createIfAbsent(ContextAggregate(
            principal.tenantId, contextId, owner, ContextKind.FINDING,
            ContextStatus.CONFIRMED, "Title", "Summary", "content",
            setOf("project:recl"), pinned = true,
            sources = if (sources) listOf(citation) else emptyList()
        )) is WriteResult.Applied)
        return contextId
    }

    private fun verify(contextId: String, fence: SourceGrantFence? = grant): SourceVerificationResult {
        val evidence = VerificationEvidence(principal.tenantId, contextId, sourceId, uri,
            digest, 1, "github-app", "recl-source-verifier", "attestation-1",
            now, now + 120_000, sourceGrantFence = fence)
        return SourceVerificationService(store,
            TrustedSourceAttestor { _, _ -> SourceAttestationResult.Verified(evidence) },
            "recl-source-verifier", clock,
            requireGrantFenceForVerified = true, fencedCommit = store)
            .verify(principal.tenantId, contextId, sourceId, 1)
    }

    private val query = PinnedQuery(setOf("project:recl"), 100, 10)

    private fun secured(
        admission: TrustedPinnedReadAdmission? = SourceGrantReadAdmission(store),
        at: Clock = clock,
        storage: ContextStorage = store,
    ): PinnedContextReader = PinnedContextReader(storage, ContextTokenCounter { 1 },
        clock = at, readAdmission = admission, requireReadAdmission = true)

    @Test fun `current grant epoch admits verified source and preserves token budget`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        val result = secured().list(principal, query) as PinnedContextResult.Found
        assertEquals(listOf(i), result.contexts.map { it.id })
        assertEquals(1, result.usedTokens)
        assertEquals(VerificationState.VERIFIED, result.contexts.single().sources.single().verification)
    }

    @Test fun `revocation after verified commit excludes pinned even before evidence TTL expires`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        store.revokeGrantForTestOnly(grant)
        val result = secured().list(principal, query) as PinnedContextResult.Found
        assertEquals(emptyList<String>(), result.contexts.map { it.id })
        assertEquals(0, result.usedTokens)
    }

    @Test fun `new generation and installation for same repository rejects old verified source`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        store.installGrantForTestOnly(grant.copy(generation = 8, installationId = 9999))
        val found = secured().list(principal, query) as PinnedContextResult.Found
        assertEquals(emptyList<String>(), found.contexts.map { it.id })
    }

    @Test fun `principal owner and tenant cannot borrow another users grant`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        assertEquals(emptyList<String>(), (secured().list(Principal("tenant-a", "another"), query)
            as PinnedContextResult.Found).contexts.map { it.id })
        assertEquals(emptyList<String>(), (secured().list(Principal("tenant-b", principal.id), query)
            as PinnedContextResult.Found).contexts.map { it.id })
    }

    @Test fun `explicit admission capability is mandatory and missing one is unsupported`() {
        add(1)
        assertEquals(PinnedContextResult.Unsupported,
            secured(admission = null).list(principal, query))
    }

    @Test fun `source free legacy Context has no assumed repository grant`() {
        add(1, sources = false)
        assertEquals(PinnedContextResult.Unsupported, secured().list(principal, query))
    }

    @Test fun `legacy verified Source without grant epoch is not auto authorized`() {
        val i = add(1)
        // The internal non-production adapter can synthesize an older verification record.
        val source = (store.get(principal.tenantId, i) as ReadResult.Found).stored.value.sources.single()
        val oldProof = VerificationEvidence(principal.tenantId, i, sourceId, uri,
            digest, 1, "fake", "recl-source-verifier", "legacy-proof", now, now + 60_000)
        val before = (store.get(principal.tenantId, i) as ReadResult.Found).stored
        assertTrue(store.compareAndSwap(principal.tenantId, i, before.storageRevision,
            before.value.copy(sources = listOf(source.copy(
                verification = VerificationState.VERIFIED, evidence = oldProof)),
                contentRevision = 2)) is WriteResult.Applied)
        assertEquals(PinnedContextResult.Unsupported, secured().list(principal, query))
    }

    @Test fun `missing live grant reader capability blocks entire result`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        val notSupported = SourceGrantReadAdmission(TrustedGrantEpochReader { _, _ ->
            GrantEpochReadResult.Unsupported
        })
        assertEquals(PinnedContextResult.Unsupported,
            secured(admission = notSupported).list(principal, query))
    }

    @Test fun `quorum loss in grant read returns unavailable never an empty successful list`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        val unavailable = SourceGrantReadAdmission(TrustedGrantEpochReader { _, _ ->
            GrantEpochReadResult.Unavailable
        })
        assertEquals(PinnedContextResult.Unavailable,
            secured(admission = unavailable).list(principal, query))
    }

    @Test fun `revocation between selection and final admission returns unavailable`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        var admissionChecks = 0
        val racing = SourceGrantReadAdmission(TrustedGrantEpochReader { principal, fence ->
            admissionChecks++
            if (admissionChecks == 2) store.revokeGrantForTestOnly(grant)
            store.check(principal, fence)
        })
        assertEquals(PinnedContextResult.Unavailable,
            secured(admission = racing).list(principal, query))
        assertEquals(2, admissionChecks)
    }

    @Test fun `semantic mutation during tokenization invalidates selected snapshot`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        var changed = false
        val counter = ContextTokenCounter {
            if (!changed) {
                changed = true
                val previous = (store.get(principal.tenantId, i) as ReadResult.Found).stored
                assertTrue(store.compareAndSwap(principal.tenantId, i,
                    previous.storageRevision, previous.value.copy(title = "new title",
                        contentRevision = previous.value.contentRevision + 1)
                ) is WriteResult.Applied)
            }
            1
        }
        val reader = PinnedContextReader(store, counter, clock = clock,
            readAdmission = SourceGrantReadAdmission(store), requireReadAdmission = true)
        assertEquals(PinnedContextResult.Unavailable, reader.list(principal, query))
    }

    @Test fun `expired verification is not admitted even when repo grant is still current`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        val later = Clock.offset(clock, Duration.ofMinutes(3))
        assertEquals(emptyList<String>(),
            (secured(at = later).list(principal, query) as PinnedContextResult.Found)
                .contexts.map { it.id })
    }

    @Test fun `authoritative re-read outage returns unavailable and leaks no earlier contents`() {
        store.installGrantForTestOnly(grant)
        val i = add(1)
        assertEquals(SourceVerificationResult.Applied(2), verify(i))
        var reads = 0
        val failing = object : ContextStorage by store {
            override fun get(tenantId: String, id: String): ReadResult {
                reads++
                return if (reads >= 2) ReadResult.Unavailable else store.get(tenantId, id)
            }
        }
        assertEquals(PinnedContextResult.Unavailable,
            secured(storage = failing).list(principal, query))
    }
}
