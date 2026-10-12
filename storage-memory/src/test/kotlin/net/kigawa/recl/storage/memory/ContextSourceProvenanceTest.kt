package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextSourceProvenanceTest {
    private val tenant = "tenant-a"
    private val owner = Principal(tenant, "owner-1")
    private val ctxId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a61"
    private val createId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a62"
    private val updateId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a63"
    private val sha = "a".repeat(40)
    private val digest = "a".repeat(64)
    private val store = InMemoryContextStorage()
    private val service = ContextService(store)
    private val gateway = TrustedMutationGateway(service,
        SessionAuthenticator { if (it == "verified-owner") owner else null })

    private fun source(
        sourceId: String = "src-1",
        type: SourceType = SourceType.GITHUB_COMMIT,
        uri: String = "https://github.com/Kigawa-Net/Recl/commit/$sha",
        trust: SourceTrust = SourceTrust.PRIMARY_SOURCE,
    ) = SourceSubmission(sourceId, type, uri, "file.kt:42", 1_700_000_000_000L,
        "sha256:$digest", trust)

    private fun create(
        sources: List<SourceSubmission>,
        status: ContextStatus = ContextStatus.CONFIRMED,
    ) = CreateContextMutation(ctxId, createId, ContextKind.FINDING,
        status, "Finding", "Summary", "Content", setOf("project:recl"),
        pinned = true, sources = sources)

    @Test fun `immutable GitHub commit reference retains fixed SHA`() {
        val canonical = source().normalized()
        assertEquals("https://github.com/kigawa-net/recl/commit/$sha", canonical.uri)
        assertEquals(VerificationState.UNVERIFIED, canonical.unverified().verification)
        assertThrows(IllegalArgumentException::class.java) {
            source(uri = "https://github.com/kigawa-net/recl/tree/main").normalized()
        }
        assertThrows(IllegalArgumentException::class.java) {
            source(uri = "https://github.com/kigawa-net/recl/commit/main").normalized()
        }
    }

    @Test fun `mutable GitHub issue and PR references are not automatically verified`() {
        val issue = source("issue", SourceType.GITHUB_ISSUE,
            "https://github.com/kigawa-net/recl/issues/30").unverified()
        val pr = source("pr", SourceType.GITHUB_PR,
            "https://github.com/kigawa-net/recl/pull/29").unverified()
        assertEquals(VerificationState.UNVERIFIED, issue.verification)
        assertEquals(VerificationState.UNVERIFIED, pr.verification)
    }

    @Test fun `source input has no client verification field and trusted create records unverified`() {
        assertFalse(SourceSubmission::class.java.declaredFields.any { it.name == "verification" })
        assertEquals(MutationResult.Applied(1), gateway.create("verified-owner", create(listOf(source()))))
        val stored = (store.get(tenant, ctxId) as ReadResult.Found).stored.value
        assertEquals(VerificationState.UNVERIFIED, stored.sources.single().verification)
        assertEquals(SourceTrust.PRIMARY_SOURCE, stored.sources.single().trust)
        assertEquals(VerificationState.UNVERIFIED,
            (ContextReader(store).get(owner, ctxId) as ContextReadResult.Found).context.sources.single().verification)
    }

    @Test fun `credential bearing and ambiguous URLs never enter storage`() {
        val invalid = listOf(
            "http://example.com/public",
            "https://user:secret@example.com/report",
            "https://example.com/report?token=secret",
            "https://example.com/report#access_token=secret",
            "https://example.com/%3Ftoken=secret",
            "https://example.com/token/value",
            "https://example.com/a/../secret",
            "https://localhost/internal",
            "https://192.168.1.3/internal",
            "https://example.com:8443/report",
            "https://example.com/" + "x".repeat(2100),
        )
        for ((index, uri) in invalid.withIndex()) {
            val candidate = source("ref-$index", SourceType.URL, uri)
            assertEquals(MutationResult.InvalidRequest,
                gateway.create("verified-owner", create(listOf(candidate))))
        }
        assertEquals(ReadResult.NotFound, store.get(tenant, ctxId))
    }

    @Test fun `oversized locator and duplicate source IDs are rejected without writes`() {
        val duplicate = listOf(source(), source(uri =
            "https://github.com/kigawa-net/recl/commit/" + "b".repeat(40)))
        assertEquals(MutationResult.InvalidRequest,
            gateway.create("verified-owner", create(duplicate)))
        assertEquals(MutationResult.InvalidRequest,
            gateway.create("verified-owner", create(listOf(
                source().copy(locator = "x".repeat(513))
            ))))
        assertEquals(ReadResult.NotFound, store.get(tenant, ctxId))
    }

    @Test fun `repository file and exkes artifacts use immutable internal locators`() {
        val file = source("file", SourceType.FILE,
            "repo://kigawa-net/recl/$sha/src/main.kt").normalized()
        val artifact = source("artifact", SourceType.EXKES_ARTIFACT,
            "exkes://staging/exec-1/artifact/$digest").normalized()
        assertEquals("repo://kigawa-net/recl/$sha/src/main.kt", file.uri)
        assertEquals("exkes://staging/exec-1/artifact/$digest", artifact.uri)
        assertThrows(IllegalArgumentException::class.java) {
            source("bad", SourceType.EXKES_ARTIFACT,
                "https://s3.example.com/download?X-Amz-Signature=secret").normalized()
        }
        assertThrows(IllegalArgumentException::class.java) {
            source("bad", SourceType.FILE, "file:///home/agent/.ssh/id_rsa").normalized()
        }
    }

    @Test fun `unverified inferred evidence cannot become confirmed Context`() {
        val inferred = source(trust = SourceTrust.AGENT_INFERRED)
        assertEquals(MutationResult.InvalidRequest,
            gateway.create("verified-owner", create(listOf(inferred))))
        assertEquals(MutationResult.Applied(1),
            gateway.create("verified-owner", create(listOf(inferred), ContextStatus.PROPOSED)))
        val result = gateway.update("verified-owner",
            UpdateContextMutation(ctxId, updateId, 1, ContextPatch(status = ContextStatus.CONFIRMED)))
        assertEquals(MutationResult.InvalidRequest, result)
        val stored = (store.get(tenant, ctxId) as ReadResult.Found).stored.value
        assertEquals(ContextStatus.PROPOSED, stored.status)
        assertEquals(1L, stored.contentRevision)
    }

    @Test fun `provenance change is semantic CAS and replay identity includes source fields`() {
        val first = source(sourceId = "a")
        val second = source(sourceId = "b", type = SourceType.GITHUB_PR,
            uri = "https://github.com/kigawa-net/recl/pull/29")
        assertEquals(MutationResult.Applied(1),
            gateway.create("verified-owner", create(listOf(second, first))))
        // Citation input ordering is not semantic.
        assertEquals(MutationResult.Applied(1, replayed = true),
            gateway.create("verified-owner", create(listOf(first, second))))
        assertEquals(MutationResult.RequestIdReused,
            gateway.create("verified-owner", create(listOf(first.copy(locator = "file.kt:43"), second))))
        val next = SourceSubmission("a", SourceType.URL,
            "https://example.com/report", trust = SourceTrust.USER_STATED)
        val patch = UpdateContextMutation(ctxId, updateId, 1, ContextPatch(sources = listOf(next)))
        assertEquals(MutationResult.Applied(2), gateway.update("verified-owner", patch))
        assertEquals(MutationResult.Applied(2, replayed = true), gateway.update("verified-owner", patch))
        assertEquals(MutationResult.RequestIdReused,
            gateway.update("verified-owner", patch.copy(patch =
                ContextPatch(sources = listOf(next.copy(uri = "https://example.com/other"))))))
        val stored = (store.get(tenant, ctxId) as ReadResult.Found).stored.value
        assertEquals(2L, stored.contentRevision)
        assertEquals(IndexAction.UPSERT, stored.indexAction)
        assertEquals("https://example.com/report", stored.sources.single().uri)
    }

    @Test fun `pinned injection excludes unverified primary and inferred claims`() {
        val primary = source()
        val result = gateway.create("verified-owner", create(listOf(primary)))
        assertEquals(MutationResult.Applied(1), result)
        val pin = PinnedContextReader(store, ContextTokenCounter { 1 })
        val q = PinnedQuery(setOf("project:recl"), 10, 5)
        assertEquals(emptyList<String>(),
            (pin.list(owner, q) as PinnedContextResult.Found).contexts.map { it.id })
        // Only an internal trusted verifier may change a stored source's verification.
        val before = (store.get(tenant, ctxId) as ReadResult.Found).stored
        assertTrue(store.compareAndSwap(tenant, ctxId, before.storageRevision,
            before.value.copy(
                sources = before.value.sources.map { it.copy(verification = VerificationState.VERIFIED) },
                contentRevision = before.value.contentRevision + 1)
            ) is WriteResult.Applied)
        val pinned = pin.list(owner, q) as PinnedContextResult.Found
        assertEquals(listOf(ctxId), pinned.contexts.map { it.id })
        assertEquals(VerificationState.VERIFIED, pinned.contexts.single().sources.single().verification)
    }

    @Test fun `in memory persistence detaches caller owned source lists`() {
        val input = mutableListOf(source().unverified())
        val ctx = ContextAggregate(tenant, ctxId, owner.id, ContextKind.FINDING,
            ContextStatus.PROPOSED, "T", "S", "C", sources = input)
        assertTrue(store.createIfAbsent(ctx) is WriteResult.Applied)
        input.clear()
        val after = (store.get(tenant, ctxId) as ReadResult.Found).stored.value
        assertEquals(1, after.sources.size)
    }

    @Test fun `unknown source type cannot be used as a verification bypass`() {
        assertEquals(SourceType.entries.map { it.name }.toSet(), setOf(
            "GITHUB_COMMIT", "GITHUB_PR", "GITHUB_ISSUE", "FILE",
            "EXKES_ARTIFACT", "EXKES_LOG", "FUSHA_TASK", "USER_MESSAGE", "URL"))
        assertEquals(SourceTrust.entries.map { it.name }.toSet(), setOf(
            "USER_STATED", "PRIMARY_SOURCE", "AGENT_OBSERVED", "AGENT_INFERRED"))
    }

    @Test fun `user stated label cannot bypass independent source verification`() {
        val claimed = source(trust = SourceTrust.USER_STATED)
        assertEquals(MutationResult.Applied(1),
            gateway.create("verified-owner", create(listOf(claimed))))
        val direct = ContextReader(store).get(owner, ctxId) as ContextReadResult.Found
        assertEquals(SourceTrust.USER_STATED, direct.context.sources.single().trust)
        assertEquals(VerificationState.UNVERIFIED, direct.context.sources.single().verification)
        val pinned = PinnedContextReader(store, ContextTokenCounter { 1 })
            .list(owner, PinnedQuery(setOf("project:recl"), 10, 10)) as PinnedContextResult.Found
        assertEquals(emptyList<String>(), pinned.contexts.map { it.id })
    }
}
