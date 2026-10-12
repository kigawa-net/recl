package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrustedMutationGatewayTest {
    private val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b"
    private val createRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6c"
    private val updateRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6d"
    private val deleteRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6e"
    private val store = InMemoryContextStorage()
    private val gateway = TrustedMutationGateway(ContextService(store), SessionAuthenticator { credential ->
        when (credential) {
            "verified-owner" -> Principal("tenant-a", "owner")
            "verified-outsider" -> Principal("tenant-a", "outsider")
            "verified-other-tenant" -> Principal("tenant-b", "owner")
            else -> null
        }
    })
    private fun create(
        requestId: String = createRequestId,
        title: String = "Architecture",
        scopes: Set<String> = setOf("project:recl"),
    ) = CreateContextMutation(contextId, requestId, ContextKind.DECISION,
        ContextStatus.CONFIRMED, title, "summary", "content", scopes)

    @Test fun `only authenticated session creates authoritative owner and tenant`() {
        assertEquals(MutationResult.Forbidden, gateway.create("self-reported-tenant-admin", create()))
        assertEquals(MutationResult.Applied(1), gateway.create("verified-owner", create()))
        val stored = (store.get("tenant-a", contextId) as ReadResult.Found).stored.value
        assertEquals("tenant-a", stored.tenantId)
        assertEquals("owner", stored.ownerId)
        assertEquals(ReadResult.NotFound, store.get("tenant-b", contextId))
        assertEquals(MutationResult.Forbidden, gateway.update("verified-outsider",
            UpdateContextMutation(contextId, updateRequestId, 1, ContextPatch(title = "forged"))))
    }

    @Test fun `create receipt uses canonical NFC and ignores Set insertion order`() {
        val decomposed = "Cafe\u0301"
        val composed = "Caf\u00e9"
        val first = create(title = decomposed, scopes = linkedSetOf("topic:a", "project:recl"))
        val repeat = create(title = composed, scopes = linkedSetOf("project:recl", "topic:a"))
        assertEquals(MutationResult.Applied(1), gateway.create("verified-owner", first))
        assertEquals(MutationResult.Applied(1, replayed = true), gateway.create("verified-owner", repeat))
        assertEquals(composed, (store.get("tenant-a", contextId) as ReadResult.Found).stored.value.title)
    }

    @Test fun `replay with different payload is rejected without mutation`() {
        assertEquals(MutationResult.Applied(1), gateway.create("verified-owner", create()))
        assertEquals(MutationResult.RequestIdReused,
            gateway.create("verified-owner", create(title = "Different")))
        assertEquals(1L, (store.get("tenant-a", contextId) as ReadResult.Found).stored.value.contentRevision)
    }

    @Test fun `update is hashed server side and bound to semantic revision`() {
        gateway.create("verified-owner", create())
        val command = UpdateContextMutation(contextId, updateRequestId, 1,
            ContextPatch(title = "Trusted title"))
        assertEquals(MutationResult.Applied(2), gateway.update("verified-owner", command))
        assertEquals(MutationResult.Applied(2, replayed = true), gateway.update("verified-owner", command))
        assertEquals(MutationResult.RequestIdReused, gateway.update("verified-owner",
            command.copy(patch = ContextPatch(title = "different"))))
        assertEquals(2L, (store.get("tenant-a", contextId) as ReadResult.Found).stored.value.contentRevision)
    }

    @Test fun `missing patch field and explicitly empty field are not equivalent`() {
        gateway.create("verified-owner", create())
        val first = UpdateContextMutation(contextId, updateRequestId, 1, ContextPatch())
        assertEquals(MutationResult.Applied(2), gateway.update("verified-owner", first))
        assertEquals(MutationResult.RequestIdReused, gateway.update("verified-owner",
            first.copy(patch = ContextPatch(title = ""))))
    }

    @Test fun `the same request identity cannot be reused for a different operation`() {
        gateway.create("verified-owner", create())
        gateway.update("verified-owner", UpdateContextMutation(contextId, updateRequestId, 1,
            ContextPatch(summary = "v2")))
        assertEquals(MutationResult.RequestIdReused, gateway.delete("verified-owner",
            DeleteContextMutation(contextId, updateRequestId, 2)))
        assertTrue(!(store.get("tenant-a", contextId) as ReadResult.Found).stored.value.deleted)
        assertEquals(MutationResult.Applied(3), gateway.delete("verified-owner",
            DeleteContextMutation(contextId, deleteRequestId, 2)))
    }

    @Test fun `UUIDv7 identifiers are required on the trusted boundary`() {
        assertEquals(MutationResult.InvalidRequest, gateway.create("verified-owner",
            create(requestId = "not-an-id")))
        assertEquals(MutationResult.InvalidRequest, gateway.create("verified-owner",
            create().copy(contextId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")))
        assertEquals(ReadResult.NotFound, store.get("tenant-a", contextId))
    }

    @Test fun `a different tenant cannot impersonate the original owner`() {
        gateway.create("verified-owner", create())
        assertEquals(MutationResult.NotFound, gateway.update("verified-other-tenant",
            UpdateContextMutation(contextId, updateRequestId, 1, ContextPatch(title = "cross-tenant"))))
    }
}
