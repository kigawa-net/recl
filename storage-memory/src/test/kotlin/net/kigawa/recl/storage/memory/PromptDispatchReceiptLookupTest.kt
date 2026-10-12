package net.kigawa.recl.storage.memory

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptDispatchReceiptLookupTest {
    private val now = Instant.parse("2026-10-12T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val principal = Principal("tenant-a", "owner-a")
    private val destination = PromptDestination("model-gateway", "model-a", "send-op-1")
    private val grant = SourceGrantFence(
        principal.tenantId, principal.id, "kigawa-net", "recl",
        repositoryId = 42, installationId = 99, generation = 7,
        expiresAtEpochMillis = now.toEpochMilli() + 60_000
    )
    private val uri = "https://github.com/kigawa-net/recl/commit/" + "a".repeat(40)
    private val digest = "sha256:" + "b".repeat(64)
    private val id = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a91"
    private val store = InMemoryContextStorage(clock)
    private val tokenCounter = ContextTokenCounter { 1 }
    private val journal = InMemoryPromptSendStartJournalForTestOnly(store, tokenCounter, clock)

    private fun prepare(): PromptOutputRequest {
        store.installGrantForTestOnly(grant)
        val source = SourceSubmission("src-1", SourceType.GITHUB_COMMIT, uri,
            contentDigest = digest, trust = SourceTrust.PRIMARY_SOURCE).unverified()
        assertTrue(store.createIfAbsent(ContextAggregate(
            principal.tenantId, id, principal.id, ContextKind.FINDING,
            ContextStatus.CONFIRMED, "t", "s", "c", setOf("project:recl"),
            pinned = true, sources = listOf(source)
        )) is WriteResult.Applied)
        val evidence = VerificationEvidence(
            principal.tenantId, id, "src-1", uri, digest, 1, "github-app",
            "recl-source-verifier", "receipt-1",
            now.toEpochMilli(), now.toEpochMilli() + 30_000,
            sourceGrantFence = grant)
        assertEquals(SourceVerificationResult.Applied(2), SourceVerificationService(
            store, TrustedSourceAttestor { _, _ -> SourceAttestationResult.Verified(evidence) },
            "recl-source-verifier", clock, requireGrantFenceForVerified = true,
            fencedCommit = store
        ).verify(principal.tenantId, id, "src-1", 1))
        val query = PinnedQuery(setOf("project:recl"), 10, 10)
        val selected = PinnedContextReader(
            store, tokenCounter, clock = clock,
            readAdmission = SourceGrantReadAdmission(store), requireReadAdmission = true,
        ).list(principal, query) as PinnedContextResult.Found
        assertEquals(1, selected.contexts.size)
        return PromptOutputRequest(principal, destination, query, selected)
    }

    private fun receiptQuery(request: PromptOutputRequest): PromptDispatchReceiptQuery =
        PromptDispatchReceiptQuery(request.principal, request.destination,
            PromptDispatchBinding.digest(request))

    @Test fun `no authoritative reader never reports receipt or authorizes replay`() {
        val request = prepare()
        assertEquals(PromptDispatchReconciliation.Unsupported,
            TrustedPromptDispatchReconciler().reconcile(receiptQuery(request)))
    }

    @Test fun `durable intent phase is observed as STARTED_PENDING not sent or completed`() {
        val request = prepare()
        val lookup = receiptQuery(request)
        val start = journal.tryStart(request, lookup.requestDigest)
        assertTrue(start is PromptDispatchStartResult.Started)
        assertEquals(PromptDispatchReconciliation.Observed(
            PromptDispatchReceiptState.STARTED_PENDING),
            TrustedPromptDispatchReconciler(journal).reconcile(lookup))
        val found = journal.lookup(lookup) as PromptDispatchReceiptRead.Found
        assertEquals(null, found.settledAtEpochMillis)
    }

    @Test fun `transport accepted is recorded but does not assert model completion`() {
        val request = prepare()
        val lookup = receiptQuery(request)
        val out = TrustedPromptLeaseDispatcher(journal, clock).dispatch(
            request, TrustedLeaseBoundPromptSink { _, _ ->
                TrustedPromptSendResult.Accepted
            })
        assertEquals(PromptOutputResult.Accepted, out)
        assertEquals(PromptDispatchReconciliation.Observed(
            PromptDispatchReceiptState.TRANSPORT_ACCEPTED),
            TrustedPromptDispatchReconciler(journal).reconcile(lookup))
    }

    @Test fun `provider timeout remains UNKNOWN across result lookup without retry`() {
        val request = prepare()
        val lookup = receiptQuery(request)
        var calls = 0
        val dispatcher = TrustedPromptLeaseDispatcher(journal, clock)
        assertEquals(PromptOutputResult.OutcomeUnknown, dispatcher.dispatch(
            request, TrustedLeaseBoundPromptSink { _, _ ->
                calls++
                TrustedPromptSendResult.OutcomeUnknown
            }))
        assertEquals(PromptDispatchReconciliation.Observed(
            PromptDispatchReceiptState.OUTCOME_UNKNOWN),
            TrustedPromptDispatchReconciler(journal).reconcile(lookup))
        assertEquals(PromptOutputResult.OutcomeUnknown, dispatcher.dispatch(
            request, TrustedLeaseBoundPromptSink { _, _ -> error("no automatic replay") }))
        assertEquals(1, calls)
    }

    @Test fun `trusted DefinitelyNotSent is observable but reconciling never starts a new send`() {
        val request = prepare()
        val lookup = receiptQuery(request)
        val started = journal.tryStart(request, lookup.requestDigest) as PromptDispatchStartResult.Started
        assertEquals(PromptDispatchSettlement.Recorded,
            journal.settle(started.permit, TrustedPromptSendResult.DefinitelyNotSent))
        assertEquals(PromptDispatchReconciliation.Observed(
            PromptDispatchReceiptState.DEFINITELY_NOT_SENT),
            TrustedPromptDispatchReconciler(journal).reconcile(lookup))
        assertEquals(PromptDispatchStartResult.AlreadyRecorded,
            journal.tryStart(request, lookup.requestDigest))
    }

    @Test fun `wrong payload digest or changed model is not permitted to adopt receipt`() {
        val request = prepare()
        val lookup = receiptQuery(request)
        val started = journal.tryStart(request, lookup.requestDigest)
        assertTrue(started is PromptDispatchStartResult.Started)
        val mismatchedDigest = lookup.copy(requestDigest = "sha256:" + "f".repeat(64))
        val otherDestination = lookup.copy(destination = destination.copy(model = "other-model"))
        for (invalid in listOf(mismatchedDigest, otherDestination)) {
            assertEquals(PromptDispatchReceiptRead.BindingConflict, journal.lookup(invalid))
            assertEquals(PromptDispatchReconciliation.BindingConflict,
                TrustedPromptDispatchReconciler(journal).reconcile(invalid))
        }
        assertNotEquals(lookup.requestDigest, mismatchedDigest.requestDigest)
    }

    @Test fun `another tenant or owner cannot infer an operation exists via lookup`() {
        val request = prepare()
        val query = receiptQuery(request)
        assertTrue(journal.tryStart(request, query.requestDigest) is PromptDispatchStartResult.Started)
        for (actor in listOf(Principal("tenant-b", principal.id),
            Principal(principal.tenantId, "owner-other"))) {
            val other = query.copy(principal = actor)
            assertEquals(PromptDispatchReceiptRead.NotFound, journal.lookup(other))
            assertEquals(PromptDispatchReconciliation.HeldNoReplay,
                TrustedPromptDispatchReconciler(journal).reconcile(other))
        }
    }

    @Test fun `new ephemeral journal loses record and NOT_FOUND must remain no-replay hold`() {
        val request = prepare()
        val query = receiptQuery(request)
        assertTrue(journal.tryStart(request, query.requestDigest) is PromptDispatchStartResult.Started)
        val fresh = InMemoryPromptSendStartJournalForTestOnly(store, tokenCounter, clock)
        assertEquals(PromptDispatchReceiptRead.NotFound, fresh.lookup(query))
        assertEquals(PromptDispatchReconciliation.HeldNoReplay,
            TrustedPromptDispatchReconciler(fresh).reconcile(query))
    }

    @Test fun `provider and storage uncertainty never grant model-side retry rights`() {
        val request = prepare()
        val query = receiptQuery(request)
        for (result in listOf(
            PromptDispatchReceiptRead.NotFound,
            PromptDispatchReceiptRead.OutcomeUnknown
        )) {
            assertEquals(PromptDispatchReconciliation.HeldNoReplay,
                TrustedPromptDispatchReconciler(TrustedPromptDispatchReceiptReader { result })
                    .reconcile(query))
        }
        assertEquals(PromptDispatchReconciliation.Unavailable,
            TrustedPromptDispatchReconciler(TrustedPromptDispatchReceiptReader {
                PromptDispatchReceiptRead.Unavailable
            }).reconcile(query))
        assertEquals(PromptDispatchReconciliation.Unsupported,
            TrustedPromptDispatchReconciler(TrustedPromptDispatchReceiptReader {
                PromptDispatchReceiptRead.Unsupported
            }).reconcile(query))
        assertEquals(PromptDispatchReconciliation.Unavailable,
            TrustedPromptDispatchReconciler(TrustedPromptDispatchReceiptReader {
                throw IllegalStateException("quorum lost")
            }).reconcile(query))
    }

    @Test fun `forged or inconsistent receipt is held instead of trusting status`() {
        val request = prepare()
        val query = receiptQuery(request)
        val invalid = listOf(
            PromptDispatchReceiptRead.Found(PromptDispatchReceiptState.TRANSPORT_ACCEPTED,
                "sha256:" + "1".repeat(64), 1, 2),
            PromptDispatchReceiptRead.Found(PromptDispatchReceiptState.TRANSPORT_ACCEPTED,
                query.requestDigest, 1, null),
            PromptDispatchReceiptRead.Found(PromptDispatchReceiptState.STARTED_PENDING,
                query.requestDigest, 1, 2),
            PromptDispatchReceiptRead.Found(PromptDispatchReceiptState.OUTCOME_UNKNOWN,
                query.requestDigest, 3, 2),
            PromptDispatchReceiptRead.Found(PromptDispatchReceiptState.DEFINITELY_NOT_SENT,
                query.requestDigest, -1, 3),
        )
        for (receipt in invalid) {
            assertEquals(PromptDispatchReconciliation.HeldNoReplay,
                TrustedPromptDispatchReconciler(TrustedPromptDispatchReceiptReader { receipt })
                    .reconcile(query))
        }
    }
}
