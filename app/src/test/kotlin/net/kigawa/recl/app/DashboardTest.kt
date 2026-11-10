package net.kigawa.recl.app

import java.net.HttpURLConnection
import java.net.URL
import com.sun.net.httpserver.HttpHandler
import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.WriteResult
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardTest {
    @Test
    fun `dashboard returns html with metrics link`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            val response = request(server, "/dashboard")
            assertEquals(200, response.status)
            assertEquals("text/html; charset=utf-8", response.contentType)
            assertEquals("no-store", response.cacheControl)
            assertTrue(response.body.startsWith("<!doctype html>"))
            assertTrue(response.body.contains("href=\"/dashboard/api/metrics\""))
            assertTrue(response.body.contains("システム情報"))
        }
    }

    @Test
    fun `metrics count all requests and describe actual module wiring`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            request(server, "/health")
            request(server, "/dashboard")
            request(server, "/missing")
            val response = request(server, "/dashboard/api/metrics")
            assertEquals(200, response.status)
            assertEquals("application/json; charset=utf-8", response.contentType)
            assertEquals("no-store", response.cacheControl)
            assertTrue(response.body.contains("\"status\": \"ok\""))
            assertTrue(response.body.contains("\"recordCount\": null"))
            assertTrue(response.body.contains("\"requestCount\": 4"))
            assertTrue(Regex("\"uptimeSeconds\": [0-9]+").containsMatchIn(response.body))
            listOf("javaVersion", "osName", "availableProcessors").forEach { key ->
                assertTrue(response.body.contains("\"$key\":"))
            }
            assertTrue(response.body.contains("\"http\": \"running\""))
            assertTrue(response.body.contains("\"contextApi\": \"disabled\""))
            listOf("fortis", "qdrant", "mcp", "mcpStorage").forEach { module ->
                assertTrue(response.body.contains("\"$module\": \"not_configured\""))
            }
            assertTrue(request(server, "/dashboard/api/metrics").body.contains("\"requestCount\": 5"))
        }
    }

    @Test
    fun `metrics include context record count when storage is available`() {
        val storage = InMemoryContextStorage()
        val context = ContextAggregate(
            tenantId = "default", id = java.util.UUID.randomUUID().toString(), ownerId = "owner",
            kind = ContextKind.DECISION, status = ContextStatus.CONFIRMED,
            title = "Decision", summary = "Summary", content = "Content", sensitivity = Sensitivity.INTERNAL,
        )
        assertTrue(storage.createIfAbsent(context) is WriteResult.Applied)
        val placeholder = HttpHandler { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        ReclHttpServer(host = "127.0.0.1", port = 0, mcp = placeholder, storage = storage).use { server ->
            server.start()
            val response = request(server, "/dashboard/api/metrics")
            assertEquals(200, response.status)
            assertTrue(response.body.contains("\"recordCount\": 1"))
        }
    }

    @Test
    fun `dashboard routes reject unsupported methods and path prefixes`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            listOf("/dashboard", "/dashboard/api/metrics").forEach { path ->
                val response = request(server, path, "POST")
                assertEquals(405, response.status)
                assertEquals("GET, HEAD", response.allow)
                assertEquals("{\"error\":\"method_not_allowed\"}", response.body)
                assertEquals(404, request(server, "$path/extra").status)
            }
            assertEquals(404, request(server, "/health/extra").status)
            assertEquals(200, request(server, "/dashboard?refresh=1").status)
        }
    }

    @Test
    fun `head requests return headers without a body`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            listOf("/health", "/dashboard", "/dashboard/api/metrics").forEach { path ->
                val response = request(server, path, "HEAD")
                assertEquals(200, response.status)
                assertEquals("", response.body)
            }
        }
    }

    private data class Response(
        val status: Int,
        val contentType: String?,
        val cacheControl: String?,
        val allow: String?,
        val body: String,
    )

    private fun request(server: ReclHttpServer, path: String, method: String = "GET"): Response {
        val connection = URL("http://127.0.0.1:${server.address().port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Response(
                status,
                connection.contentType,
                connection.getHeaderField("Cache-Control"),
                connection.getHeaderField("Allow"),
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }
}
