package net.kigawa.recl.app

import com.sun.net.httpserver.HttpHandler
import net.kigawa.recl.core.ContextReader
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.ContextTokenCounter
import net.kigawa.recl.core.OpenedStorage
import net.kigawa.recl.core.PinnedContextReader
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.SessionAuthenticator
import net.kigawa.recl.core.StorageProvider
import net.kigawa.recl.mcp.McpContextServer
import net.kigawa.recl.mcp.McpContextTools
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import java.net.URI
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

sealed interface McpRuntime {
    data object Disabled : McpRuntime
    data class Rejected(val reason: String) : McpRuntime
    data class Enabled(
        val handler: HttpHandler,
        val storage: ContextStorage,
        val storageMode: String,
        val authMode: String,
        val tenantId: String,
        val publicUrl: String? = null,
        val issuer: String? = null,
        val requiredScope: String? = null,
        private val openedStorage: OpenedStorage = OpenedStorage(storage, storageMode),
    ) : McpRuntime, AutoCloseable {
        override fun close() = openedStorage.close()

        val metadataHandler: OAuthProtectedResourceMetadata?
            get() = if (authMode == "oidc" && publicUrl != null && issuer != null)
                OAuthProtectedResourceMetadata(publicUrl, issuer, requiredScope) else null
    }

    companion object {
        fun resolve(env: Map<String, String>): McpRuntime = resolve(env) {
            ServiceLoader.load(StorageProvider::class.java).firstOrNull { it.id == "fortis" }
        }

        internal fun resolve(env: Map<String, String>, findFortis: () -> StorageProvider?): McpRuntime {
            if (env["RECL_MCP_ENABLED"] != "1") return Disabled
            val tenantId = env["RECL_MCP_TENANT_ID"]
            if (tenantId == null || !validId(tenantId)) return Rejected("RECL_MCP_TENANT_ID must be non-blank without whitespace")
            val storageDir = when (env["RECL_STORAGE"] ?: "memory") {
                "memory" -> {
                    if (env["RECL_MCP_ALLOW_EPHEMERAL"] != "1") {
                        return Rejected("in-memory storage is ephemeral; set RECL_MCP_ALLOW_EPHEMERAL=1 to acknowledge")
                    }
                    null
                }
                "fortis" -> env["RECL_FORTIS_DIR"]?.takeIf { it.isNotBlank() }
                    ?: return Rejected("RECL_FORTIS_DIR is required when RECL_STORAGE=fortis")
                else -> return Rejected("RECL_STORAGE must be memory or fortis")
            }
            val mode = env["RECL_MCP_AUTH"] ?: "token"
            val authenticator: McpRequestAuthenticator
            var issuer: String? = null
            var publicUrl: String? = null
            var scope: String? = null
            when (mode) {
                "token" -> {
                    val token = env["RECL_MCP_TOKEN"]
                    if (token == null || token.length < 32 || token.any { it.isWhitespace() }) {
                        return Rejected("RECL_MCP_TOKEN must contain at least 32 characters without whitespace")
                    }
                    val ownerId = env["RECL_MCP_OWNER_ID"]
                    if (!validId(ownerId)) return Rejected("RECL_MCP_OWNER_ID must be non-blank without whitespace")
                    authenticator = TokenMcpAuthenticator(token, ownerId!!)
                }
                "oidc" -> {
                    issuer = env["RECL_OIDC_ISSUER"]
                    if (!validUrl(issuer)) return Rejected("RECL_OIDC_ISSUER must be an HTTPS URL (HTTP loopback allowed for tests)")
                    val jwksUri = env["RECL_OIDC_JWKS_URI"] ?: "${issuer!!.trimEnd('/')}/protocol/openid-connect/certs"
                    if (!validUrl(jwksUri)) return Rejected("RECL_OIDC_JWKS_URI must be an HTTPS URL (HTTP loopback allowed for tests)")
                    publicUrl = env["RECL_PUBLIC_URL"]?.trimEnd('/')
                    if (publicUrl != null && !validUrl(publicUrl)) return Rejected("RECL_PUBLIC_URL must be an HTTPS URL (HTTP loopback allowed for tests)")
                    scope = env["RECL_OIDC_REQUIRED_SCOPE"]
                    if (scope != null && !validId(scope)) return Rejected("RECL_OIDC_REQUIRED_SCOPE must be a single non-blank scope")
                    val audience = env["RECL_OIDC_AUDIENCE"]
                    if (audience != null && !validId(audience)) return Rejected("RECL_OIDC_AUDIENCE must be non-blank without whitespace")
                    authenticator = OidcTokenAuthenticator(issuer!!, jwksUri, tenantId, audience, scope)
                }
                else -> return Rejected("RECL_MCP_AUTH must be token or oidc")
            }
            val opened = if (storageDir == null) {
                OpenedStorage(InMemoryContextStorage(), "in-memory (ephemeral)")
            } else {
                try {
                    val provider = findFortis()
                        ?: return Rejected("this build does not include Fortis storage; rebuild with -Pfortis.dir=<path>")
                    provider.open(mapOf("dir" to storageDir))
                } catch (failure: ServiceConfigurationError) {
                    return Rejected("could not load Fortis storage provider: ${failure.message}")
                } catch (failure: Exception) {
                    return Rejected("could not open Fortis storage: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
            val storage = opened.storage
            val counter = ContextTokenCounter { it.title.length + it.summary.length + it.content.length }
            val service = ContextService(storage)
            val reader = ContextReader(storage)
            val pinnedReader = PinnedContextReader(storage, counter)
            val sessionAuthenticator = SessionAuthenticator { credential ->
                authenticator.authenticate(credential)?.let { Principal(tenantId = tenantId, id = it.userId) }
            }
            val factory = { credential: String ->
                McpContextServer(McpContextTools(service, reader, pinnedReader, sessionAuthenticator, credential))
            }
            val metadataUrl = publicUrl?.let { "$it/.well-known/oauth-protected-resource" }
            return Enabled(
                handler = McpHttpHandler(factory, authenticator, metadataUrl),
                storage = storage,
                storageMode = opened.mode,
                authMode = mode,
                tenantId = tenantId,
                publicUrl = publicUrl,
                issuer = issuer,
                requiredScope = scope,
                openedStorage = opened,
            )
        }

        private fun validId(value: String?): Boolean =
            value != null && value.isNotBlank() && value.none { it.isWhitespace() }

        private fun validUrl(value: String?): Boolean = try {
            val uri = URI(value ?: "")
            value != null && value.none { it.isWhitespace() || it == '"' || it == '\\' } &&
                uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))
        } catch (_: Exception) { false }
    }
}
