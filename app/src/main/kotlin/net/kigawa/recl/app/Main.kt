package net.kigawa.recl.app

import com.sun.net.httpserver.Filter
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/** HTTP process with an explicitly gated, ephemeral MCP runtime. */
class ReclHttpServer(
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
    mcp: HttpHandler? = null,
    mcpStorageMode: String? = null,
    metadata: HttpHandler? = null,
    storage: net.kigawa.recl.core.ContextStorage? = null,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(host, port), 0)
    private val dashboard = Dashboard(mcp != null, mcpStorageMode, storage)

    init {
        val requestCounter = Filter.beforeHandler("Count incoming HTTP requests") { dashboard.recordRequest() }
        buildList {
            if (metadata != null) add(server.createContext("/.well-known/oauth-protected-resource", metadata))
            if (mcp != null) add(server.createContext("/mcp", mcp))
            addAll(
                listOf(
                    server.createContext("/health", ::health),
                    server.createContext("/dashboard", dashboard::page),
                    server.createContext("/dashboard/api/metrics", dashboard::metrics),
                    server.createContext("/dashboard/contexts", dashboard::contexts),
                    server.createContext("/dashboard/contexts/", dashboard::contextDetail),
                    server.createContext("/") { exchange ->
                        if (exchange.requestURI.path == "/") {
                            respondRedirect(exchange, 302, "/dashboard")
                        } else {
                            respond(exchange, 404, "{\"error\":\"not_found\"}")
                        }
                    },
                ),
            )
        }.forEach { context ->
            context.filters.add(requestCounter)
        }
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
    }

    fun start() = server.start()

    fun address(): InetSocketAddress = server.address

    override fun close() = server.stop(0)

    private fun health(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestURI.path != "/health") {
                respond(exchange, 404, "{\"error\":\"not_found\"}")
            } else if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
            } else {
                respond(exchange, 200, "{\"status\":\"ok\"}", exchange.requestMethod == "HEAD")
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, head: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, if (head) -1 else bytes.size.toLong())
        if (!head) exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondRedirect(exchange: HttpExchange, status: Int, location: String) {
        exchange.responseHeaders.set("Location", location)
        respond(exchange, status, "")
    }
}

fun main() {
    val mcp = McpRuntime.resolve(System.getenv())
    if (mcp is McpRuntime.Rejected) {
        System.err.println("recl refused to start: ${mcp.reason}")
        exitProcess(2)
    }
    val enabled = mcp as? McpRuntime.Enabled
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    require(port in 1..65535) { "PORT must be between 1 and 65535" }
    val server = ReclHttpServer(port = port, mcp = enabled?.handler, mcpStorageMode = enabled?.storageMode, metadata = enabled?.metadataHandler, storage = enabled?.storage)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start()
    println("recl MCP ${if (enabled != null) "enabled" else "disabled"}; auth: ${enabled?.authMode ?: "not_configured"}; storage: ${enabled?.storageMode ?: "not_configured"}")
    println("recl HTTP server listening on ${server.address()}")
}
