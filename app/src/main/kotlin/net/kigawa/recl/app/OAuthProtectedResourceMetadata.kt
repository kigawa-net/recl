package net.kigawa.recl.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OAuthProtectedResourceMetadata(publicUrl: String, issuer: String, requiredScope: String? = null) : HttpHandler {
    private val body = buildJsonObject {
        put("resource", publicUrl)
        put("authorization_servers", JsonArray(listOf(JsonPrimitive(issuer))))
        put("bearer_methods_supported", JsonArray(listOf(JsonPrimitive("header"))))
        put("resource_name", "recl")
        put("scopes_supported", JsonArray(listOfNotNull(requiredScope?.let(::JsonPrimitive))))
    }.toString()

    override fun handle(exchange: HttpExchange) {
        exchange.use {
            val path = exchange.requestURI.path.trimEnd('/')
            val status: Int
            val response: String
            if (path !in setOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
                status = 404
                response = "{\"error\":\"not_found\"}"
            } else if (exchange.requestMethod !in setOf("GET", "HEAD")) {
                status = 405
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                response = "{\"error\":\"method_not_allowed\"}"
            } else {
                status = 200
                response = body
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            val head = exchange.requestMethod == "HEAD"
            exchange.sendResponseHeaders(status, if (head) -1 else bytes.size.toLong())
            if (!head) exchange.responseBody.write(bytes)
        }
    }
}
