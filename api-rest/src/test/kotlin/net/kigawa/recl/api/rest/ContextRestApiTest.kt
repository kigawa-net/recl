package net.kigawa.recl.api.rest

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextReader
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextSource
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.ContextTokenCounter
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.PinnedContextReader
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.SessionAuthenticator
import net.kigawa.recl.core.SourceTrust
import net.kigawa.recl.core.SourceType
import net.kigawa.recl.core.VerificationState
import net.kigawa.recl.core.WriteResult
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpRequest.BodyPublishers

class ContextRestApiTest {
    private val ownerToken = "token-owner"
    private val sameTenantOtherToken = "token-same-tenant-other-owner"
    private val otherTenantToken = "token-other-tenant-owner"

    private val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b"
    private val missingId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4fffff"
    private val createRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a10"
    private val updateRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a11"
    private val secondUpdateRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a12"
    private val deleteRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a13"
    private val otherRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a14"

    private val memory = InMemoryContextStorage()
    private val faulty = FaultyStorage(memory)
    private val authenticator = SessionAuthenticator { credential ->
        when (credential) {
            ownerToken -> Principal("tenant-a", "owner")
            sameTenantOtherToken -> Principal("tenant-a", "outsider")
            otherTenantToken -> Principal("tenant-b", "owner")
            else -> null
        }
    }
    private val http = HttpClient.newHttpClient()
    private lateinit var server: HttpServer
    private var port = 0

    @BeforeEach
    fun startServer() {
        startWith(PinnedContextReader(memory, contentLengthTokens))
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun startWith(pinned: PinnedContextReader) {
        if (::server.isInitialized) server.stop(0)
        val api = ContextRestApi(ContextService(faulty), ContextReader(faulty), authenticator, pinned)
        server = startContextRestServer(api, InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        port = server.address.port
    }

    // ---- CRUD -------------------------------------------------------------------------------

    @Test
    fun `owner can create, read, patch and delete a Context`() {
        val created = call("POST", "/contexts", body = createBody())
        assertEquals(201, created.status)
        assertEquals("/contexts/$contextId", created.headers.firstValue("Location").orElse(null))
        assertEquals(1L, created.num("contentRevision"))
        assertEquals(false, created.json["replayed"]!!.jsonPrimitive.boolean)

        val read = call("GET", "/contexts/$contextId")
        assertEquals(200, read.status)
        assertEquals("Decision", read.str("title"))
        assertEquals("decision", read.str("kind"))
        assertEquals("confirmed", read.str("status"))
        assertEquals(listOf("project:recl"), read.json["scopes"]!!.jsonArray.map { it.jsonPrimitive.content })
        for (internal in listOf(
            "tenantId", "ownerId", "storageRevision", "indexedContentRevision", "indexAction",
            "operationReceipts", "deleted", "pinned", "sources", "payloadDigest",
        )) {
            assertFalse(read.json.containsKey(internal), "response must not expose $internal")
        }

        val patched = call("PATCH", "/contexts/$contextId",
            body = body("requestId" to updateRequestId, "expectedContentRevision" to 1, "title" to "Renamed"))
        assertEquals(200, patched.status)
        assertEquals(2L, patched.num("contentRevision"))
        assertEquals("Renamed", call("GET", "/contexts/$contextId").str("title"))

        val deleted = call("DELETE", "/contexts/$contextId?requestId=$deleteRequestId&expectedContentRevision=2")
        assertEquals(200, deleted.status)
        assertEquals(true, deleted.json["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(404, call("GET", "/contexts/$contextId").status)
        assertEquals(404, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to secondUpdateRequestId, "expectedContentRevision" to 3, "title" to "x")).status)
    }

    @Test
    fun `uppercase UUIDv7 is canonicalised to lowercase`() {
        val upper = contextId.uppercase()
        val created = call("POST", "/contexts", body = createBody("contextId" to upper))
        assertEquals(201, created.status)
        assertEquals(contextId, created.str("contextId"))
        assertEquals(200, call("GET", "/contexts/$contextId").status)
    }

    // ---- authentication and leak prevention --------------------------------------------------

    @Test
    fun `missing, malformed or unverified bearer is 401 and nothing is written`() {
        val withoutHeader = call("POST", "/contexts", token = null, body = createBody())
        assertEquals(401, withoutHeader.status)
        assertEquals("UNAUTHENTICATED", withoutHeader.str("error"))
        assertEquals("Bearer", withoutHeader.headers.firstValue("WWW-Authenticate").orElse(null))
        assertEquals(401, call("POST", "/contexts", token = null,
            headers = mapOf("Authorization" to "Basic dXNlcjpwYXNz"), body = createBody()).status)
        assertEquals(401, call("POST", "/contexts", token = "forged", body = createBody()).status)
        assertEquals(ReadResult.NotFound, memory.get("tenant-a", contextId))

        assertEquals(201, call("POST", "/contexts", body = createBody()).status)
        assertEquals(401, call("GET", "/contexts/$contextId", token = "forged").status)
        assertEquals(401, call("GET", "/contexts/$contextId", token = null).status)
        assertEquals(401, call("GET", "/contexts/$missingId", token = null).status)
        assertEquals(401, call("PATCH", "/contexts/$contextId", token = "forged",
            body = body("requestId" to updateRequestId, "expectedContentRevision" to 1, "title" to "x")).status)
        assertEquals(401, call("DELETE", "/contexts/$contextId?requestId=$deleteRequestId&expectedContentRevision=1",
            token = null).status)
        assertEquals("Decision", call("GET", "/contexts/$contextId").str("title"))
    }

    @Test
    fun `other owner and other tenant receive the same answer as a missing Context`() {
        assertEquals(201, call("POST", "/contexts", body = createBody()).status)

        val missingRead = call("GET", "/contexts/$missingId")
        assertEquals(404, missingRead.status)
        for (token in listOf(sameTenantOtherToken, otherTenantToken)) {
            val read = call("GET", "/contexts/$contextId", token = token)
            assertEquals(404, read.status)
            assertEquals(missingRead.json, read.json)
        }

        val missingPatch = call("PATCH", "/contexts/$missingId",
            body = body("requestId" to otherRequestId, "expectedContentRevision" to 1, "title" to "x"))
        val missingDelete = call("DELETE", "/contexts/$missingId?requestId=$deleteRequestId&expectedContentRevision=1")
        for (token in listOf(sameTenantOtherToken, otherTenantToken)) {
            val patch = call("PATCH", "/contexts/$contextId", token = token,
                body = body("requestId" to otherRequestId, "expectedContentRevision" to 1, "title" to "forged"))
            assertEquals(404, patch.status)
            assertEquals(missingPatch.json, patch.json)

            val delete = call("DELETE", "/contexts/$contextId?requestId=$deleteRequestId&expectedContentRevision=1",
                token = token)
            assertEquals(404, delete.status)
            assertEquals(missingDelete.json, delete.json)
        }

        // Same tenant, other owner: an ID collision is a conflict and must not overwrite the existing Context.
        val collide = call("POST", "/contexts", token = sameTenantOtherToken,
            body = createBody("requestId" to otherRequestId, "title" to "forged"))
        assertEquals(409, collide.status)
        assertEquals("CONTEXT_ID_CONFLICT", collide.str("error"))

        // Context keys are tenant-scoped (core StoragePort), so another tenant may create the same UUID
        // without touching tenant-a's Context.
        val otherTenantCreate = call("POST", "/contexts", token = otherTenantToken,
            body = createBody("requestId" to otherRequestId, "title" to "other tenant"))
        assertEquals(201, otherTenantCreate.status)

        val owner = call("GET", "/contexts/$contextId")
        assertEquals("Decision", owner.str("title"))
        assertEquals(1L, owner.num("contentRevision"))
        assertEquals("other tenant", call("GET", "/contexts/$contextId", token = otherTenantToken).str("title"))
    }

    // ---- optimistic locking and idempotency --------------------------------------------------

    @Test
    fun `stale expectedContentRevision is 409 with the current revision and no mutation`() {
        call("POST", "/contexts", body = createBody())
        assertEquals(200, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to updateRequestId, "expectedContentRevision" to 1, "title" to "Renamed")).status)

        val stale = call("PATCH", "/contexts/$contextId",
            body = body("requestId" to secondUpdateRequestId, "expectedContentRevision" to 1, "title" to "Stale"))
        assertEquals(409, stale.status)
        assertEquals("REVISION_CONFLICT", stale.str("error"))
        assertEquals(2L, stale.num("currentContentRevision"))

        val read = call("GET", "/contexts/$contextId")
        assertEquals("Renamed", read.str("title"))
        assertEquals(2L, read.num("contentRevision"))

        val staleDelete = call("DELETE", "/contexts/$contextId?requestId=$deleteRequestId&expectedContentRevision=1")
        assertEquals(409, staleDelete.status)
        assertEquals(2L, staleDelete.num("currentContentRevision"))
    }

    @Test
    fun `same requestId with the same payload replays the same result without a second write`() {
        call("POST", "/contexts", body = createBody())
        val patchBody = body("requestId" to updateRequestId, "expectedContentRevision" to 1, "title" to "Renamed")
        val first = call("PATCH", "/contexts/$contextId", body = patchBody)
        assertEquals(200, first.status)
        assertEquals(false, first.json["replayed"]!!.jsonPrimitive.boolean)

        val replay = call("PATCH", "/contexts/$contextId", body = patchBody)
        assertEquals(200, replay.status)
        assertEquals(2L, replay.num("contentRevision"))
        assertEquals(true, replay.json["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(2L, call("GET", "/contexts/$contextId").num("contentRevision"))

        val createReplay = call("POST", "/contexts", body = createBody())
        assertEquals(200, createReplay.status)
        assertEquals(true, createReplay.json["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(1L, createReplay.num("contentRevision"))

        val deleteUrl = "/contexts/$contextId?requestId=$deleteRequestId&expectedContentRevision=2"
        assertEquals(200, call("DELETE", deleteUrl).status)
        val deleteReplay = call("DELETE", deleteUrl)
        assertEquals(200, deleteReplay.status)
        assertEquals(true, deleteReplay.json["replayed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `requestId reused with a different payload is rejected without mutation`() {
        call("POST", "/contexts", body = createBody())
        assertEquals(200, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to updateRequestId, "expectedContentRevision" to 1, "title" to "Renamed")).status)

        val reused = call("PATCH", "/contexts/$contextId",
            body = body("requestId" to updateRequestId, "expectedContentRevision" to 2, "title" to "Different"))
        assertEquals(409, reused.status)
        assertEquals("REQUEST_ID_REUSED", reused.str("error"))

        val createReused = call("POST", "/contexts", body = createBody("title" to "Different"))
        assertEquals(409, createReused.status)
        assertEquals("REQUEST_ID_REUSED", createReused.str("error"))

        val read = call("GET", "/contexts/$contextId")
        assertEquals("Renamed", read.str("title"))
        assertEquals(2L, read.num("contentRevision"))
    }

    // ---- input validation --------------------------------------------------------------------

    @Test
    fun `malformed or non-UUIDv7 identifiers are rejected with 400`() {
        val v4 = "123e4567-e89b-42d3-a456-426614174000"
        assertEquals(400, call("GET", "/contexts/not-a-uuid").status)
        assertEquals("INVALID_ID", call("GET", "/contexts/$v4").str("error"))
        assertEquals(400, call("POST", "/contexts", body = createBody("contextId" to v4)).status)
        assertEquals(400, call("POST", "/contexts", body = createBody("requestId" to v4)).status)
        assertEquals(400, call("POST", "/contexts", body = createBody("requestId" to "bogus")).status)
        assertEquals(400, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to v4, "expectedContentRevision" to 1, "title" to "x")).status)
        assertEquals(400, call("DELETE", "/contexts/$contextId?requestId=$v4&expectedContentRevision=1").status)
        assertEquals(0, countStored())
    }

    @Test
    fun `JSON null is rejected for every field instead of clearing it`() {
        call("POST", "/contexts", body = createBody())
        val clears = listOf(
            "scopes" to null, "summary" to null, "content" to null, "sensitivity" to null,
            "validFromEpochMillis" to null, "validUntilEpochMillis" to null, "status" to null,
        )
        for ((field, value) in clears) {
            val response = call("PATCH", "/contexts/$contextId",
                body = body("requestId" to updateRequestId, "expectedContentRevision" to 1, field to value))
            assertEquals(400, response.status, field)
            assertEquals("INVALID_FIELD", response.str("error"), field)
        }
        assertEquals(400, call("POST", "/contexts", body = createBody("summary" to null)).status)
        assertEquals(1L, call("GET", "/contexts/$contextId").num("contentRevision"))
    }

    @Test
    fun `client-supplied tenant, owner, principal, digest or verification fields are rejected`() {
        for (field in listOf("tenantId", "ownerId", "principal", "payloadDigest", "digest", "verification", "pinned", "sources")) {
            val response = call("POST", "/contexts", body = createBody(field to "forged"))
            assertEquals(400, response.status, field)
            assertEquals("INVALID_FIELD", response.str("error"), field)
        }
        assertEquals(0, countStored())
    }

    @Test
    fun `invalid field values, RESTRICTED sensitivity and superseded status are refused`() {
        assertEquals(400, call("POST", "/contexts", body = createBody("title" to "   ")).status)
        assertEquals(400, call("POST", "/contexts", body = createBody("kind" to "DECISION")).status)
        assertEquals(400, call("POST", "/contexts", body = createBody("validFromEpochMillis" to "1000")).status)
        assertEquals(400, call("POST", "/contexts", body = createBody("scopes" to "project:recl")).status)
        assertEquals(400, call("POST", "/contexts", body = createBody("status" to "superseded")).status)
        assertEquals(422, call("POST", "/contexts", body = createBody("sensitivity" to "restricted")).status)
        assertEquals("REJECTED_BY_POLICY", call("POST", "/contexts", body = createBody("sensitivity" to "restricted")).str("error"))
        assertEquals(0, countStored())

        call("POST", "/contexts", body = createBody())
        val before = call("GET", "/contexts/$contextId")
        assertEquals(422, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to updateRequestId, "expectedContentRevision" to 1, "sensitivity" to "restricted")).status)
        assertEquals(400, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to secondUpdateRequestId, "expectedContentRevision" to 1, "status" to "superseded")).status)
        assertEquals(400, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to secondUpdateRequestId, "expectedContentRevision" to 1, "title" to "")).status)
        assertEquals(400, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to secondUpdateRequestId, "expectedContentRevision" to 0, "title" to "x")).status)
        assertEquals(400, call("PATCH", "/contexts/$contextId",
            body = body("requestId" to secondUpdateRequestId, "expectedContentRevision" to 1)).status)
        assertEquals(before.json, call("GET", "/contexts/$contextId").json)
    }

    @Test
    fun `routing rejects unsupported methods and paths without touching storage`() {
        assertEquals(405, call("GET", "/contexts").status)
        assertEquals(405, call("PUT", "/contexts/$contextId", body = "{}").status)
        assertEquals(404, call("GET", "/elsewhere").status)
        assertEquals(404, call("GET", "/contexts/$contextId/extra").status)
        assertEquals(0, countStored())
    }

    @Test
    fun `oversized body is rejected with 413`() {
        val response = call("POST", "/contexts", body = "x".repeat(ContextRestApi.MAX_BODY_BYTES + 1))
        assertEquals(413, response.status)
        assertEquals("PAYLOAD_TOO_LARGE", response.str("error"))
    }

    // ---- OutcomeUnknown ----------------------------------------------------------------------

    @Test
    fun `lost response without commit is OUTCOME_UNKNOWN and not reported as applied or not applied`() {
        faulty.nextWriteFault = FaultyStorage.Fault.LOSE_RESPONSE_WITHOUT_COMMIT
        val unknown = call("POST", "/contexts", body = createBody())
        assertEquals(503, unknown.status)
        assertEquals("OUTCOME_UNKNOWN", unknown.str("error"))
        assertFalse(unknown.json.containsKey("contentRevision"))
        assertFalse(unknown.json.containsKey("replayed"))

        // Retrying with the same requestId and body settles the outcome.
        val retry = call("POST", "/contexts", body = createBody())
        assertEquals(201, retry.status)
        assertEquals(false, retry.json["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(1L, call("GET", "/contexts/$contextId").num("contentRevision"))
    }

    @Test
    fun `lost response after commit is reconciled to the applied result by requestId`() {
        faulty.nextWriteFault = FaultyStorage.Fault.COMMIT_THEN_LOSE_RESPONSE
        val reconciled = call("POST", "/contexts", body = createBody())
        assertEquals(200, reconciled.status)
        assertEquals(true, reconciled.json["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(1L, reconciled.num("contentRevision"))
        assertEquals(1, countStored())

        call("PATCH", "/contexts/$contextId",
            body = body("requestId" to updateRequestId, "expectedContentRevision" to 1, "title" to "Renamed"))
        faulty.nextWriteFault = FaultyStorage.Fault.COMMIT_THEN_LOSE_RESPONSE
        val patched = call("PATCH", "/contexts/$contextId",
            body = body("requestId" to secondUpdateRequestId, "expectedContentRevision" to 2, "title" to "Again"))
        assertEquals(200, patched.status)
        assertEquals(3L, patched.num("contentRevision"))
        assertEquals("Again", call("GET", "/contexts/$contextId").str("title"))
        assertEquals(3L, call("GET", "/contexts/$contextId").num("contentRevision"))
    }

    // ---- pinned retrieval (GET /contexts/pinned) --------------------------------------------

    private val contentLengthTokens = ContextTokenCounter { it.content.length }
    private val futureMillis = 4_102_444_800_000L // 2100-01-01T00:00:00Z
    private val pastMillis = 1_000L

    private fun seedId(n: Int) = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f7a%02x".format(n)

    private fun seed(
        n: Int,
        tenant: String = "tenant-a",
        owner: String = "owner",
        scopes: Set<String> = setOf("project:recl"),
        pinned: Boolean = true,
        status: ContextStatus = ContextStatus.CONFIRMED,
        sensitivity: Sensitivity = Sensitivity.INTERNAL,
        validFrom: Long? = null,
        validUntil: Long? = null,
        sources: List<ContextSource> = emptyList(),
        deleted: Boolean = false,
        content: String = "0123456789",
    ) {
        val context = ContextAggregate(
            tenantId = tenant, id = seedId(n), ownerId = owner,
            kind = ContextKind.DECISION, status = status,
            title = "T$n", summary = "S$n", content = content,
            scopes = scopes, pinned = pinned, sensitivity = sensitivity,
            validFromEpochMillis = validFrom, validUntilEpochMillis = validUntil,
            sources = sources, deleted = deleted,
        )
        assertTrue(memory.createIfAbsent(context) is WriteResult.Applied, "seed $n")
    }

    private fun pinnedPath(vararg query: String) =
        "/contexts/pinned" + if (query.isEmpty()) "" else "?" + query.joinToString("&")

    @Test
    fun `pinned list returns confirmed pinned owner contexts in id order with usedTokens and limited`() {
        seed(3, content = "cccc")
        seed(1, content = "aaaa")
        seed(2, content = "bbbb")

        val response = call("GET", pinnedPath("tokenBudget=100", "maxContexts=10"))
        assertEquals(200, response.status)
        assertEquals(listOf(seedId(1), seedId(2), seedId(3)), response.contexts().map { it.str("id") })
        assertEquals(12L, response.num("usedTokens"))
        assertEquals(false, response.json["limited"]!!.jsonPrimitive.boolean)

        val first = response.contexts()[0]
        assertEquals("T1", first.str("title"))
        assertEquals("decision", first.str("kind"))
        assertEquals("confirmed", first.str("status"))
        assertEquals("internal", first.str("sensitivity"))
        assertEquals(listOf("project:recl"), first.json["scopes"]!!.jsonArray.map { it.jsonPrimitive.content })
        for (internal in listOf("tenantId", "ownerId", "pinned", "sources", "storageRevision", "operationReceipts")) {
            assertFalse(first.json.containsKey(internal), "response must not expose $internal")
        }
    }

    @Test
    fun `scope is a relevance filter and global scope is included for every requested scope`() {
        seed(1, scopes = setOf("project:recl"))
        seed(2, scopes = setOf("project:other"))
        seed(3, scopes = setOf("global"))
        seed(4, scopes = setOf("project:recl", "project:other"))

        val recl = call("GET", pinnedPath("scope=project:recl", "tokenBudget=100", "maxContexts=10"))
        assertEquals(listOf(seedId(1), seedId(3), seedId(4)), recl.contexts().map { it.str("id") })

        val repeated = call("GET", pinnedPath("scope=project:other", "scope=project:recl", "tokenBudget=100", "maxContexts=10"))
        assertEquals(listOf(seedId(1), seedId(2), seedId(3), seedId(4)), repeated.contexts().map { it.str("id") })

        val unmatched = call("GET", pinnedPath("scope=project:none", "tokenBudget=100", "maxContexts=10"))
        assertEquals(listOf(seedId(3)), unmatched.contexts().map { it.str("id") })
    }

    @Test
    fun `other owners and other tenants never appear in the owner's pinned result`() {
        seed(1)
        seed(2, owner = "outsider")
        seed(3, tenant = "tenant-b")

        assertEquals(listOf(seedId(1)), call("GET", pinnedPath("tokenBudget=100", "maxContexts=10"))
            .contexts().map { it.str("id") })
        assertEquals(listOf(seedId(2)), call("GET", pinnedPath("tokenBudget=100", "maxContexts=10"), token = sameTenantOtherToken)
            .contexts().map { it.str("id") })
        assertEquals(listOf(seedId(3)), call("GET", pinnedPath("tokenBudget=100", "maxContexts=10"), token = otherTenantToken)
            .contexts().map { it.str("id") })
    }

    @Test
    fun `contexts that are not eligible for automatic injection never appear`() {
        seed(1) // eligible control
        seed(2, pinned = false)
        seed(3, status = ContextStatus.PROPOSED)
        seed(4, status = ContextStatus.ARCHIVED)
        seed(5, sensitivity = Sensitivity.CONFIDENTIAL)
        seed(6, validUntil = pastMillis)
        seed(7, validFrom = futureMillis)
        seed(8, deleted = true)
        seed(9, sources = listOf(ContextSource(
            sourceId = "src-1", type = SourceType.URL, uri = "https://example.com/spec",
            locator = null, retrievedAtEpochMillis = null, contentDigest = null,
            trust = SourceTrust.USER_STATED, verification = VerificationState.UNVERIFIED,
        )))

        val response = call("GET", pinnedPath("tokenBudget=1000", "maxContexts=100"))
        assertEquals(200, response.status)
        assertEquals(listOf(seedId(1)), response.contexts().map { it.str("id") })
    }

    @Test
    fun `token budget and maxContexts limit the result and report limited`() {
        seed(1, content = "0123456789")
        seed(2, content = "0123456789")
        seed(3, content = "0123456789")

        val byBudget = call("GET", pinnedPath("tokenBudget=25", "maxContexts=10"))
        assertEquals(200, byBudget.status)
        assertEquals(listOf(seedId(1), seedId(2)), byBudget.contexts().map { it.str("id") })
        assertEquals(20L, byBudget.num("usedTokens"))
        assertEquals(true, byBudget.json["limited"]!!.jsonPrimitive.boolean)

        val byCount = call("GET", pinnedPath("tokenBudget=1000", "maxContexts=1"))
        assertEquals(listOf(seedId(1)), byCount.contexts().map { it.str("id") })
        assertEquals(10L, byCount.num("usedTokens"))
        assertEquals(true, byCount.json["limited"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pinned list without a verified bearer is 401 and returns no contexts`() {
        seed(1)
        val query = listOf("tokenBudget=100", "maxContexts=10")
        val missing = call("GET", pinnedPath(*query.toTypedArray()), token = null)
        assertEquals(401, missing.status)
        assertEquals("UNAUTHENTICATED", missing.str("error"))
        assertEquals("Bearer", missing.headers.firstValue("WWW-Authenticate").orElse(null))
        assertEquals(401, call("GET", pinnedPath(*query.toTypedArray()), token = "forged").status)
        assertEquals(401, call("GET", pinnedPath(*query.toTypedArray()), token = null,
            headers = mapOf("Authorization" to "Basic dXNlcjpwYXNz")).status)
    }

    @Test
    fun `client-supplied owner or tenant parameters are rejected with 400`() {
        seed(1)
        for (name in listOf("tenantId", "ownerId", "principal", "owner", "tenant")) {
            val response = call("GET", pinnedPath("tokenBudget=100", "maxContexts=10", "$name=tenant-b"))
            assertEquals(400, response.status, name)
            assertEquals("INVALID_FIELD", response.str("error"), name)
        }
    }

    @Test
    fun `invalid pinned query parameters are rejected with 400`() {
        val invalid = listOf(
            "maxContexts=10", // tokenBudget missing
            "tokenBudget=100", // maxContexts missing
            "tokenBudget=0&maxContexts=10",
            "tokenBudget=100001&maxContexts=10",
            "tokenBudget=-5&maxContexts=10",
            "tokenBudget=5x&maxContexts=10",
            "tokenBudget=100&maxContexts=0",
            "tokenBudget=100&maxContexts=101",
            "tokenBudget=100&maxContexts=abc",
            "tokenBudget=100&tokenBudget=200&maxContexts=10",
            "tokenBudget=100&maxContexts=10&maxContexts=10",
            "tokenBudget=100&maxContexts=10&scope=",
            "tokenBudget=100&maxContexts=10&scope=%20%20",
            "tokenBudget=100&maxContexts=10&scope=" + "x".repeat(257),
            "tokenBudget=100&maxContexts=10&unknown=1",
            "tokenBudget=100&maxContexts=10&maxItems=5",
        ) + listOf(
            "tokenBudget=100&maxContexts=10&" + (1..33).joinToString("&") { "scope=s$it" },
        )
        for (query in invalid) {
            val response = call("GET", "/contexts/pinned?$query")
            assertEquals(400, response.status, query)
            assertEquals("INVALID_FIELD", response.str("error"), query)
            assertFalse(response.json.containsKey("contexts"), query)
        }
    }

    @Test
    fun `GET pinned is not routed to the item route and other methods are 405`() {
        seed(1)
        val response = call("GET", pinnedPath("tokenBudget=100", "maxContexts=10"))
        assertEquals(200, response.status)
        assertFalse(response.json.containsKey("error"))

        for (method in listOf("POST", "PATCH", "PUT", "DELETE")) {
            val rejected = call(method, pinnedPath("tokenBudget=100", "maxContexts=10"), body = if (method == "DELETE") null else "{}")
            assertEquals(405, rejected.status, method)
            assertEquals("METHOD_NOT_ALLOWED", rejected.str("error"), method)
            assertEquals("GET", rejected.headers.firstValue("Allow").orElse(null), method)
        }
    }

    @Test
    fun `unsupported enumeration is 503 PINNED_UNSUPPORTED with no partial result`() {
        // A storage port that does not override enumerateTenant keeps the default Unsupported.
        startWith(PinnedContextReader(NoEnumerationStorage(memory), contentLengthTokens))
        seed(1)
        assertPinnedFailure(call("GET", pinnedPath("tokenBudget=100", "maxContexts=10")), "PINNED_UNSUPPORTED")
    }

    @Test
    fun `too many contexts is 503 PINNED_TOO_LARGE with no partial result`() {
        startWith(PinnedContextReader(memory, contentLengthTokens, maxEnumeratedObjects = 2))
        seed(1)
        seed(2)
        seed(3)
        assertPinnedFailure(call("GET", pinnedPath("tokenBudget=100", "maxContexts=10")), "PINNED_TOO_LARGE")
    }

    @Test
    fun `unavailable enumeration is 503 PINNED_UNAVAILABLE with no partial result`() {
        startWith(PinnedContextReader(EnumerationStub(memory, EnumerationResult.Unavailable), contentLengthTokens))
        seed(1)
        assertPinnedFailure(call("GET", pinnedPath("tokenBudget=100", "maxContexts=10")), "PINNED_UNAVAILABLE")
    }

    @Test
    fun `a required read that is unavailable fails the whole pinned request`() {
        // Enumeration succeeds but the authoritative re-read of one key fails: no partial set may be returned.
        startWith(PinnedContextReader(ReadFailingStorage(memory, failingId = seedId(2)), contentLengthTokens))
        seed(1)
        seed(2)
        assertPinnedFailure(call("GET", pinnedPath("tokenBudget=100", "maxContexts=10")), "PINNED_UNAVAILABLE")
    }

    private fun assertPinnedFailure(response: Resp, code: String) {
        assertEquals(503, response.status)
        assertEquals(code, response.str("error"))
        assertFalse(response.json.containsKey("contexts"), "a failed pinned request must not carry contexts")
        assertFalse(response.json.containsKey("usedTokens"))
        assertFalse(response.json.containsKey("limited"))
    }

    // ---- helpers -----------------------------------------------------------------------------

    private class Resp(val status: Int, val json: JsonObject, val headers: HttpHeaders) {
        fun str(key: String): String = json[key]!!.jsonPrimitive.content
        fun num(key: String): Long = json[key]!!.jsonPrimitive.long
        fun contexts(): List<Resp> = json["contexts"]!!.jsonArray.map { Resp(status, it.jsonObject, headers) }
    }

    /** Delegates everything except enumeration, which keeps the port default (Unsupported). */
    private class NoEnumerationStorage(private val inner: ContextStorage) : ContextStorage {
        override fun get(tenantId: String, id: String): ReadResult = inner.get(tenantId, id)
        override fun createIfAbsent(value: ContextAggregate): WriteResult = inner.createIfAbsent(value)
        override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult =
            inner.compareAndSwap(tenantId, id, expectedStorageRevision, value)
    }

    /** Delegates everything and returns a fixed enumeration result. */
    private class EnumerationStub(private val inner: ContextStorage, private val result: EnumerationResult) : ContextStorage {
        override fun get(tenantId: String, id: String): ReadResult = inner.get(tenantId, id)
        override fun createIfAbsent(value: ContextAggregate): WriteResult = inner.createIfAbsent(value)
        override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult =
            inner.compareAndSwap(tenantId, id, expectedStorageRevision, value)
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult = result
    }

    /** Enumerates normally but fails the authoritative re-read of one key. */
    private class ReadFailingStorage(private val inner: ContextStorage, private val failingId: String) : ContextStorage {
        override fun get(tenantId: String, id: String): ReadResult =
            if (id == failingId) ReadResult.Unavailable else inner.get(tenantId, id)
        override fun createIfAbsent(value: ContextAggregate): WriteResult = inner.createIfAbsent(value)
        override fun compareAndSwap(tenantId: String, id: String, expectedStorageRevision: Long, value: ContextAggregate): WriteResult =
            inner.compareAndSwap(tenantId, id, expectedStorageRevision, value)
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult =
            inner.enumerateTenant(tenantId, maxItems)
    }

    private fun call(
        method: String,
        path: String,
        token: String? = ownerToken,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Resp {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, body?.let { BodyPublishers.ofString(it) } ?: BodyPublishers.noBody())
        val response = http.send(builder.build(), BodyHandlers.ofString())
        return Resp(response.statusCode(), Json.parseToJsonElement(response.body()).jsonObject, response.headers())
    }

    private val createBase: List<Pair<String, Any?>>
        get() = listOf(
            "contextId" to contextId, "requestId" to createRequestId, "kind" to "decision",
            "status" to "confirmed", "title" to "Decision", "summary" to "summary", "content" to "content",
            "scopes" to listOf("project:recl"),
        )

    /** Builds a valid create body; later pairs override earlier ones. */
    private fun createBody(vararg extra: Pair<String, Any?>): String =
        body(*(createBase + extra).toTypedArray())

    private fun body(vararg fields: Pair<String, Any?>): String =
        JsonObject(fields.associate { (name, value) -> name to element(value) }).toString()

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is List<*> -> JsonArray(value.map { element(it) })
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /** Number of stored Contexts (of the IDs used by these tests) across both tenants. */
    private fun countStored(): Int =
        listOf("tenant-a", "tenant-b").sumOf { tenant ->
            listOf(contextId, missingId).count { id -> memory.get(tenant, id) is ReadResult.Found }
        }

    /** Injects a single write fault: a lost response, optionally after the write committed. */
    private class FaultyStorage(private val inner: ContextStorage) : ContextStorage {
        enum class Fault { LOSE_RESPONSE_WITHOUT_COMMIT, COMMIT_THEN_LOSE_RESPONSE }

        var nextWriteFault: Fault? = null

        private fun takeFault(): Fault? = nextWriteFault.also { nextWriteFault = null }

        override fun get(tenantId: String, id: String): ReadResult = inner.get(tenantId, id)

        override fun createIfAbsent(value: ContextAggregate): WriteResult = when (takeFault()) {
            null -> inner.createIfAbsent(value)
            Fault.LOSE_RESPONSE_WITHOUT_COMMIT -> WriteResult.OutcomeUnknown
            Fault.COMMIT_THEN_LOSE_RESPONSE -> {
                inner.createIfAbsent(value)
                WriteResult.OutcomeUnknown
            }
        }

        override fun compareAndSwap(
            tenantId: String,
            id: String,
            expectedStorageRevision: Long,
            value: ContextAggregate,
        ): WriteResult = when (takeFault()) {
            null -> inner.compareAndSwap(tenantId, id, expectedStorageRevision, value)
            Fault.LOSE_RESPONSE_WITHOUT_COMMIT -> WriteResult.OutcomeUnknown
            Fault.COMMIT_THEN_LOSE_RESPONSE -> {
                inner.compareAndSwap(tenantId, id, expectedStorageRevision, value)
                WriteResult.OutcomeUnknown
            }
        }
    }
}
