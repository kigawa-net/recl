package net.kigawa.recl.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import net.kigawa.recl.mcp.McpContextServer
import java.io.IOException

/** Stateless POST transport; authentication is checked before any JSON-RPC execution. */
class McpHttpHandler(
    private val serverFactory: (credential: String) -> McpContextServer,
    private val authenticator: McpRequestAuthenticator,
    private val resourceMetadataUrl: String?,
) : HttpHandler {

    override fun handle(exchange: HttpExchange) {
        try {
            exchange.responseHeaders.set("Cache-Control", "no-store")
            try {
                dispatch(exchange)
            } catch (_: RuntimeException) {
                respond(exchange, 500, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"internal error\"}}")
            }
        } catch (_: IOException) {
            // A disconnected client must not terminate the HTTP process or expose details.
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(exchange: HttpExchange) {
        if (exchange.requestURI.path != "/mcp") {
            respond(exchange, 404, "{\"error\":\"not_found\"}")
            return
        }
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.set("Allow", "POST")
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
            return
        }
        val authorization = exchange.requestHeaders["Authorization"]?.singleOrNull()
        val credential = authorization?.let { BEARER.matchEntire(it)?.groupValues?.get(1) }
        if (credential == null || authenticator.authenticate(credential) == null) {
            exchange.responseHeaders.set("WWW-Authenticate", resourceMetadataUrl?.let { "Bearer resource_metadata=\"$it\"" } ?: "Bearer")
            respond(exchange, 401, "{\"error\":\"unauthorized\"}")
            return
        }
        val contentType = exchange.requestHeaders["Content-Type"]?.singleOrNull()
            ?.substringBefore(';')?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) {
            respond(exchange, 415, "{\"error\":\"unsupported_media_type\"}")
            return
        }
        val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) {
            respond(exchange, 413, "{\"error\":\"payload_too_large\"}")
            return
        }
        val response = serverFactory(credential).handle(body.toString(Charsets.UTF_8))
        if (response == null) exchange.sendResponseHeaders(202, -1)
        else respond(exchange, 200, response)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, if (exchange.requestMethod == "HEAD") -1 else bytes.size.toLong())
        if (exchange.requestMethod != "HEAD") exchange.responseBody.write(bytes)
    }

    companion object {
        const val MAX_BODY_BYTES = 1 shl 20
        private val BEARER = Regex("Bearer (\\S+)", RegexOption.IGNORE_CASE)
    }
}
