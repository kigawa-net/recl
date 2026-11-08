package net.kigawa.recl.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OAuthProtectedResourceMetadataTest {
    private val paths = listOf(
        "/.well-known/oauth-protected-resource",
        "/.well-known/oauth-protected-resource/",
        "/.well-known/oauth-protected-resource/mcp",
        "/.well-known/oauth-protected-resource/mcp/",
    )

    @Test
    fun `oidc discovery returns public resource issuer bearer method and scope on both paths`() = withServer(
        oidcEnvironment() + ("RECL_OIDC_REQUIRED_SCOPE" to "openid"),
    ) { server ->
        for (path in paths) {
            val response = request(server, path)
            assertEquals(200, response.status)
            assertTrue(response.contentType.orEmpty().startsWith("application/json"))
            val body = Json.parseToJsonElement(response.body).jsonObject
            assertEquals("https://recl.kigawa.net", body.getValue("resource").jsonPrimitive.content)
            assertEquals(listOf("https://user.kigawa.net/realms/kigawa-net"),
                body.getValue("authorization_servers").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("header"), body.getValue("bearer_methods_supported").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("recl", body.getValue("resource_name").jsonPrimitive.content)
            assertEquals(listOf("openid"), body.getValue("scopes_supported").jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun `optional scope is an empty array`() = withServer(oidcEnvironment()) { server ->
        val body = Json.parseToJsonElement(request(server, paths.first()).body).jsonObject
        assertTrue(body.getValue("scopes_supported").jsonArray.isEmpty())
    }

    @Test
    fun `head succeeds without a response body`() = withServer(oidcEnvironment()) { server ->
        for (path in paths) {
            val response = request(server, path, "HEAD")
            assertEquals(200, response.status)
            assertEquals("", response.body)
            assertTrue(response.contentType.orEmpty().startsWith("application/json"))
        }
    }

    @Test
    fun `post is rejected with allowed methods`() = withServer(oidcEnvironment()) { server ->
        for (path in paths) {
            val response = request(server, path, "POST")
            assertEquals(405, response.status)
            assertEquals("GET, HEAD", response.allow)
        }
    }

    @Test
    fun `discovery is absent when oidc is disabled or public url is missing`() {
        for (env in listOf(emptyMap(), mcpEnvironment(), oidcEnvironment() - "RECL_PUBLIC_URL")) {
            withServer(env) { server ->
                for (path in paths) assertEquals(404, request(server, path).status)
            }
        }
    }

    @Test
    fun `metadata requests are counted and unrelated paths stay absent`() = withServer(oidcEnvironment()) { server ->
        assertEquals(200, request(server, paths.first()).status)
        assertEquals(200, request(server, paths.last(), "HEAD").status)
        assertEquals(405, request(server, paths.first(), "POST").status)
        assertEquals(404, request(server, "/.well-known/oauth-protected-resource/other").status)
        val metrics = Json.parseToJsonElement(request(server, "/dashboard/api/metrics").body).jsonObject
        assertEquals("5", metrics.getValue("requestCount").jsonPrimitive.content)
    }

    private fun withServer(env: Map<String, String>, block: (ReclHttpServer) -> Unit) {
        val runtime = McpRuntime.resolve(env)
        if (env["RECL_MCP_ENABLED"] == "1") assertIs<McpRuntime.Enabled>(runtime)
        val enabled = runtime as? McpRuntime.Enabled
        ReclHttpServer("127.0.0.1", 0, enabled?.handler, enabled?.storageMode, enabled?.metadataHandler).use { server ->
            server.start()
            block(server)
        }
    }

    private data class Response(val status: Int, val body: String, val contentType: String?, val allow: String?)

    private fun request(server: ReclHttpServer, path: String, method: String = "GET"): Response {
        val connection = URL("http://127.0.0.1:${server.address().port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            val status = connection.responseCode
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Response(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
                connection.contentType, connection.getHeaderField("Allow"))
        } finally {
            connection.disconnect()
        }
    }
}
