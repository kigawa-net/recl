package net.kigawa.recl.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.recl.core.ContextReader
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextTokenCounter
import net.kigawa.recl.core.PinnedContextReader
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.SessionAuthenticator
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class McpStdioTransportTest {
    private val memory = InMemoryContextStorage()
    private val authenticator = SessionAuthenticator { credential ->
        if (credential == "token-owner") Principal("tenant-a", "owner") else null
    }
    private val server = McpContextServer(
        McpContextTools(
            service = ContextService(memory),
            reader = ContextReader(memory),
            pinnedReader = PinnedContextReader(memory, ContextTokenCounter { it.content.length }),
            authenticator = authenticator,
            bearerCredential = "token-owner",
        ),
    )

    @Test
    fun `newline delimited messages produce one response each and notifications produce none`() {
        val output = run(
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
            {"jsonrpc":"2.0","method":"notifications/initialized"}

            {"jsonrpc":"2.0","id":2,"method":"ping"}
            {broken
            """.trimIndent() + "\n",
        )

        val lines = output.trim().lines()
        assertEquals(3, lines.size, output)
        assertEquals("1", Json.parseToJsonElement(lines[0]).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("2", Json.parseToJsonElement(lines[1]).jsonObject["id"]!!.jsonPrimitive.content)
        val parseError = Json.parseToJsonElement(lines[2]).jsonObject["error"]!!.jsonObject
        assertEquals(McpContextServer.PARSE_ERROR, parseError["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a final line without a newline is still processed`() {
        val output = run("""{"jsonrpc":"2.0","id":7,"method":"ping"}""")
        val response = Json.parseToJsonElement(output.trim()).jsonObject
        assertEquals("7", response["id"]!!.jsonPrimitive.content)
        assertTrue(response.containsKey("result"))
    }

    @Test
    fun `CRLF input is accepted`() {
        val output = run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\r\n")
        assertTrue(Json.parseToJsonElement(output.trim()).jsonObject.containsKey("result"))
    }

    @Test
    fun `an oversized message is rejected without being executed or echoed`() {
        val huge = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\",\"params\":{\"pad\":\"" + "a".repeat(400) + "\"}}"
        val output = run("$huge\n" + """{"jsonrpc":"2.0","id":10,"method":"ping"}""" + "\n", limit = 64)

        val lines = output.trim().lines()
        assertEquals(2, lines.size, output)
        val rejected = Json.parseToJsonElement(lines[0]).jsonObject
        assertNull(rejected["id"]?.jsonPrimitive?.contentOrNull)
        val error = rejected["error"]!!.jsonObject
        assertEquals(McpContextServer.INVALID_REQUEST, error["code"]!!.jsonPrimitive.int)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("byte limit"))
        assertTrue(!output.contains("aaaa"))

        val ping = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals("10", ping["id"]!!.jsonPrimitive.content)
        assertTrue(ping.containsKey("result"))
    }

    @Test
    fun `notification-only input closes without output`() {
        assertEquals("", run("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n"))
    }

    private fun run(input: String, limit: Int = 1 shl 20): String {
        val output = ByteArrayOutputStream()
        McpStdioTransport(
            server = server,
            input = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
            output = output,
            maxMessageBytes = limit,
        ).run()
        return output.toString(Charsets.UTF_8)
    }
}
