package net.kigawa.recl.storage.fortis

import net.kigawa.recl.core.ContextIds

/**
 * Fail-closed identifier validation at the Fortis boundary. Anything that is not already in
 * canonical form is rejected (never trimmed, lowercased or re-encoded), so keys cannot alias
 * across tenants or contexts.
 */
object FortisContextKeys {
    /** Lowercase, no separators: `/` cannot appear, so tenant prefixes never overlap. */
    private val tenant = Regex("[a-z0-9][a-z0-9-]{0,62}")
    private val requestId = Regex("[A-Za-z0-9._:-]{1,128}")
    private const val ROOT = "recl/v1"

    fun isCanonicalTenantId(value: String): Boolean = tenant.matches(value)

    fun isCanonicalRequestId(value: String): Boolean = requestId.matches(value)

    fun contextKey(tenantId: String, contextId: String): String {
        requireTenant(tenantId)
        requireContext(contextId)
        return "$ROOT/t/$tenantId/c/$contextId"
    }

    /** Trailing slash keeps tenant `a` from matching keys of tenant `ab`. */
    fun tenantPrefix(tenantId: String): String {
        requireTenant(tenantId)
        return "$ROOT/t/$tenantId/c/"
    }

    /**
     * Fortis dedup identity. A request id is scoped to its Context so that the same client
     * requestId used on two Contexts cannot collide inside Fortis's (principal, requestId) space.
     */
    fun fortisRequestId(contextId: String, requestId: String): String {
        requireContext(contextId)
        require(isCanonicalRequestId(requestId)) { "requestId is not canonical" }
        return "$ROOT/c/$contextId/r/$requestId"
    }

    private fun requireTenant(tenantId: String) =
        require(isCanonicalTenantId(tenantId)) { "tenantId is not canonical" }

    private fun requireContext(contextId: String) =
        require(ContextIds.canonicalUuidV7(contextId) == contextId) { "contextId must be canonical UUIDv7" }
}
