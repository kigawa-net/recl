package net.kigawa.recl.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.kigawa.recl.core.ContextReader
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.ContextTokenCounter
import net.kigawa.recl.core.EnumerationResult
import net.kigawa.recl.core.PinnedContextReader
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.ReadResult
import net.kigawa.recl.core.SessionAuthenticator
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpContextServerTest {
    private val ownerToken = "token-owner"
    private val sameTenantOtherToken = "token-same-tenant-other-owner"
    private val otherTenantToken = "token-other-tenant-owner"

    private val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b"
    private val secondId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6c"
    private val missingId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4fffff"
    private val createRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a10"
    private val secondRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a11"
    private val thirdRequestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a12"

    private val memory = InMemoryContextStorage()
    private val authenticator = SessionAuthenticator { credential ->
        when (credential) {
            ownerToken -> Principal("tenant-a", "owner")
            sameTenantOtherToken -> Principal("tenant-a", "outsider")
            otherTenantToken -> Principal("tenant-b", "owner")
            else -> null
        }
    }
    private val service = ContextService(memory)
    private val reader = ContextReader(memory)
    private val tokens = ContextTokenCounter { it.title.length + it.content.length }

    private fun serverFor(credential: String, storage: ContextStorage = memory): McpContextServer {
        val tools = McpContextTools(
            service = ContextService(storage),
            reader = ContextReader(storage),
            pinnedReader = PinnedContextReader(storage, tokens),
            authenticator = authenticator,
            bearerCredential = credential,
        )
        return McpContextServer(tools)
    }

    private val server = serverFor(ownerToken)

    // ---- protocol ---------------------------------------------------------------------------

    @Test
    fun `initialize negotiates a supported protocol version and advertises tools`() {
        val response = rpc("initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"claude-code","version":"x"}}""")
        val result = response.result()
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
        assertEquals("recl", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(result["instructions"]!!.jsonPrimitive.content.contains("search_context"))
    }

    @Test
    fun `unknown protocol version falls back to the latest supported version`() {
        val response = rpc("initialize", """{"protocolVersion":"9999-01-01"}""")
        assertEquals(McpContextServer.LATEST_PROTOCOL_VERSION, response.result()["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `initialize without protocolVersion is invalid params`() {
        val response = rpc("initialize", "{}")
        assertEquals(McpContextServer.INVALID_PARAMS, response.errorCode())
    }

    @Test
    fun `notifications never produce a response`() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{}}"""))
        assertNull(server.handle("""{"jsonrpc":"2.0"}"""))
    }

    @Test
    fun `tools list exposes the five tools with strict object schemas`() {
        val tools = rpc("tools/list", "{}").result()["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            setOf("search_context", "get_context", "get_pinned_contexts", "save_context", "update_context"),
            tools.map { it["name"]!!.jsonPrimitive.content }.toSet(),
        )
        for (tool in tools) {
            val schema = tool["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
        }
        val readOnly = tools.filter {
            it["name"]!!.jsonPrimitive.content in setOf("search_context", "get_context", "get_pinned_contexts")
        }
        assertTrue(readOnly.all { it["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun `malformed json is a parse error and a batch is an invalid request`() {
        val parse = parse(server.handle("{not json"))
        assertEquals(McpContextServer.PARSE_ERROR, parse.errorCode())

        val batch = parse(server.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]"""))
        assertEquals(McpContextServer.INVALID_REQUEST, batch.errorCode())
    }

    @Test
    fun `missing jsonrpc, unknown methods and bad params are protocol errors`() {
        assertEquals(McpContextServer.INVALID_REQUEST, rpc("ping", "{}", includeJsonRpc = false).errorCode())
        assertEquals(McpContextServer.METHOD_NOT_FOUND, rpc("resources/list", "{}").errorCode())

        val call = server.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_context","arguments":[]}}""",
        )!!
        assertEquals(McpContextServer.INVALID_PARAMS, parse(call).errorCode())

        val unknown = server.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nope","arguments":{}}}""",
        )!!
        assertEquals(McpContextServer.INVALID_PARAMS, parse(unknown).errorCode())
    }

    @Test
    fun `number and string ids are echoed unchanged`() {
        val stringId = server.handle("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""")!!
        assertEquals("abc", parse(stringId)["id"]!!.jsonPrimitive.content)
        val numberId = server.handle("""{"jsonrpc":"2.0","id":42,"method":"ping"}""")!!
        assertEquals("42", parse(numberId)["id"]!!.jsonPrimitive.content)
    }

    // ---- tools ------------------------------------------------------------------------------

    @Test
    fun `save then get returns the public snapshot without internal fields`() {
        assertFalse(tool("save_context", createArgs()).isError)

        val got = tool("get_context", """{"id":"$contextId"}""")
        assertFalse(got.isError)
        assertEquals(contextId, got.payload["id"]!!.jsonPrimitive.content)
        assertEquals("decision", got.payload["kind"]!!.jsonPrimitive.content)
        assertEquals("confirmed", got.payload["status"]!!.jsonPrimitive.content)
        assertEquals(1, got.payload["contentRevision"]!!.jsonPrimitive.int)
        for (internal in listOf(
            "tenantId", "ownerId", "storageRevision", "indexedContentRevision", "indexAction",
            "operationReceipts", "deleted", "pinned", "payloadDigest",
        )) {
            assertFalse(got.payload.containsKey(internal), "response must not expose $internal")
        }
    }

    @Test
    fun `unverified credential is UNAUTHENTICATED while other owners see NOT_FOUND`() {
        assertFalse(tool("save_context", createArgs()).isError)

        val forged = tool("get_context", """{"id":"$contextId"}""", server = serverFor("forged"))
        assertTrue(forged.isError)
        assertEquals("UNAUTHENTICATED", forged.code())

        val missing = tool("get_context", """{"id":"$missingId"}""")
        assertEquals("NOT_FOUND", missing.code())

        for (credential in listOf(sameTenantOtherToken, otherTenantToken)) {
            val other = tool("get_context", """{"id":"$contextId"}""", server = serverFor(credential))
            assertTrue(other.isError)
            assertEquals("NOT_FOUND", other.code())
            assertEquals(missing.payload, other.payload, "existence must not leak")
        }
    }

    @Test
    fun `unknown and null arguments are rejected without writing`() {
        val unknown = tool("save_context", createArgs(extra = ""","tenantId":"tenant-a""""))
        assertTrue(unknown.isError)
        assertEquals("INVALID_ARGUMENT", unknown.code())
        assertEquals(ReadResult.NotFound, memory.get("tenant-a", contextId))

        val nullField = tool("save_context", createArgs(extra = ""","sensitivity":null"""))
        assertEquals("INVALID_ARGUMENT", nullField.code())
    }

    @Test
    fun `uppercase UUIDv7 is canonicalised`() {
        assertFalse(tool("save_context", createArgs(id = contextId.uppercase())).isError)
        assertFalse(tool("get_context", """{"id":"$contextId"}""").isError)
    }

    @Test
    fun `same requestId with the same payload replays and a different payload is rejected`() {
        val first = tool("save_context", createArgs())
        assertEquals(false, first.payload["replayed"]!!.jsonPrimitive.boolean)

        val replay = tool("save_context", createArgs())
        assertEquals(false, replay.isError)
        assertEquals(true, replay.payload["replayed"]!!.jsonPrimitive.boolean)

        val reused = tool("save_context", createArgs(title = "Different"))
        assertTrue(reused.isError)
        assertEquals("REQUEST_ID_REUSED", reused.code())
    }

    @Test
    fun `policy violations are reported as tool errors and never stored`() {
        val superseded = tool("save_context", createArgs(status = "superseded"))
        assertEquals("INVALID_ARGUMENT", superseded.code())

        val restricted = tool("save_context", createArgs(extra = ""","sensitivity":"restricted""""))
        assertEquals("REJECTED_BY_POLICY", restricted.code())

        val inferredConfirmed = tool(
            "save_context",
            createArgs(extra = ""","sources":[{"sourceId":"s1","type":"github_issue","uri":"https://github.com/kigawa-net/recl/issues/7","trust":"agent_inferred"}]"""),
        )
        assertEquals("REJECTED_BY_POLICY", inferredConfirmed.code())
        assertEquals(ReadResult.NotFound, memory.get("tenant-a", contextId))
    }

    @Test
    fun `submitted sources stay public citations and are never verified`() {
        val created = tool(
            "save_context",
            createArgs(extra = ""","sources":[{"sourceId":"s1","type":"github_issue","uri":"https://github.com/kigawa-net/recl/issues/7","trust":"primary_source"}]"""),
        )
        assertFalse(created.isError)

        val source = tool("get_context", """{"id":"$contextId"}""").payload["sources"]!!.jsonArray.single().jsonObject
        assertEquals("github_issue", source["type"]!!.jsonPrimitive.content)
        assertEquals("primary_source", source["trust"]!!.jsonPrimitive.content)
        assertEquals("unverified", source["verification"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stale expectedContentRevision returns the current revision and does not mutate`() {
        tool("save_context", createArgs())
        val updated = tool("update_context", updateArgs(expected = 1, title = "Renamed"))
        assertFalse(updated.isError)
        assertEquals(2, updated.payload["contentRevision"]!!.jsonPrimitive.int)

        val stale = tool("update_context", updateArgs(expected = 1, title = "Stale", requestId = thirdRequestId))
        assertTrue(stale.isError)
        assertEquals("REVISION_CONFLICT", stale.code())
        assertEquals(2, stale.payload["currentContentRevision"]!!.jsonPrimitive.int)
        assertEquals("Renamed", tool("get_context", """{"id":"$contextId"}""").payload["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty patch and clearing via null are rejected`() {
        tool("save_context", createArgs())
        val empty = tool("update_context", """{"id":"$contextId","requestId":"$secondRequestId","expectedContentRevision":1}""")
        assertEquals("INVALID_ARGUMENT", empty.code())
        val nullField = tool(
            "update_context",
            """{"id":"$contextId","requestId":"$secondRequestId","expectedContentRevision":1,"content":null}""",
        )
        assertEquals("INVALID_ARGUMENT", nullField.code())
    }

    @Test
    fun `pinned contexts are selected under budget and report truncation`() {
        tool("save_context", createArgs(id = contextId, pinned = true))
        tool("save_context", createArgs(id = secondId, requestId = secondRequestId, pinned = false))

        val selected = tool("get_pinned_contexts", """{"tokenBudget":1000,"maxContexts":10}""")
        assertFalse(selected.isError)
        val contexts = selected.payload["contexts"]!!.jsonArray
        assertEquals(1, contexts.size)
        assertEquals(contextId, contexts.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(selected.payload["usedTokens"]!!.jsonPrimitive.int > 0)
        assertEquals(false, selected.payload["limited"]!!.jsonPrimitive.boolean)

        val tooSmall = tool("get_pinned_contexts", """{"tokenBudget":1,"maxContexts":10}""")
        assertEquals(0, tooSmall.payload["contexts"]!!.jsonArray.size)
        assertEquals(true, tooSmall.payload["limited"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pinned query rejects out-of-range budgets and unsupported enumeration`() {
        assertEquals("INVALID_ARGUMENT", tool("get_pinned_contexts", """{"tokenBudget":0,"maxContexts":10}""").code())
        assertEquals("INVALID_ARGUMENT", tool("get_pinned_contexts", """{"tokenBudget":10,"maxContexts":101}""").code())

        val unsupported = serverFor(ownerToken, NoEnumerationStorage(memory))
        val result = tool("get_pinned_contexts", """{"tokenBudget":1000,"maxContexts":10}""", server = unsupported)
        assertTrue(result.isError)
        assertEquals("PINNED_UNSUPPORTED", result.code())
    }

    @Test
    fun `search is explicitly unsupported and returns no candidates`() {
        val result = tool("search_context", """{"query":"anything"}""")
        assertTrue(result.isError)
        assertEquals("SEARCH_UNSUPPORTED", result.code())
        assertFalse(result.payload.containsKey("contexts"))
    }

    @Test
    fun `authoritative read failure is UNAVAILABLE and never returns cached data`() {
        assertFalse(tool("save_context", createArgs()).isError)
        val unavailable = serverFor(ownerToken, UnavailableStorage(memory))
        val result = tool("get_context", """{"id":"$contextId"}""", server = unavailable)
        assertTrue(result.isError)
        assertEquals("UNAVAILABLE", result.code())
    }

    // ---- helpers ----------------------------------------------------------------------------

    private data class ToolCall(val payload: JsonObject, val isError: Boolean) {
        fun code(): String = payload["error"]!!.jsonPrimitive.content
    }

    private fun tool(name: String, arguments: String, server: McpContextServer = this.server): ToolCall {
        val response = parse(
            server.handle(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""",
            )!!,
        )
        val result = response.result()
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        return ToolCall(Json.parseToJsonElement(text).jsonObject, result["isError"]!!.jsonPrimitive.boolean)
    }

    private fun rpc(
        method: String,
        params: String,
        includeJsonRpc: Boolean = true,
    ): JsonObject {
        val prefix = if (includeJsonRpc) """"jsonrpc":"2.0",""" else ""
        return parse(server.handle("""{$prefix"id":1,"method":"$method","params":$params}""")!!)
    }

    private fun parse(raw: String?): JsonObject {
        assertNotNull(raw)
        return Json.parseToJsonElement(raw!!).jsonObject
    }

    private fun JsonObject.result(): JsonObject = this["result"]!!.jsonObject

    private fun JsonObject.errorCode(): Int = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    private fun createArgs(
        id: String = contextId,
        requestId: String = createRequestId,
        title: String = "Decision",
        status: String = "confirmed",
        pinned: Boolean = false,
        extra: String = "",
    ): String = """{"contextId":"$id","requestId":"$requestId","kind":"decision","status":"$status",""" +
        """"title":"$title","summary":"summary","content":"content","pinned":$pinned$extra}"""

    private fun updateArgs(
        expected: Int,
        title: String,
        requestId: String = secondRequestId,
    ): String = """{"id":"$contextId","requestId":"$requestId","expectedContentRevision":$expected,"title":"$title"}"""

    /** Delegates everything except enumeration, which the storage port does not support. */
    private class NoEnumerationStorage(private val inner: ContextStorage) : ContextStorage by inner {
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult =
            EnumerationResult.Unsupported
    }

    private class UnavailableStorage(private val inner: ContextStorage) : ContextStorage by inner {
        override fun get(tenantId: String, id: String): ReadResult = ReadResult.Unavailable
        override fun enumerateTenant(tenantId: String, maxItems: Int): EnumerationResult =
            EnumerationResult.Unavailable
    }
}
