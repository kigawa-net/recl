package net.kigawa.recl.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
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
import net.kigawa.recl.core.MutationResult
import net.kigawa.recl.core.PinnedContextReader
import net.kigawa.recl.core.PinnedContextResult
import net.kigawa.recl.core.PinnedQuery
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.SessionAuthenticator
import net.kigawa.recl.core.SourceCitation
import net.kigawa.recl.core.SourceSubmission
import net.kigawa.recl.core.SourceTrust
import net.kigawa.recl.core.SourceType
import net.kigawa.recl.core.TrustedContextQueryGateway
import net.kigawa.recl.core.TrustedMutationGateway
import net.kigawa.recl.core.TrustedPinnedQueryGateway
import net.kigawa.recl.core.UpdateContextMutation

/**
 * MCP tool surface for recl Core (recl #7, first slice).
 *
 * Every call runs behind the same trusted gateways as the REST adapter. The tenant/owner come only
 * from the injected [SessionAuthenticator]; tool arguments never carry tenant, owner, Principal or a
 * payload digest. This class is NOT a production server: no durable storage, no real authenticator
 * and no Qdrant search are wired here.
 */
class McpContextTools(
    private val service: ContextService,
    private val reader: ContextReader,
    private val pinnedReader: PinnedContextReader,
    private val authenticator: SessionAuthenticator,
    /** Credential established by the trusted stdio launcher, never by an MCP tool argument. */
    private val bearerCredential: String,
) {
    fun call(name: String, arguments: JsonObject): ToolResult {
        if (name !in TOOL_NAMES) throw McpUnknownToolException(name)
        return try {
            when (name) {
                "search_context" -> searchContext(arguments)
                "get_context" -> getContext(arguments)
                "get_pinned_contexts" -> getPinnedContexts(arguments)
                "save_context" -> saveContext(arguments)
                "update_context" -> updateContext(arguments)
                else -> throw McpUnknownToolException(name)
            }
        } catch (e: McpToolInputException) {
            error("INVALID_ARGUMENT", e.message ?: "invalid arguments")
        }
    }

    /**
     * Semantic search is deliberately not faked. Qdrant and the authoritative revalidation protocol
     * (design.md §7) are not implemented, so returning any local substring match would silently
     * advertise a capability the deployment does not have.
     */
    private fun searchContext(arguments: JsonObject): ToolResult {
        val args = Arguments(arguments, setOf("query", "scope", "limit"))
        args.requireString("query")
        args.stringSet("scope")
        args.int("limit")
        return error(
            "SEARCH_UNSUPPORTED",
            "Semantic search is not implemented in this build. Use get_context for an exact ID or " +
                "get_pinned_contexts for pinned Context. No candidate list was produced.",
        )
    }

    private fun getContext(arguments: JsonObject): ToolResult {
        val args = Arguments(arguments, setOf("id"))
        val id = args.requireString("id")
        return when (val result = TrustedContextQueryGateway(reader, authenticator).get(bearerCredential, id)) {
            is ContextReadResult.Found -> ok(result.context.toJson())
            ContextReadResult.NotFound -> error("NOT_FOUND", "Context not found")
            ContextReadResult.Unavailable -> unavailable()
            ContextReadResult.Forbidden ->
                if (credentialVerified()) error("NOT_FOUND", "Context not found") else unauthenticated()
            ContextReadResult.InvalidRequest -> invalidId("'id' must be a UUIDv7")
        }
    }

    private fun getPinnedContexts(arguments: JsonObject): ToolResult {
        val args = Arguments(arguments, setOf("scope", "tokenBudget", "maxContexts"))
        val query = PinnedQuery(
            scopes = args.stringSet("scope") ?: emptySet(),
            tokenBudget = args.requireIntIn("tokenBudget", 1..100_000),
            maxContexts = args.requireIntIn("maxContexts", 1..100),
        )
        return when (val result = TrustedPinnedQueryGateway(pinnedReader, authenticator).list(bearerCredential, query)) {
            is PinnedContextResult.Found -> ok(buildJsonObject {
                put("contexts", buildJsonArray { result.contexts.forEach { add(it.toJson()) } })
                put("usedTokens", result.usedTokens)
                put("limited", result.limited)
            })
            PinnedContextResult.Forbidden -> unauthenticated()
            PinnedContextResult.InvalidRequest -> error("INVALID_ARGUMENT", "pinned query parameters are out of range")
            PinnedContextResult.Unsupported -> error(
                "PINNED_UNSUPPORTED",
                "pinned retrieval needs a complete authoritative enumeration, which this storage does not provide",
            )
            PinnedContextResult.TooLarge -> error(
                "PINNED_TOO_LARGE",
                "the tenant has too many Contexts for a complete pinned enumeration; no partial result is returned",
            )
            PinnedContextResult.Unavailable -> error(
                "PINNED_UNAVAILABLE",
                "pinned retrieval could not complete an authoritative read; no partial result is returned",
            )
        }
    }

    private fun saveContext(arguments: JsonObject): ToolResult {
        val args = Arguments(arguments, CREATE_FIELDS)
        val contextId = canonicalId("contextId", args.requireString("contextId"))
        val requestId = canonicalId("requestId", args.requireString("requestId"))
        val title = args.requireString("title").also {
            if (it.isBlank()) throw McpToolInputException("'title' must not be blank")
        }
        val mutation = CreateContextMutation(
            contextId = contextId,
            requestId = requestId,
            kind = args.enumValue("kind", ContextKind.entries) ?: missing("kind"),
            status = args.status(required = true) ?: missing("status"),
            title = title,
            summary = args.requireString("summary"),
            content = args.requireString("content"),
            scopes = args.stringSet("scopes") ?: emptySet(),
            pinned = args.boolean("pinned") ?: false,
            sensitivity = args.enumValue("sensitivity", Sensitivity.entries) ?: Sensitivity.INTERNAL,
            validFromEpochMillis = args.long("validFromEpochMillis"),
            validUntilEpochMillis = args.long("validUntilEpochMillis"),
            sources = args.sources() ?: emptyList(),
        )
        val verified = credentialVerified()
        return when (val result = TrustedMutationGateway(service, authenticator).create(bearerCredential, mutation)) {
            is MutationResult.Applied -> ok(buildJsonObject {
                put("contextId", contextId)
                put("contentRevision", result.contentRevision)
                put("replayed", result.replayed)
            })
            is MutationResult.Conflict -> error("CONTEXT_ID_CONFLICT", "a Context with this contextId already exists")
            MutationResult.Forbidden ->
                if (verified) error("CONTEXT_ID_CONFLICT", "a Context with this contextId already exists") else unauthenticated()
            MutationResult.NotFound -> error("NOT_FOUND", "Context not found")
            MutationResult.RequestIdReused ->
                error("REQUEST_ID_REUSED", "requestId was already used with a different payload")
            MutationResult.InvalidRequest -> policyRejected()
            MutationResult.OutcomeUnknown -> outcomeUnknown()
            MutationResult.Unavailable -> unavailable()
        }
    }

    private fun updateContext(arguments: JsonObject): ToolResult {
        val args = Arguments(arguments, UPDATE_FIELDS)
        val id = canonicalId("id", args.requireString("id"))
        val requestId = canonicalId("requestId", args.requireString("requestId"))
        val expected = args.requireLong("expectedContentRevision")
        if (expected < 1) throw McpToolInputException("'expectedContentRevision' must be at least 1")
        val title = args.string("title")?.also {
            if (it.isBlank()) throw McpToolInputException("'title' must not be blank")
        }
        val patch = ContextPatch(
            title = title,
            summary = args.string("summary"),
            content = args.string("content"),
            status = args.status(required = false),
            scopes = args.stringSet("scopes"),
            pinned = args.boolean("pinned"),
            sensitivity = args.enumValue("sensitivity", Sensitivity.entries),
            validFromEpochMillis = args.long("validFromEpochMillis"),
            validUntilEpochMillis = args.long("validUntilEpochMillis"),
            sources = args.sources(),
        )
        if (patch == ContextPatch()) throw McpToolInputException("update_context must change at least one field")
        val verified = credentialVerified()
        val result = TrustedMutationGateway(service, authenticator)
            .update(bearerCredential, UpdateContextMutation(id, requestId, expected, patch))
        return when (result) {
            is MutationResult.Applied -> ok(buildJsonObject {
                put("contextId", id)
                put("contentRevision", result.contentRevision)
                put("replayed", result.replayed)
            })
            is MutationResult.Conflict -> error(
                "REVISION_CONFLICT",
                "expectedContentRevision does not match the current contentRevision",
                buildJsonObject {
                    put("currentContentRevision", result.actualRevision?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
            MutationResult.Forbidden -> if (verified) error("NOT_FOUND", "Context not found") else unauthenticated()
            MutationResult.NotFound -> error("NOT_FOUND", "Context not found")
            MutationResult.RequestIdReused ->
                error("REQUEST_ID_REUSED", "requestId was already used with a different payload")
            MutationResult.InvalidRequest -> policyRejected()
            MutationResult.OutcomeUnknown -> outcomeUnknown()
            MutationResult.Unavailable -> unavailable()
        }
    }

    private fun credentialVerified(): Boolean =
        bearerCredential.isNotBlank() && authenticator.authenticate(bearerCredential) != null

    private fun canonicalId(name: String, value: String): String =
        ContextIds.canonicalUuidV7(value) ?: invalidId("'$name' must be a UUIDv7")

    companion object {
        val TOOL_NAMES = setOf(
            "search_context", "get_context", "get_pinned_contexts", "save_context", "update_context",
        )

        private val CREATE_FIELDS = setOf(
            "contextId", "requestId", "kind", "status", "title", "summary", "content",
            "scopes", "pinned", "sensitivity", "validFromEpochMillis", "validUntilEpochMillis", "sources",
        )
        private val UPDATE_FIELDS = setOf(
            "id", "requestId", "expectedContentRevision", "title", "summary", "content", "status",
            "scopes", "pinned", "sensitivity", "validFromEpochMillis", "validUntilEpochMillis", "sources",
        )
    }
}

/** A tool-level failure is returned to the agent as `isError: true`, never as a protocol crash. */
data class ToolResult(val payload: JsonObject, val isError: Boolean)

class McpUnknownToolException(val toolName: String) : Exception("unknown tool '$toolName'")

class McpToolInputException(message: String) : Exception(message)

private fun ok(payload: JsonObject) = ToolResult(payload, isError = false)

private fun error(code: String, message: String, extra: JsonObject = JsonObject(emptyMap())) =
    ToolResult(
        buildJsonObject {
            put("error", code)
            put("message", message)
            extra.forEach { (name, value) -> put(name, value) }
        },
        isError = true,
    )

private fun unauthenticated() = error("UNAUTHENTICATED", "the injected MCP credential did not authenticate")
private fun unavailable() = error("UNAVAILABLE", "authoritative storage is unavailable; nothing was confirmed")
private fun policyRejected() = error(
    "REJECTED_BY_POLICY",
    "rejected by Context policy (for example RESTRICTED sensitivity, invalid validity interval, " +
        "unverified CONFIRMED evidence, or an unsafe source reference)",
)
private fun outcomeUnknown() = error(
    "OUTCOME_UNKNOWN",
    "the outcome of this write is unknown; retry with the same requestId and the same payload to reconcile",
)
private fun invalidId(message: String): Nothing = throw McpToolInputException(message)
private fun missing(name: String): Nothing = throw McpToolInputException("'$name' is required")

private fun ContextSnapshot.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("kind", kind.name.lowercase())
    put("status", status.name.lowercase())
    put("title", title)
    put("summary", summary)
    put("content", content)
    put("scopes", buildJsonArray { scopes.sorted().forEach { add(JsonPrimitive(it)) } })
    put("contentRevision", contentRevision)
    put("sensitivity", sensitivity.name.lowercase())
    put("validFromEpochMillis", validFromEpochMillis)
    put("validUntilEpochMillis", validUntilEpochMillis)
    put("sources", buildJsonArray { sources.forEach { add(it.toJson()) } })
}

private fun SourceCitation.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("type", type.name.lowercase())
    put("uri", uri)
    put("locator", locator)
    put("retrievedAtEpochMillis", retrievedAtEpochMillis)
    put("contentDigest", contentDigest)
    put("trust", trust.name.lowercase())
    put("verification", verification.name.lowercase())
}

/**
 * Strict argument reader: unknown fields and JSON null are rejected. MCP has no partial update
 * sentinel (null), so a caller must omit fields it does not want to change.
 */
private class Arguments(private val obj: JsonObject, allowed: Set<String>) {
    init {
        obj.keys.firstOrNull { it !in allowed }?.let {
            throw McpToolInputException("unknown field '$it' is not accepted")
        }
        obj.forEach { (name, value) ->
            if (value is JsonNull) throw McpToolInputException("null is not supported for '$name'; omit it instead")
        }
    }

    fun string(name: String): String? {
        val value = obj[name] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw McpToolInputException("'$name' must be a string")
        return primitive.content
    }

    fun requireString(name: String): String = string(name) ?: missing(name)

    fun long(name: String): Long? {
        val value = obj[name] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString) throw McpToolInputException("'$name' must be an integer")
        return primitive.longOrNull ?: throw McpToolInputException("'$name' must be an integer")
    }

    fun requireLong(name: String): Long = long(name) ?: missing(name)

    fun int(name: String): Int? {
        val value = long(name) ?: return null
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw McpToolInputException("'$name' is out of range")
        }
        return value.toInt()
    }

    fun requireIntIn(name: String, range: IntRange): Int {
        val value = int(name) ?: missing(name)
        if (value !in range) throw McpToolInputException("'$name' must be between ${range.first} and ${range.last}")
        return value
    }

    fun boolean(name: String): Boolean? {
        val value = obj[name] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString) throw McpToolInputException("'$name' must be a boolean")
        return primitive.booleanOrNull ?: throw McpToolInputException("'$name' must be a boolean")
    }

    fun stringSet(name: String): Set<String>? {
        val value = obj[name] ?: return null
        val array = value as? JsonArray ?: throw McpToolInputException("'$name' must be an array of strings")
        return array.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw McpToolInputException("'$name' must contain only strings")
        }.toSet()
    }

    fun <T : Enum<T>> enumValue(name: String, values: List<T>): T? {
        val raw = string(name) ?: return null
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw McpToolInputException("'$name' has an unsupported value")
    }

    /** `superseded` is not available: the two-key Supersede protocol is still unsupported. */
    fun status(required: Boolean): ContextStatus? {
        val raw = string("status") ?: if (required) missing("status") else return null
        if (raw.equals("superseded", ignoreCase = true)) {
            throw McpToolInputException("status 'superseded' is not available: Supersede is not implemented")
        }
        return enumValue("status", ContextStatus.entries.filter { it != ContextStatus.SUPERSEDED })
    }

    fun sources(): List<SourceSubmission>? {
        val value = obj["sources"] ?: return null
        val array = value as? JsonArray ?: throw McpToolInputException("'sources' must be an array of objects")
        if (array.size > 16) throw McpToolInputException("at most 16 sources are accepted")
        return array.mapIndexed { index, element ->
            val source = element as? JsonObject
                ?: throw McpToolInputException("'sources[$index]' must be an object")
            val s = Arguments(
                source,
                setOf("sourceId", "type", "uri", "locator", "retrievedAtEpochMillis", "contentDigest", "trust"),
            )
            SourceSubmission(
                sourceId = s.requireString("sourceId"),
                type = s.enumValue("type", SourceType.entries) ?: missing("sources[$index].type"),
                uri = s.requireString("uri"),
                locator = s.string("locator"),
                retrievedAtEpochMillis = s.long("retrievedAtEpochMillis"),
                contentDigest = s.string("contentDigest"),
                trust = s.enumValue("trust", SourceTrust.entries) ?: missing("sources[$index].trust"),
            )
        }
    }
}
