package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinnedContextReaderTest {
    private val tenant = "tenant-a"
    private val owner = Principal(tenant, "owner")
    private val store = InMemoryContextStorage()
    private val service = ContextService(store)

    private fun id(n: Int) = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a" + n.toString(16).padStart(2, '0')

    private fun add(
        n: Int,
        pinned: Boolean = true,
        status: ContextStatus = ContextStatus.CONFIRMED,
        scopes: Set<String> = setOf("project:recl"),
        tenantId: String = tenant,
        ownerId: String = "owner",
        content: String = "abcd",
        deleted: Boolean = false,
    ) {
        val context = ContextAggregate(
            tenantId = tenantId, id = id(n), ownerId = ownerId,
            kind = ContextKind.DECISION, status = status,
            title = "T$n", summary = "S$n", content = content,
            pinned = pinned, scopes = scopes, deleted = deleted,
        )
        assertTrue(store.createIfAbsent(context) is WriteResult.Applied)
    }

    private fun reader(
        storage: ContextStorage = store,
        maxItems: Int = 100,
        tokens: ContextTokenCounter = ContextTokenCounter { it.content.length },
    ) = PinnedContextReader(storage, tokens, maxItems)

    @Test fun `only authorized confirmed pinned and matching scopes appear`() {
        add(1)
        add(2, scopes = setOf("global"))
        add(3, pinned = false)
        add(4, status = ContextStatus.ARCHIVED)
        add(5, status = ContextStatus.SUPERSEDED)
        add(6, status = ContextStatus.PROPOSED)
        add(7, scopes = setOf("project:other"))
        add(8, ownerId = "other")
        add(9, tenantId = "tenant-b")
        add(10, deleted = true)
        val result = reader().list(owner,
            PinnedQuery(setOf("project:recl"), tokenBudget = 100, maxContexts = 20))
                as PinnedContextResult.Found
        assertEquals(listOf(id(1), id(2)), result.contexts.map { it.id })
        assertEquals(8, result.usedTokens)
        assertFalse(result.limited)
    }

    @Test fun `selection is deterministic and budget or result limit is explicit`() {
        add(1, content = "1234")
        add(2, content = "1234")
        add(3, content = "1234")
        val byBudget = reader().list(owner, PinnedQuery(tokenBudget = 7, maxContexts = 10))
            as PinnedContextResult.Found
        assertEquals(listOf(id(1)), byBudget.contexts.map { it.id })
        assertEquals(4, byBudget.usedTokens)
        assertTrue(byBudget.limited)
        val byCount = reader().list(owner, PinnedQuery(tokenBudget = 100, maxContexts = 2))
            as PinnedContextResult.Found
        assertEquals(listOf(id(1), id(2)), byCount.contexts.map { it.id })
        assertTrue(byCount.limited)
    }

    @Test fun `pin and unpin are semantic CAS changes with distinct request digests`() {
        val contextId = id(1)
        val createId = id(200)
        val pinId = id(201)
        val unpinId = id(202)
        val gateway = TrustedMutationGateway(service, SessionAuthenticator { credential ->
            if (credential == "verified") owner else null
        })
        val create = CreateContextMutation(contextId, createId, ContextKind.DECISION,
            ContextStatus.CONFIRMED, "T", "S", "abcd", setOf("project:recl"), pinned = false)
        assertEquals(MutationResult.Applied(1), gateway.create("verified", create))
        val query = PinnedQuery(tokenBudget = 100, maxContexts = 10)
        assertEquals(emptyList<String>(), (reader().list(owner, query) as PinnedContextResult.Found).contexts.map { it.id })
        assertEquals(MutationResult.Applied(2), gateway.update("verified",
            UpdateContextMutation(contextId, pinId, 1, ContextPatch(pinned = true))))
        assertEquals(listOf(contextId), (reader().list(owner, query) as PinnedContextResult.Found).contexts.map { it.id })
        assertEquals(MutationResult.RequestIdReused, gateway.update("verified",
            UpdateContextMutation(contextId, pinId, 1, ContextPatch(pinned = false))))
        assertEquals(MutationResult.Applied(3), gateway.update("verified",
            UpdateContextMutation(contextId, unpinId, 2, ContextPatch(pinned = false))))
        assertEquals(emptyList<String>(), (reader().list(owner, query) as PinnedContextResult.Found).contexts.map { it.id })
        val current = (store.get(tenant, contextId) as ReadResult.Found).stored.value
        assertEquals(3L, current.contentRevision)
        assertFalse(current.pinned)
    }

    @Test fun `unsupported scan is not replaced with Qdrant or a partial list`() {
        add(1)
        val unsupported = object : ContextStorage by store {
            override fun enumerateTenant(tenantId: String, maxItems: Int) = EnumerationResult.Unsupported
        }
        assertEquals(PinnedContextResult.Unsupported,
            reader(unsupported).list(owner, PinnedQuery(tokenBudget = 100, maxContexts = 10)))
    }

    @Test fun `large tenant enumeration does not silently truncate`() {
        add(1)
        add(2, pinned = false)
        assertEquals(PinnedContextResult.TooLarge,
            reader(maxItems = 1).list(owner, PinnedQuery(tokenBudget = 100, maxContexts = 10)))
    }

    @Test fun `authoritative reread error fails whole result without returning partial contents`() {
        add(1)
        add(2)
        val unreliable = object : ContextStorage by store {
            override fun get(tenantId: String, id: String): ReadResult =
                if (id == this@PinnedContextReaderTest.id(2)) ReadResult.Unavailable
                else store.get(tenantId, id)
        }
        assertEquals(PinnedContextResult.Unavailable,
            reader(unreliable).list(owner, PinnedQuery(tokenBudget = 100, maxContexts = 10)))
    }

    @Test fun `owner revocation between scan and read is revalidated`() {
        add(1)
        add(2)
        val racing = object : ContextStorage by store {
            override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult {
                val snapshot = store.enumerateTenant(tenantId, maxItems)
                val current = (store.get(tenantId, id(1)) as ReadResult.Found).stored
                assertTrue(store.compareAndSwap(tenantId, id(1), current.storageRevision,
                    current.value.copy(ownerId = "revoked-owner")) is WriteResult.Applied)
                return snapshot
            }
        }
        val result = reader(racing).list(owner, PinnedQuery(tokenBudget = 100, maxContexts = 10))
            as PinnedContextResult.Found
        assertEquals(listOf(id(2)), result.contexts.map { it.id })
    }

    @Test fun `deletion between scan and reread is not returned`() {
        add(1)
        val racing = object : ContextStorage by store {
            override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult {
                val snapshot = store.enumerateTenant(tenantId, maxItems)
                val current = (store.get(tenantId, id(1)) as ReadResult.Found).stored
                assertTrue(store.compareAndSwap(tenantId, id(1), current.storageRevision,
                    current.value.copy(deleted = true)) is WriteResult.Applied)
                return snapshot
            }
        }
        assertEquals(emptyList<String>(),
            (reader(racing).list(owner, PinnedQuery(tokenBudget = 100, maxContexts = 10))
                as PinnedContextResult.Found).contexts.map { it.id })
    }

    @Test fun `unauthenticated and invalid requests are rejected`() {
        val gate = TrustedPinnedQueryGateway(reader(), SessionAuthenticator { key ->
            if (key == "verified") owner else null
        })
        val good = PinnedQuery(tokenBudget = 100, maxContexts = 10)
        assertEquals(PinnedContextResult.Forbidden, gate.list("bad-credential", good))
        assertEquals(PinnedContextResult.InvalidRequest, gate.list("verified",
            PinnedQuery(tokenBudget = 0, maxContexts = 10)))
        assertEquals(PinnedContextResult.InvalidRequest, gate.list("verified",
            PinnedQuery(tokenBudget = 100, maxContexts = 0)))
    }

    @Test fun `projection checkpoint cannot change pinned semantic revision`() {
        add(1)
        assertTrue(service.acknowledgeIndex(tenant, id(1), 1))
        val result = reader().list(owner, PinnedQuery(tokenBudget = 100, maxContexts = 10))
            as PinnedContextResult.Found
        assertEquals(1L, result.contexts.single().contentRevision)
    }

    @Test fun `the estimator must provide a positive cost to avoid undercount`() {
        add(1)
        assertEquals(PinnedContextResult.Unavailable,
            reader(tokens = ContextTokenCounter { 0 }).list(owner,
                PinnedQuery(tokenBudget = 100, maxContexts = 10)))
    }
}
