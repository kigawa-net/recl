package net.kigawa.recl.storage.memory

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptOutputBarrierTest {
    private class AdjustableClock(var value: Instant = Instant.parse("2026-10-12T09:00:00Z")) : Clock() {
        override fun instant(): Instant = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val clock = AdjustableClock()
    private val now get() = clock.millis()
    private val principal = Principal("tenant-a", "owner-a")
    private val digest = "sha256:" + "b".repeat(64)
    private val uri = "https://github.com/kigawa-net/recl/commit/" + "a".repeat(40)
    private val fence = SourceGrantFence(
        principal.tenantId, principal.id, "kigawa-net", "recl",
        repositoryId = 4001, installationId = 8001, generation = 5,
        expiresAtEpochMillis = now + 600_000,
    )
    private val store = InMemoryContextStorage(clock)
    private val tokens = ContextTokenCounter { 1 }
    private val query = PinnedQuery(setOf("project:recl"), tokenBudget = 20, maxContexts = 10)
    private val destination = PromptDestination("model-gateway", "test-model", "op-1")
    private fun id(n: Int) =
        "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a" + n.toString(16).padStart(2, '0')

    private fun add(n: Int = 1, withSource: Boolean = true): String {
        val contextId = id(n)
        val ref = SourceSubmission("source-$n", SourceType.GITHUB_COMMIT, uri,
            contentDigest = digest, trust = SourceTrust.PRIMARY_SOURCE).unverified()
        assertTrue(store.createIfAbsent(ContextAggregate(
            principal.tenantId, contextId, principal.id,
            ContextKind.FINDING, ContextStatus.CONFIRMED,
            "title$n", "summary$n", "content$n", setOf("project:recl"),
            pinned = true, sources = if (withSource) listOf(ref) else emptyList(),
        )) is WriteResult.Applied)
        if (withSource) {
            val proof = VerificationEvidence(
                principal.tenantId, contextId, ref.sourceId, uri, digest, 1,
                "github-app", "recl-source-verifier", "attestation-$n",
                now, now + 120_000, sourceGrantFence = fence,
            )
            val verifier = SourceVerificationService(
                store, TrustedSourceAttestor { _, _ -> SourceAttestationResult.Verified(proof) },
                "recl-source-verifier", clock,
                requireGrantFenceForVerified = true, fencedCommit = store,
            )
            assertEquals(SourceVerificationResult.Applied(2),
                verifier.verify(principal.tenantId, contextId, ref.sourceId, 1))
        }
        return contextId
    }

    private fun selection(): PinnedContextResult.Found =
        PinnedContextReader(
            store, tokens, clock = clock,
            readAdmission = SourceGrantReadAdmission(store), requireReadAdmission = true
        ).list(principal, query) as PinnedContextResult.Found

    private fun request(found: PinnedContextResult.Found = selection(),
        actor: Principal = principal
    ) = PromptOutputRequest(actor, destination, query, found)

    private fun service() = TrustedPromptOutputService(store.promptBarrierForTestOnly(tokens))

    @Test fun `missing trusted output authority never silently sends existing selected context`() {
        store.installGrantForTestOnly(fence)
        add()
        var calls = 0
        assertEquals(PromptOutputResult.Unsupported,
            TrustedPromptOutputService().dispatch(request(), TrustedPromptSink { _, _ ->
                calls++
                TrustedPromptSendResult.Accepted
            }))
        assertEquals(0, calls)
    }

    @Test fun `current trusted epoch permits exactly selected snapshot and bound destination`() {
        store.installGrantForTestOnly(fence)
        val id = add()
        val req = request()
        var calls = 0
        val result = service().dispatch(req, TrustedPromptSink { dest, contexts ->
            calls++
            assertEquals(destination, dest)
            assertEquals(listOf(id), contexts.map { it.id })
            assertEquals(req.selection.contexts, contexts)
            TrustedPromptSendResult.Accepted
        })
        assertEquals(PromptOutputResult.Accepted, result)
        assertEquals(1, calls)
    }

    @Test fun `revocation after read list before send start blocks without invoking sink`() {
        store.installGrantForTestOnly(fence)
        add()
        val req = request()
        store.revokeGrantForTestOnly(fence)
        var calls = 0
        assertEquals(PromptOutputResult.Forbidden,
            service().dispatch(req, TrustedPromptSink { _, _ ->
                calls++
                TrustedPromptSendResult.Accepted
            }))
        assertEquals(0, calls)
    }

    @Test fun `regrant under same repository name and changed installation epoch blocks old selection`() {
        store.installGrantForTestOnly(fence)
        add()
        val req = request()
        store.installGrantForTestOnly(fence.copy(generation = 6, installationId = 9001))
        assertEquals(PromptOutputResult.Forbidden,
            service().dispatch(req, TrustedPromptSink { _, _ ->
                error("must not emit old grant")
            }))
    }

    @Test fun `modified content or source revision cannot replay an old admission`() {
        store.installGrantForTestOnly(fence)
        val id = add()
        val req = request()
        val old = (store.get(principal.tenantId, id) as ReadResult.Found).stored
        assertTrue(store.compareAndSwap(principal.tenantId, id, old.storageRevision,
            old.value.copy(content = "changed after list", contentRevision = 3)
        ) is WriteResult.Applied)
        assertEquals(PromptOutputResult.Forbidden,
            service().dispatch(req, TrustedPromptSink { _, _ -> error("must not send") }))
    }

    @Test fun `all selected contexts must still be authorized or none are emitted`() {
        store.installGrantForTestOnly(fence)
        add(1)
        val second = add(2)
        val req = request()
        assertEquals(2, req.selection.contexts.size)
        val old = (store.get(principal.tenantId, second) as ReadResult.Found).stored
        assertTrue(store.compareAndSwap(principal.tenantId, second, old.storageRevision,
            old.value.copy(deleted = true, contentRevision = 3)) is WriteResult.Applied)
        var calls = 0
        assertEquals(PromptOutputResult.Forbidden,
            service().dispatch(req, TrustedPromptSink { _, _ ->
                calls++
                TrustedPromptSendResult.Accepted
            }))
        assertEquals(0, calls)
    }

    @Test fun `tampered token cost, duplicate snapshots and oversized budget are rejected`() {
        store.installGrantForTestOnly(fence)
        add()
        val req = request()
        val tampered = req.copy(selection = req.selection.copy(usedTokens = 0))
        assertEquals(PromptOutputResult.InvalidRequest,
            service().dispatch(tampered, TrustedPromptSink { _, _ -> error("must not send") }))
        val duplicate = req.copy(selection = req.selection.copy(contexts =
            listOf(req.selection.contexts.single(), req.selection.contexts.single())))
        assertEquals(PromptOutputResult.InvalidRequest,
            service().dispatch(duplicate, TrustedPromptSink { _, _ -> error("must not send") }))
        val noBudget = req.copy(query = req.query.copy(tokenBudget = 0))
        assertEquals(PromptOutputResult.InvalidRequest,
            service().dispatch(noBudget, TrustedPromptSink { _, _ -> error("must not send") }))
    }

    @Test fun `source free legacy record cannot borrow another source repository permission`() {
        store.installGrantForTestOnly(fence)
        add(withSource = false)
        val stored = (store.get(principal.tenantId, id(1)) as ReadResult.Found).stored.value
        val view = ContextSnapshot(stored.id, stored.kind, stored.status, stored.title,
            stored.summary, stored.content, stored.scopes, stored.contentRevision)
        val req = PromptOutputRequest(principal, destination, query,
            PinnedContextResult.Found(listOf(view), 1, false))
        assertEquals(PromptOutputResult.Unsupported,
            service().dispatch(req, TrustedPromptSink { _, _ -> error("must not send") }))
    }

    @Test fun `expired evidence after selection blocks output even if grant is still alive`() {
        store.installGrantForTestOnly(fence)
        add()
        val req = request()
        clock.value = clock.value.plusSeconds(180)
        assertEquals(PromptOutputResult.Forbidden,
            service().dispatch(req, TrustedPromptSink { _, _ -> error("must not send") }))
    }

    @Test fun `different tenant or owner does not reuse a prior admission`() {
        store.installGrantForTestOnly(fence)
        add()
        val found = selection()
        for (actor in listOf(Principal(principal.tenantId, "another"),
            Principal("tenant-other", principal.id))) {
            assertEquals(PromptOutputResult.Forbidden,
                service().dispatch(request(found, actor),
                    TrustedPromptSink { _, _ -> error("must not send") }))
        }
    }

    @Test fun `send result UNKNOWN or transport exception never proves no effect or retries`() {
        store.installGrantForTestOnly(fence)
        add()
        val req = request()
        var calls = 0
        assertEquals(PromptOutputResult.OutcomeUnknown,
            service().dispatch(req, TrustedPromptSink { _, _ ->
                calls++
                TrustedPromptSendResult.OutcomeUnknown
            }))
        assertEquals(PromptOutputResult.OutcomeUnknown,
            service().dispatch(req, TrustedPromptSink { _, _ ->
                calls++
                throw IllegalStateException("response lost after sending")
            }))
        // Independent test invocations are explicit operator decisions, not automatic retry.
        assertEquals(2, calls)
    }

    @Test fun `trusted transport can explicitly prove not sent without calling it accepted`() {
        store.installGrantForTestOnly(fence)
        add()
        assertEquals(PromptOutputResult.NotSent,
            service().dispatch(request(), TrustedPromptSink { _, _ ->
                TrustedPromptSendResult.DefinitelyNotSent
            }))
    }

    @Test fun `single process barrier orders external send start before concurrent revoke`() {
        store.installGrantForTestOnly(fence)
        add()
        val req = request()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        var sendResult: PromptOutputResult? = null
        val sender = thread(name = "test-send") {
            sendResult = service().dispatch(req, TrustedPromptSink { _, _ ->
                entered.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                TrustedPromptSendResult.Accepted
            })
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val revoker = thread(name = "test-revoke") {
            revokeStarted.countDown()
            store.revokeGrantForTestOnly(fence)
            revoked.countDown()
        }
        try {
            assertTrue(revokeStarted.await(3, TimeUnit.SECONDS))
            assertFalse(revoked.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            sender.join(5000)
            revoker.join(5000)
        }
        assertFalse(sender.isAlive)
        assertFalse(revoker.isAlive)
        assertEquals(PromptOutputResult.Accepted, sendResult)
        assertTrue(revoked.count == 0L)
        assertEquals(PromptOutputResult.Forbidden,
            service().dispatch(req, TrustedPromptSink { _, _ -> error("revoked grant") }))
    }
}
