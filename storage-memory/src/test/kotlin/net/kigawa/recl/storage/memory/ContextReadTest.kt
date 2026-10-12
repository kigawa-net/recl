package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextReadTest {
    private val owner = Principal("tenant-a", "owner")
    private val id = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b"
    private val storage = InMemoryContextStorage()
    private val service = ContextService(storage)
    private val gateway = TrustedContextQueryGateway(ContextReader(storage),
        SessionAuthenticator { credential ->
            when (credential) {
                "owner-token" -> owner
                "other-owner" -> Principal("tenant-a", "other")
                "other-tenant" -> Principal("tenant-b", "owner")
                else -> null
            }
        }
    )

    private fun create(status: ContextStatus = ContextStatus.CONFIRMED) {
        assertEquals(MutationResult.Applied(1), service.create(owner,
            ContextAggregate("tenant-a", id, "owner", ContextKind.DECISION,
                status, "Architecture", "Summary", "Details",
                setOf("project:recl")),
            "create-request", "digest-create"
        ))
    }

    @Test fun `authorized owner receives only semantic snapshot fields`() {
        create()
        val view = gateway.get("owner-token", id) as ContextReadResult.Found
        assertEquals(ContextStatus.CONFIRMED, view.context.status)
        assertEquals("Details", view.context.content)
        assertEquals(1L, view.context.contentRevision)
        assertEquals(setOf("project:recl"), view.context.scopes)
        assertEquals(setOf("id", "kind", "status", "title", "summary", "content", "scopes", "contentRevision"),
            ContextSnapshot::class.java.declaredFields.filter { !it.isSynthetic }.map { it.name }.toSet())
    }

    @Test fun `unauthorized and absent contexts are indistinguishable`() {
        create()
        assertEquals(ContextReadResult.NotFound, gateway.get("other-owner", id))
        assertEquals(ContextReadResult.NotFound, gateway.get("other-tenant", id))
        assertEquals(ContextReadResult.NotFound,
            gateway.get("owner-token", "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6c"))
    }

    @Test fun `auth failure and bad UUID are rejected before storage`() {
        assertEquals(ContextReadResult.Forbidden, gateway.get("invalid-token", id))
        assertEquals(ContextReadResult.Forbidden, gateway.get("", id))
        assertEquals(ContextReadResult.InvalidRequest, gateway.get("owner-token", "hello"))
        assertEquals(ContextReadResult.InvalidRequest, gateway.get("owner-token",
            "019a1a2b-3c4d-4e8f-8a9b-1c2d3e4f5a6b"))
    }

    @Test fun `tombstone is never returned even while index might be stale`() {
        create()
        assertEquals(MutationResult.Applied(2), service.delete(owner, id, 1, "delete", "digest-delete"))
        assertEquals(ContextReadResult.NotFound, gateway.get("owner-token", id))
    }

    @Test fun `explicit owner lookup can inspect inactive history`() {
        create(ContextStatus.SUPERSEDED)
        val view = gateway.get("owner-token", id) as ContextReadResult.Found
        assertEquals(ContextStatus.SUPERSEDED, view.context.status)
    }

    @Test fun `projection bookkeeping never changes visible semantic revision`() {
        create()
        assertTrue(service.acknowledgeIndex("tenant-a", id, 1))
        val view = gateway.get("owner-token", id) as ContextReadResult.Found
        assertEquals(1L, view.context.contentRevision)
        assertEquals("Details", view.context.content)
    }

    @Test fun `unavailable authoritative store never returns cached data`() {
        val unavailable = object : ContextStorage {
            override fun get(tenantId: String, id: String): ReadResult = ReadResult.Unavailable
            override fun createIfAbsent(value: ContextAggregate): WriteResult = WriteResult.Unavailable
            override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long,
                value: ContextAggregate): WriteResult = WriteResult.Unavailable
        }
        val query = TrustedContextQueryGateway(ContextReader(unavailable),
            SessionAuthenticator { owner })
        assertEquals(ContextReadResult.Unavailable, query.get("credential", id))
    }

    @Test fun `uppercase UUID alias resolves to canonical context key`() {
        create()
        val view = gateway.get("owner-token", id.uppercase()) as ContextReadResult.Found
        assertEquals(id, view.context.id)
    }
}
