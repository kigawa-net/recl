package net.kigawa.recl.storage.memory

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextValidityAndSensitivityTest {
    private val owner = Principal("tenant-a", "owner-1")
    private val now = Instant.parse("2026-10-12T04:00:00Z").toEpochMilli()
    private val fixed = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val id0 = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a"
    private fun id(n: Int) = id0 + n.toString(16).padStart(2, '0')
    private val storage = InMemoryContextStorage()

    private fun add(
        n: Int,
        sensitivity: Sensitivity = Sensitivity.INTERNAL,
        from: Long? = null,
        until: Long? = null,
        pinned: Boolean = true,
    ): ContextAggregate {
        val aggregate = ContextAggregate(
            tenantId = owner.tenantId, id = id(n), ownerId = owner.id,
            kind = ContextKind.CONSTRAINT, status = ContextStatus.CONFIRMED,
            title = "Policy #$n", summary = "summary", content = "abcd",
            pinned = pinned, scopes = setOf("project:recl"),
            sensitivity = sensitivity, validFromEpochMillis = from,
            validUntilEpochMillis = until,
        )
        assertTrue(storage.createIfAbsent(aggregate) is WriteResult.Applied)
        return aggregate
    }

    private fun reader(store: ContextStorage = storage) =
        PinnedContextReader(store, ContextTokenCounter { 1 }, clock = fixed)

    private fun query() = PinnedQuery(setOf("project:recl"), tokenBudget = 100, maxContexts = 50)

    @Test fun `valid from inclusive and valid until exclusive at exact boundary`() {
        add(1, from = now, until = now + 1)
        add(2, from = now + 1)
        add(3, until = now)
        add(4)
        add(5, from = now - 1, until = now + 100)
        val found = reader().list(owner, query()) as PinnedContextResult.Found
        assertEquals(listOf(id(1), id(4), id(5)), found.contexts.map { it.id })
        assertEquals(3, found.usedTokens)
    }

    @Test fun `confirmed public and internal auto inject but confidential never does`() {
        add(1, sensitivity = Sensitivity.PUBLIC)
        add(2, sensitivity = Sensitivity.INTERNAL)
        add(3, sensitivity = Sensitivity.CONFIDENTIAL)
        val results = reader().list(owner, query()) as PinnedContextResult.Found
        assertEquals(listOf(id(1), id(2)), results.contexts.map { it.id })
        // Explicit owner-authorized retrieval remains available for historical/confidential review.
        val direct = ContextReader(storage).get(owner, id(3)) as ContextReadResult.Found
        assertEquals(Sensitivity.CONFIDENTIAL, direct.context.sensitivity)
    }

    @Test fun `restricted context cannot be constructed or persisted`() {
        assertThrows(IllegalArgumentException::class.java) {
            add(1, sensitivity = Sensitivity.RESTRICTED)
        }
        assertEquals(ReadResult.NotFound, storage.get(owner.tenantId, id(1)))
    }

    @Test fun `invalid half open validity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            add(1, from = now, until = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            add(1, from = now + 1, until = now)
        }
    }

    @Test fun `trusted create rejects restricted and invalid interval with typed error`() {
        val svc = ContextService(storage)
        val gateway = TrustedMutationGateway(svc, SessionAuthenticator { if (it == "valid") owner else null })
        fun create(n: Int, sensitivity: Sensitivity = Sensitivity.INTERNAL,
                   from: Long? = null, until: Long? = null) = CreateContextMutation(
            id(n), id(201 + n), ContextKind.CONSTRAINT, ContextStatus.CONFIRMED,
            "title", "summary", "content", setOf("project:recl"), pinned = true,
            sensitivity = sensitivity, validFromEpochMillis = from, validUntilEpochMillis = until,
        )
        assertEquals(MutationResult.InvalidRequest, gateway.create("valid",
            create(1, sensitivity = Sensitivity.RESTRICTED)))
        assertEquals(MutationResult.InvalidRequest, gateway.create("valid",
            create(2, from = now + 1, until = now)))
        assertEquals(ReadResult.NotFound, storage.get(owner.tenantId, id(1)))
        assertEquals(ReadResult.NotFound, storage.get(owner.tenantId, id(2)))
    }

    @Test fun `patch invalid against stored validity returns invalid request and preserves state`() {
        val initial = add(1, from = now, until = now + 100)
        val svc = ContextService(storage)
        val rejected = svc.update(owner, initial.id, 1, "u1", "digest-u1",
            ContextPatch(validUntilEpochMillis = now - 1))
        assertEquals(MutationResult.InvalidRequest, rejected)
        val persisted = (storage.get(owner.tenantId, id(1)) as ReadResult.Found).stored.value
        assertEquals(1L, persisted.contentRevision)
        assertEquals(now + 100, persisted.validUntilEpochMillis)
        assertEquals(Sensitivity.INTERNAL, persisted.sensitivity)
    }

    @Test fun `mutation sensitivity and validity are included in request replay digest`() {
        val svc = ContextService(storage)
        val gateway = TrustedMutationGateway(svc, SessionAuthenticator { if (it == "valid") owner else null })
        val create = CreateContextMutation(id(1), id(222), ContextKind.CONSTRAINT,
            ContextStatus.CONFIRMED, "title", "summary", "abcd",
            pinned = true, validFromEpochMillis = now - 1)
        assertEquals(MutationResult.Applied(1), gateway.create("valid", create))
        assertEquals(MutationResult.RequestIdReused,
            gateway.create("valid", create.copy(sensitivity = Sensitivity.PUBLIC)))
        val update = UpdateContextMutation(id(1), id(223), 1,
            ContextPatch(sensitivity = Sensitivity.CONFIDENTIAL, validUntilEpochMillis = now + 10))
        assertEquals(MutationResult.Applied(2), gateway.update("valid", update))
        assertEquals(MutationResult.Applied(2, replayed = true), gateway.update("valid", update))
        assertEquals(MutationResult.RequestIdReused,
            gateway.update("valid", update.copy(patch = ContextPatch(
                sensitivity = Sensitivity.PUBLIC, validUntilEpochMillis = now + 10))))
        assertEquals(MutationResult.InvalidRequest,
            gateway.update("valid", UpdateContextMutation(id(1), id(224), 2,
                ContextPatch(sensitivity = Sensitivity.RESTRICTED))))
        assertEquals(emptyList<String>(),
            (reader().list(owner, query()) as PinnedContextResult.Found).contexts.map { it.id })
    }

    @Test fun `concurrent policy change after scan is respected on authoritative reread`() {
        add(1, sensitivity = Sensitivity.INTERNAL)
        add(2, sensitivity = Sensitivity.INTERNAL)
        val swapping = object : ContextStorage by storage {
            override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult {
                val page = storage.enumerateTenant(tenantId, maxItems)
                val old = (storage.get(tenantId, id(1)) as ReadResult.Found).stored
                assertTrue(storage.compareAndSwap(tenantId, id(1), old.storageRevision,
                    old.value.copy(sensitivity = Sensitivity.CONFIDENTIAL, contentRevision = 2))
                    is WriteResult.Applied)
                return page
            }
        }
        assertEquals(listOf(id(2)), (reader(swapping).list(owner, query())
            as PinnedContextResult.Found).contexts.map { it.id })
    }
}
