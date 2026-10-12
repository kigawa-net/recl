package net.kigawa.recl.storage.memory

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptDispatchLeaseTest {
    private class MutableClock(
        var value: Instant = Instant.parse("2026-10-12T09:00:00Z")
    ) : Clock() {
        override fun instant(): Instant = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val clock = MutableClock()
    private val now get() = clock.millis()
    private val principal = Principal("tenant-a", "owner-a")
    private val sha = "a".repeat(40)
    private val digest = "sha256:" + "b".repeat(64)
    private val uri = "https://github.com/kigawa-net/recl/commit/$sha"
    private val fence = SourceGrantFence(principal.tenantId, principal.id,
        "kigawa-net", "recl", 42, 88, 7, now + 600_000)
    private val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a91"
    private val store = InMemoryContextStorage(clock)
    private val counter = ContextTokenCounter { 1 }
    private val journal by lazy { InMemoryPromptSendStartJournalForTestOnly(store, counter, clock) }
    private val destination = PromptDestination("model-gateway", "test-model", "op-1")
    private val query = PinnedQuery(setOf("project:recl"), 10, 10)

    private fun setup() {
        store.installGrantForTestOnly(fence)
        val citation = SourceSubmission("src-1", SourceType.GITHUB_COMMIT, uri,
            contentDigest = digest, trust = SourceTrust.PRIMARY_SOURCE).unverified()
        assertTrue(store.createIfAbsent(ContextAggregate(
            principal.tenantId, contextId, principal.id,
            ContextKind.FINDING, ContextStatus.CONFIRMED,
            "Title", "Summary", "Content", setOf("project:recl"), pinned = true,
            sources = listOf(citation)
        )) is WriteResult.Applied)
        val evidence = VerificationEvidence(principal.tenantId, contextId,
            citation.sourceId, citation.uri, digest, 1, "github-app",
            "recl-source-verifier", "evidence-1", now, now + 120_000,
            sourceGrantFence = fence)
        assertEquals(SourceVerificationResult.Applied(2), SourceVerificationService(
            store, TrustedSourceAttestor { _, _ -> SourceAttestationResult.Verified(evidence) },
            "recl-source-verifier", clock, requireGrantFenceForVerified = true,
            fencedCommit = store
        ).verify(principal.tenantId, contextId, citation.sourceId, 1))
    }

    private fun request(
        destination: PromptDestination = this.destination,
    ): PromptOutputRequest {
        val selected = PinnedContextReader(store, counter, clock = clock,
            readAdmission = SourceGrantReadAdmission(store), requireReadAdmission = true
        ).list(principal, query) as PinnedContextResult.Found
        return PromptOutputRequest(principal, destination, query, selected)
    }

    @Test fun `missing journal is unsupported and calls no sink`() {
        setup()
        var called = false
        assertEquals(PromptOutputResult.Unsupported,
            TrustedPromptLeaseDispatcher(clock = clock).dispatch(
                request(), TrustedLeaseBoundPromptSink { _, _ ->
                    called = true
                    TrustedPromptSendResult.Accepted
                }))
        assertFalse(called)
    }

    @Test fun `issued lease is bound to destination and settles exactly once`() {
        setup()
        val req = request()
        val expectedHash = PromptDispatchBinding.digest(req)
        var called = 0
        val result = TrustedPromptLeaseDispatcher(journal, clock).dispatch(
            req, TrustedLeaseBoundPromptSink { permit, contexts ->
                called++
                assertEquals(expectedHash, permit.requestDigest)
                assertEquals(destination, permit.destination)
                assertEquals(principal.tenantId, permit.tenantId)
                assertTrue(permit.expiresAtEpochMillis - permit.startedAtEpochMillis <= 2_000)
                assertEquals(req.selection.contexts, contexts)
                TrustedPromptSendResult.Accepted
            })
        assertEquals(PromptOutputResult.Accepted, result)
        assertEquals(1, called)
        assertEquals(TrustedPromptSendResult.Accepted,
            journal.observedOutcomeForTestOnly(principal.tenantId, principal.id, destination.operationId))
    }

    @Test fun `same operation never dispatches twice even after accepted`() {
        setup()
        val req = request()
        val calls = AtomicInteger()
        val sink = TrustedLeaseBoundPromptSink { _, _ ->
            calls.incrementAndGet()
            TrustedPromptSendResult.Accepted
        }
        val dispatcher = TrustedPromptLeaseDispatcher(journal, clock)
        assertEquals(PromptOutputResult.Accepted, dispatcher.dispatch(req, sink))
        assertEquals(PromptOutputResult.OutcomeUnknown, dispatcher.dispatch(req, sink))
        assertEquals(1, calls.get())
    }

    @Test fun `same operation changed model or content binding cannot reuse receipt`() {
        setup()
        val req = request()
        val selected = req.selection
        val other = req.copy(destination = destination.copy(model = "other-model"))
        assertNotEquals(PromptDispatchBinding.digest(req), PromptDispatchBinding.digest(other))
        assertNotEquals(PromptDispatchBinding.digest(req),
            PromptDispatchBinding.digest(req.copy(selection = selected.copy(
                contexts = selected.contexts.map { it.copy(content = "changed") }))))
        val dispatcher = TrustedPromptLeaseDispatcher(journal, clock)
        assertEquals(PromptOutputResult.Accepted, dispatcher.dispatch(req,
            TrustedLeaseBoundPromptSink { _, _ -> TrustedPromptSendResult.Accepted }))
        assertEquals(PromptOutputResult.InvalidRequest, dispatcher.dispatch(other,
            TrustedLeaseBoundPromptSink { _, _ -> error("should not send") }))
    }

    @Test fun `grant revoke after pinned selection before tryStart blocks all sends`() {
        setup()
        val req = request()
        store.revokeGrantForTestOnly(fence)
        assertEquals(PromptOutputResult.Forbidden, TrustedPromptLeaseDispatcher(journal, clock)
            .dispatch(req, TrustedLeaseBoundPromptSink { _, _ -> error("should not send") }))
    }

    @Test fun `lease expires after journal start and before external sink`() {
        setup()
        val req = request()
        val clockChanging = object : TrustedPromptSendStartJournal {
            override fun tryStart(req: PromptOutputRequest, hash: String): PromptDispatchStartResult {
                val started = journal.tryStart(req, hash)
                clock.value = clock.value.plusSeconds(4)
                return started
            }
            override fun settle(
                permit: PromptSendStartPermit, outcome: TrustedPromptSendResult
            ): PromptDispatchSettlement = journal.settle(permit, outcome)
        }
        val out = TrustedPromptLeaseDispatcher(clockChanging, clock).dispatch(
            req, TrustedLeaseBoundPromptSink { _, _ -> error("expired") })
        assertEquals(PromptOutputResult.NotSent, out)
        assertEquals(TrustedPromptSendResult.DefinitelyNotSent,
            journal.observedOutcomeForTestOnly(principal.tenantId, principal.id, destination.operationId))
    }

    @Test fun `lost remote response remains unknown and cannot retry`() {
        setup()
        val req = request()
        val dispatcher = TrustedPromptLeaseDispatcher(journal, clock)
        var calls = 0
        val sink = TrustedLeaseBoundPromptSink { _, _ ->
            calls++
            throw IllegalStateException("response lost")
        }
        assertEquals(PromptOutputResult.OutcomeUnknown, dispatcher.dispatch(req, sink))
        assertEquals(PromptOutputResult.OutcomeUnknown, dispatcher.dispatch(req, sink))
        assertEquals(1, calls)
        assertEquals(TrustedPromptSendResult.OutcomeUnknown,
            journal.observedOutcomeForTestOnly(principal.tenantId, principal.id, destination.operationId))
    }

    @Test fun `lost journal settlement cannot downgrade a possibly sent request`() {
        setup()
        val req = request()
        val lost = object : TrustedPromptSendStartJournal by journal {
            override fun settle(
                permit: PromptSendStartPermit, outcome: TrustedPromptSendResult
            ): PromptDispatchSettlement = PromptDispatchSettlement.OutcomeUnknown
        }
        var calls = 0
        val out = TrustedPromptLeaseDispatcher(lost, clock).dispatch(req,
            TrustedLeaseBoundPromptSink { _, _ ->
                calls++
                TrustedPromptSendResult.Accepted
            })
        assertEquals(PromptOutputResult.OutcomeUnknown, out)
        assertEquals(PromptOutputResult.OutcomeUnknown,
            TrustedPromptLeaseDispatcher(journal, clock).dispatch(req,
                TrustedLeaseBoundPromptSink { _, _ -> error("no replay") }))
        assertEquals(1, calls)
    }

    @Test fun `unknown tryStart does not call transport`() {
        setup()
        val unknown = object : TrustedPromptSendStartJournal {
            override fun tryStart(
                request: PromptOutputRequest, requestDigest: String
            ): PromptDispatchStartResult = PromptDispatchStartResult.OutcomeUnknown
            override fun settle(
                permit: PromptSendStartPermit, outcome: TrustedPromptSendResult
            ): PromptDispatchSettlement = error("no settlement")
        }
        assertEquals(PromptOutputResult.OutcomeUnknown,
            TrustedPromptLeaseDispatcher(unknown, clock).dispatch(request(),
                TrustedLeaseBoundPromptSink { _, _ -> error("must not send") }))
    }

    @Test fun `forged permit and payload mismatch cannot settle somebody else's operation`() {
        setup()
        val req = request()
        val started = journal.tryStart(req, PromptDispatchBinding.digest(req)) as PromptDispatchStartResult.Started
        assertEquals(PromptDispatchSettlement.Rejected,
            journal.settle(started.permit.copy(principalId = "attacker"),
                TrustedPromptSendResult.Accepted))
        assertEquals(PromptDispatchSettlement.Rejected,
            journal.settle(started.permit.copy(permitId = "forged-permit"),
                TrustedPromptSendResult.Accepted))
        assertEquals(PromptDispatchSettlement.Recorded,
            journal.settle(started.permit, TrustedPromptSendResult.OutcomeUnknown))
    }

    @Test fun `tampered selected context and another principal cannot be emitted`() {
        setup()
        val req = request()
        val altered = req.copy(selection = req.selection.copy(
            contexts = req.selection.contexts.map { it.copy(content = "payload-changed") }))
        assertEquals(PromptOutputResult.Forbidden,
            TrustedPromptLeaseDispatcher(journal, clock).dispatch(altered,
                TrustedLeaseBoundPromptSink { _, _ -> error("forbidden") }))
        assertEquals(PromptOutputResult.Forbidden,
            TrustedPromptLeaseDispatcher(journal, clock).dispatch(
                req.copy(principal = Principal("tenant-b", principal.id)),
                TrustedLeaseBoundPromptSink { _, _ -> error("forbidden") }))
    }

    @Test fun `two racing dispatchers produce only one send start`() {
        setup()
        val req = request()
        val enter = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val dispatcher = TrustedPromptLeaseDispatcher(journal, clock)
        val sink = TrustedLeaseBoundPromptSink { _, _ ->
            count.incrementAndGet()
            enter.countDown()
            release.await(3, TimeUnit.SECONDS)
            TrustedPromptSendResult.Accepted
        }
        var first: PromptOutputResult? = null
        val worker = thread {
            first = dispatcher.dispatch(req, sink)
        }
        try {
            assertTrue(enter.await(3, TimeUnit.SECONDS))
            assertEquals(PromptOutputResult.OutcomeUnknown, dispatcher.dispatch(req, sink))
            assertEquals(1, count.get())
        } finally {
            release.countDown()
            worker.join(4_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(PromptOutputResult.Accepted, first)
    }

    @Test fun `a new ephemeral journal loses its receipt proving production persistence is missing`() {
        setup()
        val req = request()
        assertEquals(PromptOutputResult.Accepted, TrustedPromptLeaseDispatcher(journal, clock)
            .dispatch(req, TrustedLeaseBoundPromptSink { _, _ -> TrustedPromptSendResult.Accepted }))
        val processRestart = InMemoryPromptSendStartJournalForTestOnly(store, counter, clock)
        assertEquals(null, processRestart.observedOutcomeForTestOnly(
            principal.tenantId, principal.id, destination.operationId))
        // It must NEVER be used for production recovery, where this lost receipt would
        // make a duplicate remote POST possible.
    }
}
