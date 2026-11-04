package net.kigawa.recl.devmcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class DevMcpMainTest {
    private val token = "dev-token-0123456789abcdef"

    private fun env(overrides: Map<String, String?> = emptyMap()): Map<String, String> {
        val base = mutableMapOf(
            DevMcpConfig.ALLOW_ENV to "1",
            DevMcpConfig.TOKEN_ENV to token,
            DevMcpConfig.TENANT_ENV to "tenant-dev",
            DevMcpConfig.OWNER_ENV to "owner-dev",
        )
        overrides.forEach { (key, value) -> if (value == null) base.remove(key) else base[key] = value }
        return base
    }

    private data class Run(val exit: Int, val stdout: String, val stderr: String)

    private fun run(input: String, env: Map<String, String> = env()): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = DevMcpMain.run(
            env = env,
            input = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
            output = out,
            error = PrintStream(err, true, Charsets.UTF_8),
        )
        return Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `refuses to start without the explicit ephemeral acknowledgement`() {
        val result = run("", env(mapOf(DevMcpConfig.ALLOW_ENV to null)))
        assertEquals(2, result.exit)
        assertTrue(result.stderr.contains("refused to start"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `refuses weak or missing configuration`() {
        assertEquals(2, run("", env(mapOf(DevMcpConfig.TOKEN_ENV to "short"))).exit)
        assertEquals(2, run("", env(mapOf(DevMcpConfig.TOKEN_ENV to null))).exit)
        assertEquals(2, run("", env(mapOf(DevMcpConfig.TENANT_ENV to "has space"))).exit)
        assertEquals(2, run("", env(mapOf(DevMcpConfig.OWNER_ENV to null))).exit)
    }

    @Test
    fun `starts with a warning and serves MCP over stdio`() {
        val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b"
        val requestId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a10"
        val input = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"save_context","arguments":{"contextId":"$contextId","requestId":"$requestId","kind":"decision","status":"confirmed","title":"Dev","summary":"S","content":"C"}}}
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_context","arguments":{"id":"$contextId"}}}
        """.trimIndent() + "\n"

        val result = run(input)
        assertEquals(0, result.exit)
        assertTrue(result.stderr.contains("EPHEMERAL"), result.stderr)

        val lines = result.stdout.trim().lines()
        assertEquals(3, lines.size, result.stdout)
        val init = Json.parseToJsonElement(lines[0]).jsonObject["result"]!!.jsonObject
        assertEquals("recl", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val saved = Json.parseToJsonElement(lines[1]).jsonObject["result"]!!.jsonObject
        assertEquals(false, saved["isError"]!!.jsonPrimitive.content.toBoolean())

        val read = Json.parseToJsonElement(lines[2]).jsonObject["result"]!!.jsonObject
        val text = read["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("\"title\":\"Dev\""), text)
        assertFalse(text.contains("tenantId"), "must not expose tenant internals")
        assertFalse(text.contains("ownerId"), "must not expose owner internals")
    }

    @Test
    fun `the configured principal is the only identity the session can use`() {
        // The launcher owns the credential; tool arguments cannot select another tenant/owner.
        val result = run(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"save_context","arguments":{"contextId":"019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b","requestId":"019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a10","kind":"decision","status":"confirmed","title":"T","summary":"S","content":"C","tenantId":"attacker"}}}""" + "\n",
        )
        val response = Json.parseToJsonElement(result.stdout.trim()).jsonObject["result"]!!.jsonObject
        assertEquals(true, response["isError"]!!.jsonPrimitive.content.toBoolean())
        val payload = Json.parseToJsonElement(
            response["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertEquals("INVALID_ARGUMENT", payload["error"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("tenantId"))
    }

    @Test
    fun `exit code is success when the client closes stdin immediately`() {
        val result = run("")
        assertEquals(0, result.exit)
        assertTrue(result.stderr.contains("WARNING"), result.stderr)
        assertEquals("", result.stdout)
    }
}
