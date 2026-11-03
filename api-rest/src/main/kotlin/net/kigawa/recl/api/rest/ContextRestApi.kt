package net.kigawa.recl.api.rest

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.recl.core.ContextIds
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextPatch
import net.kigawa.recl.core.ContextReadResult
import net.kigawa.recl.core.ContextReader
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextSnapshot
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.CreateContextMutation
import net.kigawa.recl.core.DeleteContextMutation
import net.kigawa.recl.core.MutationResult
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.SessionAuthenticator
import net.kigawa.recl.core.TrustedContextQueryGateway
import net.kigawa.recl.core.TrustedMutationGateway
import net.kigawa.recl.core.UpdateContextMutation
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

/**
 * JDK HttpServer adapter for the Context CRUD endpoints (recl #4, first slice).
 *
 * Clients never send tenantId, ownerId, Principal, digest or verification. The bearer credential is
 * verified by the injected [SessionAuthenticator] inside the core gateways. This class is NOT a
 * production authenticator; see docs/rest-context-api.md.
 */
class ContextRestApi(
    private val service: ContextService,
    private val reader: ContextReader,
    private val authenticator: SessionAuthenticator,
) : HttpHandler {

    override fun handle(exchange: HttpExchange) {
        try {
            val response = try {
                dispatch(exchange)
            } catch (e: ApiException) {
                e.toResponse()
            } catch (_: RuntimeException) {
                ApiResponse(500, errorBody("INTERNAL_ERROR", "unexpected server error"))
            }
            respond(exchange, response)
        } catch (_: IOException) {
            // The client disconnected before the response was written.
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(ex: HttpExchange): ApiResponse {
        val segments = (ex.requestURI.rawPath ?: throw notFound()).split('/')
        val isCollection = segments.size == 2 && segments[0].isEmpty() && segments[1] == "contexts"
        val isItem = segments.size == 3 && segments[0].isEmpty() && segments[1] == "contexts"
        if (isCollection) {
            if (ex.requestMethod != "POST") throw methodNotAllowed("POST")
            return createContext(ex)
        }
        if (isItem) {
            if (ex.requestMethod !in ITEM_METHODS) throw methodNotAllowed("GET, PATCH, DELETE")
            val id = parseUuidV7("contextId", segments[2])
            return when (ex.requestMethod) {
                "GET" -> getContext(ex, id)
                "PATCH" -> updateContext(ex, id)
                else -> deleteContext(ex, id)
            }
        }
        throw notFound()
    }

    private fun createContext(ex: HttpExchange): ApiResponse {
        val token = bearerToken(ex)
        val f = FieldReader(readJsonObject(ex), CREATE_FIELDS)
        val contextId = parseUuidV7("contextId", f.requireString("contextId"))
        val requestId = parseUuidV7("requestId", f.requireString("requestId"))
        val title = f.requireString("title")
        requireNonBlankTitle(title)
        val command = CreateContextMutation(
            contextId = contextId,
            requestId = requestId,
            kind = f.enumNamed("kind", ContextKind.entries).orMissing("kind"),
            status = f.status().orMissing("status"),
            title = title,
            summary = f.requireString("summary"),
            content = f.requireString("content"),
            scopes = f.stringSet("scopes") ?: emptySet(),
            sensitivity = f.sensitivity() ?: Sensitivity.INTERNAL,
            validFromEpochMillis = f.long("validFromEpochMillis"),
            validUntilEpochMillis = f.long("validUntilEpochMillis"),
        )
        val auth = CapturingAuthenticator(authenticator)
        val result = TrustedMutationGateway(service, auth).create(token, command)
        return mutationResponse(result, contextId, auth, Op.CREATE)
    }

    private fun getContext(ex: HttpExchange, id: String): ApiResponse {
        val token = bearerToken(ex)
        val auth = CapturingAuthenticator(authenticator)
        return when (val result = TrustedContextQueryGateway(reader, auth).get(token, id)) {
            is ContextReadResult.Found -> ApiResponse(200, result.context.toJson())
            ContextReadResult.NotFound -> throw notFound()
            ContextReadResult.Unavailable -> throw unavailable()
            ContextReadResult.Forbidden -> throw if (auth.verified) notFound() else unauthenticated()
            ContextReadResult.InvalidRequest -> throw invalidId("contextId must be a UUIDv7")
        }
    }

    private fun updateContext(ex: HttpExchange, id: String): ApiResponse {
        val token = bearerToken(ex)
        val f = FieldReader(readJsonObject(ex), PATCH_FIELDS)
        val requestId = parseUuidV7("requestId", f.requireString("requestId"))
        val expectedRevision = parseRevision("expectedContentRevision", f.requireLong("expectedContentRevision"))
        val title = f.string("title")
        title?.let(::requireNonBlankTitle)
        val patch = ContextPatch(
            title = title,
            summary = f.string("summary"),
            content = f.string("content"),
            status = f.status(),
            scopes = f.stringSet("scopes"),
            sensitivity = f.sensitivity(),
            validFromEpochMillis = f.long("validFromEpochMillis"),
            validUntilEpochMillis = f.long("validUntilEpochMillis"),
        )
        if (patch == ContextPatch()) throw invalidField("PATCH must change at least one field")
        val auth = CapturingAuthenticator(authenticator)
        val result = TrustedMutationGateway(service, auth)
            .update(token, UpdateContextMutation(id, requestId, expectedRevision, patch))
        return mutationResponse(result, id, auth, Op.UPDATE)
    }

    private fun deleteContext(ex: HttpExchange, id: String): ApiResponse {
        // DELETE carries its precondition in the query string, so it has no request body.
        val token = bearerToken(ex)
        val query = queryParams(ex, DELETE_QUERY_PARAMS)
        val requestId = parseUuidV7("requestId", query["requestId"] ?: throw invalidField("query parameter 'requestId' is required"))
        val rawRevision = query["expectedContentRevision"] ?: throw invalidField("query parameter 'expectedContentRevision' is required")
        if (!DIGITS.matches(rawRevision)) throw invalidField("'expectedContentRevision' must be a positive integer")
        val expectedRevision = parseRevision("expectedContentRevision", rawRevision.toLong())
        val auth = CapturingAuthenticator(authenticator)
        val result = TrustedMutationGateway(service, auth)
            .delete(token, DeleteContextMutation(id, requestId, expectedRevision))
        return mutationResponse(result, id, auth, Op.DELETE)
    }

    private fun mutationResponse(result: MutationResult, contextId: String, auth: CapturingAuthenticator, op: Op): ApiResponse =
        when (result) {
            is MutationResult.Applied -> {
                val created = op == Op.CREATE && !result.replayed
                ApiResponse(
                    status = if (created) 201 else 200,
                    body = buildJsonObject {
                        put("contextId", contextId)
                        put("contentRevision", result.contentRevision)
                        put("replayed", result.replayed)
                        if (op == Op.DELETE) put("deleted", true)
                    },
                    headers = if (created) mapOf("Location" to "/contexts/$contextId") else emptyMap(),
                )
            }
            is MutationResult.Conflict -> throw if (op == Op.CREATE) {
                contextIdConflict()
            } else {
                ApiException(
                    409, "REVISION_CONFLICT",
                    "expectedContentRevision does not match the current contentRevision",
                    extra = mapOf("currentContentRevision" to (result.actualRevision?.let { JsonPrimitive(it) } ?: JsonNull)),
                )
            }
            // Other owners in the same tenant get the same answer as a missing Context (no existence leak).
            MutationResult.Forbidden -> throw when {
                !auth.verified -> unauthenticated()
                op == Op.CREATE -> contextIdConflict()
                else -> notFound()
            }
            MutationResult.NotFound -> throw notFound()
            MutationResult.RequestIdReused -> throw ApiException(
                409, "REQUEST_ID_REUSED", "requestId was already used with a different payload",
            )
            MutationResult.InvalidRequest -> throw ApiException(
                422, "REJECTED_BY_POLICY",
                "rejected by Context policy (for example RESTRICTED sensitivity, invalid validity interval, or unverified CONFIRMED evidence)",
            )
            MutationResult.OutcomeUnknown -> throw ApiException(
                503, "OUTCOME_UNKNOWN",
                "The outcome of this write is unknown. It was neither confirmed as applied nor as not applied. " +
                    "Retry with the same requestId and the same body to reconcile.",
            )
            MutationResult.Unavailable -> throw unavailable()
        }

    // ---- request parsing -------------------------------------------------------------------

    private fun bearerToken(ex: HttpExchange): String {
        val header = ex.requestHeaders.getFirst("Authorization") ?: throw unauthenticated()
        return BEARER.matchEntire(header.trim())?.groupValues?.get(1) ?: throw unauthenticated()
    }

    private fun readJsonObject(ex: HttpExchange): JsonObject {
        val bytes = ex.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "request body exceeds $MAX_BODY_BYTES bytes")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw invalidJson("request body must be UTF-8")
        }
        val element = try {
            STRICT_JSON.parseToJsonElement(text)
        } catch (_: Exception) {
            throw invalidJson("request body is not valid JSON")
        }
        return element as? JsonObject ?: throw invalidJson("request body must be a JSON object")
    }

    private fun queryParams(ex: HttpExchange, allowed: Set<String>): Map<String, String> {
        val raw = ex.requestURI.rawQuery ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            val eq = part.indexOf('=')
            val name = decode(if (eq < 0) part else part.substring(0, eq))
            val value = if (eq < 0) "" else decode(part.substring(eq + 1))
            if (name !in allowed) throw invalidField("unknown query parameter '$name'")
            if (out.put(name, value) != null) throw invalidField("duplicate query parameter '$name'")
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw invalidField("malformed query string")
    }

    private fun respond(ex: HttpExchange, response: ApiResponse) {
        val bytes = response.body.toString().toByteArray(StandardCharsets.UTF_8)
        val headers = ex.responseHeaders
        headers.set("Content-Type", "application/json; charset=utf-8")
        headers.set("Cache-Control", "no-store")
        response.headers.forEach { (name, value) -> headers.set(name, value) }
        ex.sendResponseHeaders(response.status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val MAX_BODY_BYTES = 1 shl 20
        private val STRICT_JSON = Json
        private val BEARER = Regex("Bearer\\s+(\\S+)", RegexOption.IGNORE_CASE)
        private val DIGITS = Regex("0|[1-9][0-9]{0,17}")
        private val ITEM_METHODS = setOf("GET", "PATCH", "DELETE")
        private val CREATE_FIELDS = setOf(
            "contextId", "requestId", "kind", "status", "title", "summary", "content",
            "scopes", "sensitivity", "validFromEpochMillis", "validUntilEpochMillis",
        )
        private val PATCH_FIELDS = setOf(
            "requestId", "expectedContentRevision", "title", "summary", "content", "status",
            "scopes", "sensitivity", "validFromEpochMillis", "validUntilEpochMillis",
        )
        private val DELETE_QUERY_PARAMS = setOf("requestId", "expectedContentRevision")
    }
}

/** True once the bearer credential verified. Used to tell 401 (unauthenticated) from authorization failures. */
private class CapturingAuthenticator(private val delegate: SessionAuthenticator) : SessionAuthenticator {
    var verified: Boolean = false
        private set

    override fun authenticate(bearerCredential: String): Principal? =
        delegate.authenticate(bearerCredential).also { verified = it != null }
}

private enum class Op { CREATE, UPDATE, DELETE }

private class ApiResponse(val status: Int, val body: JsonObject, val headers: Map<String, String> = emptyMap())

private class ApiException(
    val status: Int,
    val code: String,
    val detail: String,
    val extra: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(detail) {
    fun toResponse(): ApiResponse = ApiResponse(status, errorBody(code, detail, extra), headers)
}

/** Strict field access over a JSON object: unknown fields and JSON null are rejected with 400. */
private class FieldReader(private val obj: JsonObject, allowed: Set<String>) {
    init {
        obj.keys.firstOrNull { it !in allowed }?.let { throw invalidField("unknown field '$it' is not accepted") }
        obj.forEach { (name, value) ->
            if (value is JsonNull) throw invalidField("null is not supported for '$name'; omit the field to leave it unchanged")
        }
    }

    fun string(name: String): String? {
        val value = obj[name] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw invalidField("'$name' must be a string")
        return primitive.content
    }

    fun requireString(name: String): String = string(name) ?: throw missingField(name)

    fun long(name: String): Long? {
        val value = obj[name] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString) throw invalidField("'$name' must be an integer")
        return primitive.content.toLongOrNull() ?: throw invalidField("'$name' must be an integer")
    }

    fun requireLong(name: String): Long = long(name) ?: throw missingField(name)

    fun stringSet(name: String): Set<String>? {
        val value = obj[name] ?: return null
        val array = value as? JsonArray ?: throw invalidField("'$name' must be an array of strings")
        return array.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw invalidField("'$name' must contain only strings")
        }.toSet()
    }

    fun <T : Enum<T>> enumNamed(name: String, values: List<T>): T? {
        val raw = string(name) ?: return null
        return values.firstOrNull { it.name.lowercase() == raw }
            ?: throw invalidField("'$name' has an unsupported value")
    }

    /** `superseded` is deliberately not accepted: Supersede (recl #9) is not implemented. */
    fun status(): ContextStatus? {
        val raw = string("status") ?: return null
        if (raw == "superseded") throw invalidField("status 'superseded' is not available: Supersede is not implemented")
        return enumNamed("status", ContextStatus.entries.filter { it != ContextStatus.SUPERSEDED })
    }

    /** `restricted` is parsed and then rejected by the core gateway, so the policy stays in one place. */
    fun sensitivity(): Sensitivity? = enumNamed("sensitivity", Sensitivity.entries)
}

private fun <T : Any> T?.orMissing(name: String): T = this ?: throw missingField(name)

private fun requireNonBlankTitle(title: String) {
    if (title.isBlank()) throw invalidField("'title' must not be blank")
}

private fun parseUuidV7(name: String, value: String): String =
    ContextIds.canonicalUuidV7(value) ?: throw invalidId("'$name' must be a UUIDv7")

private fun parseRevision(name: String, value: Long): Long {
    if (value < 1) throw invalidField("'$name' must be at least 1")
    return value
}

// ---- response construction --------------------------------------------------------------------

private fun ContextSnapshot.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("kind", kind.name.lowercase())
    put("status", status.name.lowercase())
    put("title", title)
    put("summary", summary)
    put("content", content)
    put("scopes", JsonArray(scopes.sorted().map { JsonPrimitive(it) }))
    put("contentRevision", contentRevision)
    put("sensitivity", sensitivity.name.lowercase())
    put("validFromEpochMillis", validFromEpochMillis)
    put("validUntilEpochMillis", validUntilEpochMillis)
}

private fun errorBody(code: String, message: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
    JsonObject(linkedMapOf<String, JsonElement>("error" to JsonPrimitive(code), "message" to JsonPrimitive(message)) + extra)

private fun invalidField(detail: String) = ApiException(400, "INVALID_FIELD", detail)
private fun missingField(name: String) = ApiException(400, "INVALID_FIELD", "'$name' is required")
private fun invalidId(detail: String) = ApiException(400, "INVALID_ID", detail)
private fun invalidJson(detail: String) = ApiException(400, "INVALID_JSON", detail)
private fun notFound() = ApiException(404, "NOT_FOUND", "Context not found")
private fun contextIdConflict() = ApiException(409, "CONTEXT_ID_CONFLICT", "a Context with this contextId already exists")
private fun unauthenticated() = ApiException(
    401, "UNAUTHENTICATED", "a valid bearer credential is required",
    headers = mapOf("WWW-Authenticate" to "Bearer"),
)
private fun methodNotAllowed(allow: String) = ApiException(
    405, "METHOD_NOT_ALLOWED", "method not allowed for this resource", headers = mapOf("Allow" to allow),
)
private fun unavailable() = ApiException(
    503, "UNAVAILABLE",
    "storage is unavailable. A write whose outcome is unknown must be retried with the same requestId and body.",
)

/** Starts a JDK HttpServer serving [api] under every path. Pass port 0 for an ephemeral port. */
fun startContextRestServer(api: ContextRestApi, bindAddress: InetSocketAddress): HttpServer =
    HttpServer.create(bindAddress, 0).apply {
        createContext("/", api)
        start()
    }
