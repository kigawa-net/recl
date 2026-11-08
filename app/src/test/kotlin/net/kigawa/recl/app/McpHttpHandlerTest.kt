package net.kigawa.recl.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpHttpHandlerTest {
    private val token = mcpEnvironment().getValue("RECL_MCP_TOKEN")
    private val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a6b"
    private val save = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"save_context","arguments":{"contextId":"$contextId","requestId":"019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a10","kind":"decision","status":"proposed","title":"HTTP test","summary":"summary","content":"保存内容"}}}"""
    private val get = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_context","arguments":{"id":"$contextId"}}}"""
    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""

    @Test
    fun `authentication failures cannot execute JSON-RPC writes`() = withServer { server ->
        for (auth in listOf(null, "Bearer wrong", "Basic $token", "Bearer", "Bearer $token extra")) {
            val response = request(server, body = save, authorization = auth)
            assertEquals(401, response.status)
            assertEquals("Bearer", response.authenticate)
            assertFalse(response.body.contains(token))
        }
        val result = result(request(server, body = get))
        assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        assertEquals("NOT_FOUND", payload(result).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `initialize and tool catalog use existing MCP server`() = withServer { server ->
        val response = request(server, body = initialize, contentType = "application/json; charset=utf-8")
        assertEquals(200, response.status)
        assertEquals("application/json; charset=utf-8", response.contentType)
        assertEquals("recl", result(response).getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        val tools = result(request(server, body = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
            .getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue(tools.containsAll(listOf("get_context", "save_context", "get_pinned_contexts", "update_context", "search_context")))
        val notification = request(server, body = """{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertEquals(202, notification.status)
        assertEquals("", notification.body)
    }

    @Test
    fun `transport rejects unsupported methods paths media types and oversized bodies`() = withServer { server ->
        for (method in listOf("GET", "DELETE", "PUT", "HEAD")) {
            val response = request(server, method = method)
            assertEquals(405, response.status)
            assertEquals("POST", response.allow)
        }
        for (path in listOf("/missing", "/mcp/extra", "/mcpx")) {
            val response = request(server, path = path)
            assertEquals(404, response.status)
            assertEquals("{\"error\":\"not_found\"}", response.body)
        }
        assertEquals(415, request(server, body = initialize, contentType = "text/plain").status)
        assertEquals(413, request(server, body = "x".repeat((1 shl 20) + 1)).status)
        // Exactly the limit reaches JSON-RPC parsing rather than the HTTP size rejection.
        assertEquals(200, request(server, body = " ".repeat(1 shl 20)).status)
    }

    @Test
    fun `save and get share storage across requests and metrics reflect enabled MCP`() = withServer { server ->
        assertFalse(result(request(server, body = save)).getValue("isError").jsonPrimitive.boolean)
        val read = result(request(server, body = get))
        assertFalse(read.getValue("isError").jsonPrimitive.boolean)
        assertEquals("保存内容", payload(read).getValue("content").jsonPrimitive.content)
        val metrics = request(server, path = "/dashboard/api/metrics", method = "GET").json()
        val modules = metrics.getValue("modules").jsonObject
        assertEquals("running", modules.getValue("mcp").jsonPrimitive.content)
        assertEquals("in-memory (ephemeral)", modules.getValue("mcpStorage").jsonPrimitive.content)
        assertEquals("3", metrics.getValue("requestCount").jsonPrimitive.content)
        assertFalse(metrics.toString().contains(token))
    }

    @Test
    fun `factory failures return a generic internal error only after authentication`() {
        var created = 0
        val handler = McpHttpHandler(
            serverFactory = { created++; error("private failure details") },
            authenticator = TokenMcpAuthenticator(token, "owner"),
            resourceMetadataUrl = null,
        )
        ReclHttpServer("127.0.0.1", 0, handler).use { server ->
            server.start()
            assertEquals(401, request(server, authorization = null).status)
            assertEquals(0, created)
            val response = request(server, body = initialize)
            assertEquals(500, response.status)
            assertEquals(1, created)
            assertEquals("-32603", response.json().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(response.body.contains("private failure details"))
        }
    }

    @Test
    fun `oidc unauthorized response advertises resource metadata`() = withServer(
        mcpEnvironment() + mapOf(
            "RECL_MCP_AUTH" to "oidc",
            "RECL_OIDC_ISSUER" to "https://user.kigawa.net/realms/kigawa-net",
            "RECL_PUBLIC_URL" to "https://recl.kigawa.net/",
        ),
    ) { server ->
        val response = request(server, authorization = null)
        assertEquals(401, response.status)
        assertEquals("Bearer resource_metadata=\"https://recl.kigawa.net/.well-known/oauth-protected-resource\"", response.authenticate)
    }

    @Test
    fun `oidc request credentials select owners while sharing storage`() {
        val key = com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test").generate()
        val jwksServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        jwksServer.createContext("/jwks") { exchange ->
            exchange.use {
                val body = com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
        }
        jwksServer.start()
        val issuer = "http://127.0.0.1:${jwksServer.address.port}"
        fun credential(user: String): String = com.nimbusds.jwt.SignedJWT(
            com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID(key.keyID).build(),
            com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(issuer).subject(user)
                .expirationTime(java.util.Date.from(java.time.Instant.now().plusSeconds(300))).build(),
        ).apply { sign(com.nimbusds.jose.crypto.RSASSASigner(key)) }.serialize()
        try {
            withServer(mcpEnvironment() + mapOf(
                "RECL_MCP_AUTH" to "oidc", "RECL_OIDC_ISSUER" to issuer, "RECL_OIDC_JWKS_URI" to "$issuer/jwks",
            )) { server ->
                val first = credential("first-owner")
                val second = credential("second-owner")
                assertFalse(result(request(server, body = save, authorization = "Bearer $first")).getValue("isError").jsonPrimitive.boolean)
                assertFalse(result(request(server, body = get, authorization = "Bearer $first")).getValue("isError").jsonPrimitive.boolean)
                val other = result(request(server, body = get, authorization = "Bearer $second"))
                assertEquals("NOT_FOUND", payload(other).getValue("error").jsonPrimitive.content)
            }
        } finally {
            jwksServer.stop(0)
        }
    }

    private fun withServer(env: Map<String, String> = mcpEnvironment(), block: (ReclHttpServer) -> Unit) {
        val runtime = McpRuntime.resolve(env) as McpRuntime.Enabled
        ReclHttpServer("127.0.0.1", 0, runtime.handler, runtime.storageMode).use { server ->
            server.start()
            block(server)
        }
    }

    private data class Response(
        val status: Int, val body: String, val contentType: String?, val authenticate: String?, val allow: String?,
    ) {
        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }

    private fun result(response: Response): JsonObject {
        assertEquals(200, response.status)
        return response.json().getValue("result").jsonObject
    }

    private fun payload(result: JsonObject): JsonObject = Json.parseToJsonElement(
        result.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content,
    ).jsonObject

    private fun request(
        server: ReclHttpServer,
        path: String = "/mcp",
        method: String = "POST",
        body: String = "{}",
        authorization: String? = "Bearer $token",
        contentType: String = "application/json",
    ): Response {
        val connection = URL("http://127.0.0.1:${server.address().port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        connection.setRequestProperty("Content-Type", contentType)
        return try {
            if (method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Response(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
                connection.contentType, connection.getHeaderField("WWW-Authenticate"), connection.getHeaderField("Allow"))
        } finally {
            connection.disconnect()
        }
    }
}
