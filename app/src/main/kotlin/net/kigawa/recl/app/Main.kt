package net.kigawa.recl.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** Minimal HTTP process. The context API remains disabled until durable storage is configured. */
class ReclHttpServer(
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.createContext("/health", ::health)
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
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    require(port in 1..65535) { "PORT must be between 1 and 65535" }
    val server = ReclHttpServer(port = port)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start()
    println("recl HTTP server listening on ${server.address()}")
}
