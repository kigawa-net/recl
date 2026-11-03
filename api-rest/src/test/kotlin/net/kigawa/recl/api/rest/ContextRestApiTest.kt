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
import kotlinx.serialization.json.long
import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextReader
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.SessionAuthenticator
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
        val api = ContextRestApi(ContextService(faulty), ContextReader(faulty), authenticator)
        server = startContextRestServer(api, InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        port = server.address.port
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
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

    // ---- helpers -----------------------------------------------------------------------------

    private class Resp(val status: Int, val json: JsonObject, val headers: HttpHeaders) {
        fun str(key: String): String = json[key]!!.jsonPrimitive.content
        fun num(key: String): Long = json[key]!!.jsonPrimitive.long
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
