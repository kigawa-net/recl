package net.kigawa.recl.app

import java.net.HttpURLConnection
import java.net.URL
import com.sun.net.httpserver.HttpHandler
import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.WriteResult
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.EnumerationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardTest {
    @Test
    fun `contexts without MCP returns an empty JSON array`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            val response = request(server, "/dashboard/contexts")
            assertEquals(200, response.status)
            assertEquals("application/json; charset=utf-8", response.contentType)
            assertEquals("no-store", response.cacheControl)
            assertEquals("[]", response.body)
            assertEquals(404, request(server, "/dashboard/contexts/missing").status)
        }
    }

    @Test
    fun `contexts return all specified fields from MCP storage`() {
        val storage = InMemoryContextStorage()
        val context = context().copy(title = "Decision \"quoted\"\n日本語", pinned = true, validFromEpochMillis = 123)
        assertTrue(storage.createIfAbsent(context) is WriteResult.Applied)
        withStorage(storage) { server ->
            val response = request(server, "/dashboard/contexts")
            assertEquals(200, response.status)
            val items = Json.parseToJsonElement(response.body).jsonArray
            assertEquals(1, items.size)
            val item = items.single().jsonObject
            assertEquals(setOf("id", "kind", "status", "title", "summary", "ownerId", "sensitivity",
                "validFromEpochMillis", "validUntilEpochMillis", "contentRevision", "pinned"), item.keys)
            mapOf("id" to context.id, "kind" to "DECISION", "status" to "CONFIRMED", "title" to context.title,
                "summary" to "Summary", "ownerId" to "owner", "sensitivity" to "INTERNAL",
                "validFromEpochMillis" to "123", "contentRevision" to "1", "pinned" to "true").forEach { (key, value) ->
                assertEquals(value, item.getValue(key).jsonPrimitive.content)
            }
            assertEquals(JsonNull, item["validUntilEpochMillis"])
        }
    }

    @Test
    fun `unsupported enumeration returns empty contexts`() {
        val storage = object : ContextStorage by InMemoryContextStorage() {
            override fun enumerateTenant(tenantId: String, maxItems: Int) = EnumerationResult.Unsupported
        }
        withStorage(storage) { server ->
            val response = request(server, "/dashboard/contexts")
            assertEquals(200, response.status)
            assertEquals("[]", response.body)
        }
    }

    @Test
    fun `contexts filter by partial search and status together`() {
        val storage = InMemoryContextStorage()
        val confirmed = context().copy(title = "Database choice", summary = "採用した構成")
        val proposed = context().copy(title = "Database proposal", status = ContextStatus.PROPOSED)
        val archived = context().copy(title = "Legacy choice", status = ContextStatus.ARCHIVED)
        listOf(confirmed, proposed, archived, context().copy(tenantId = "other")).forEach {
            assertTrue(storage.createIfAbsent(it) is WriteResult.Applied)
        }
        withStorage(storage) { server ->
            fun ids(query: String): Set<String> {
                val response = request(server, "/dashboard/contexts$query")
                assertEquals(200, response.status)
                return Json.parseToJsonElement(response.body).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            }
            assertEquals(setOf(confirmed.id, proposed.id, archived.id), ids(""))
            assertEquals(setOf(confirmed.id, proposed.id), ids("?search=BASE"))
            assertEquals(setOf(confirmed.id), ids("?search=%E6%8E%A1%E7%94%A8"))
            assertEquals(setOf(confirmed.id), ids("?status=CONFIRMED"))
            assertEquals(setOf(proposed.id), ids("?status=PROPOSED"))
            assertEquals(setOf(archived.id), ids("?status=ARCHIVED"))
            assertEquals(setOf(proposed.id), ids("?search=database&status=PROPOSED"))
            assertEquals(emptySet(), ids("?search=missing"))
            assertEquals(emptySet(), ids("?search=database&status=ARCHIVED"))
        }
    }

    @Test
    fun `context detail returns escaped HTML and missing contexts return 404`() {
        val storage = InMemoryContextStorage()
        val context = context().copy(title = "Decision <script>alert(1)</script>", content = "<b>Content</b> & text",
            scopes = setOf("project:test"), validFromEpochMillis = 123, validUntilEpochMillis = 456, pinned = true)
        assertTrue(storage.createIfAbsent(context) is WriteResult.Applied)
        val other = context().copy(tenantId = "other")
        assertTrue(storage.createIfAbsent(other) is WriteResult.Applied)
        withStorage(storage) { server ->
            val response = request(server, "/dashboard/contexts/${context.id}")
            assertEquals(200, response.status)
            assertEquals("text/html; charset=utf-8", response.contentType)
            assertEquals("no-store", response.cacheControl)
            listOf("Decision &lt;script&gt;alert(1)&lt;/script&gt;", "DECISION", "CONFIRMED", "owner", "Summary",
                "&lt;b&gt;Content&lt;/b&gt; &amp; text", "project:test", "INTERNAL", "123", "456", "true",
                "href=\"/dashboard\"").forEach { assertTrue(response.body.contains(it), it) }
            assertTrue(!response.body.contains("<script>"))
            listOf("missing", other.id).forEach { id ->
                val missing = request(server, "/dashboard/contexts/$id")
                assertEquals(404, missing.status)
                assertEquals("text/html; charset=utf-8", missing.contentType)
                assertTrue(missing.body.contains("見つかりません"))
            }
            val head = request(server, "/dashboard/contexts/${context.id}", "HEAD")
            assertEquals(200, head.status)
            assertEquals("", head.body)
            assertEquals(405, request(server, "/dashboard/contexts/${context.id}", "POST").status)
        }
    }

    private fun context() = ContextAggregate(
        tenantId = "default", id = java.util.UUID.randomUUID().toString(), ownerId = "owner",
        kind = ContextKind.DECISION, status = ContextStatus.CONFIRMED,
        title = "Decision", summary = "Summary", content = "Content", sensitivity = Sensitivity.INTERNAL,
    )

    private fun withStorage(storage: ContextStorage, test: (ReclHttpServer) -> Unit) {
        val placeholder = HttpHandler { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
        ReclHttpServer(host = "127.0.0.1", port = 0, mcp = placeholder, storage = storage).use { server ->
            server.start()
            test(server)
        }
    }

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
            listOf("/dashboard", "/dashboard/api/metrics", "/dashboard/contexts").forEach { path ->
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
            listOf("/health", "/dashboard", "/dashboard/api/metrics", "/dashboard/contexts").forEach { path ->
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
