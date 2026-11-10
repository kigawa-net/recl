package net.kigawa.recl.app

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReclHttpServerTest {
    @Test
    fun `root redirects to dashboard`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            val connection = URL("http://127.0.0.1:${server.address().port}/").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            try {
                assertEquals(302, connection.responseCode)
                assertEquals("/dashboard", connection.getHeaderField("Location"))
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun `mcp is not registered by default`() {
        ReclHttpServer(host = "127.0.0.1", port = 0, mcp = null, mcpStorageMode = null).use { server ->
            server.start()
            val connection = URL("http://127.0.0.1:${server.address().port}/mcp").openConnection() as HttpURLConnection
            try {
                assertEquals(404, connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun `health endpoint returns ok`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            val connection = URL("http://127.0.0.1:${server.address().port}/health").openConnection() as HttpURLConnection
            try {
                assertEquals(200, connection.responseCode)
                assertTrue(connection.contentType.startsWith("application/json"))
                assertEquals("{\"status\":\"ok\"}", connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun `health endpoint rejects non get methods`() {
        ReclHttpServer(host = "127.0.0.1", port = 0).use { server ->
            server.start()
            val connection = URL("http://127.0.0.1:${server.address().port}/health").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            try {
                assertEquals(405, connection.responseCode)
                assertEquals("GET, HEAD", connection.getHeaderField("Allow"))
            } finally {
                connection.disconnect()
            }
        }
    }
}
