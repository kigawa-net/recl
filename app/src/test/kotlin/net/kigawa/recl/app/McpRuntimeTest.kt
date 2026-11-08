package net.kigawa.recl.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun mcpEnvironment(): Map<String, String> = mapOf(
    "RECL_MCP_ENABLED" to "1",
    "RECL_MCP_TOKEN" to "test-token-012345678901234567890123456789",
    "RECL_MCP_TENANT_ID" to "tenant",
    "RECL_MCP_OWNER_ID" to "owner",
    "RECL_MCP_ALLOW_EPHEMERAL" to "1",
)

internal fun oidcEnvironment(): Map<String, String> = mapOf(
    "RECL_MCP_ENABLED" to "1",
    "RECL_MCP_AUTH" to "oidc",
    "RECL_MCP_TENANT_ID" to "tenant",
    "RECL_MCP_ALLOW_EPHEMERAL" to "1",
    "RECL_OIDC_ISSUER" to "https://user.kigawa.net/realms/kigawa-net",
    "RECL_PUBLIC_URL" to "https://recl.kigawa.net",
)

class McpRuntimeTest {
    @Test
    fun `disabled unless explicitly enabled`() {
        assertEquals(McpRuntime.Disabled, McpRuntime.resolve(emptyMap()))
        assertEquals(McpRuntime.Disabled, McpRuntime.resolve(mapOf("RECL_MCP_ENABLED" to "true")))
    }

    @Test
    fun `enabled requires credentials and ephemeral acknowledgement`() {
        assertIs<McpRuntime.Rejected>(McpRuntime.resolve(mapOf("RECL_MCP_ENABLED" to "1")))
        val rejected = assertIs<McpRuntime.Rejected>(
            McpRuntime.resolve(mcpEnvironment() - "RECL_MCP_ALLOW_EPHEMERAL"),
        )
        assertTrue(rejected.reason.contains("in-memory storage is ephemeral; set RECL_MCP_ALLOW_EPHEMERAL=1 to acknowledge"))
        val enabled = assertIs<McpRuntime.Enabled>(McpRuntime.resolve(mcpEnvironment()))
        assertEquals("in-memory (ephemeral)", enabled.storageMode)
    }

    @Test
    fun `invalid credentials are rejected without exposing their values`() {
        for ((key, value) in listOf(
            "RECL_MCP_TOKEN" to "short-secret",
            "RECL_MCP_TOKEN" to " ".repeat(32),
            "RECL_MCP_TOKEN" to "x".repeat(31),
            "RECL_MCP_TENANT_ID" to "tenant space",
            "RECL_MCP_TENANT_ID" to "",
            "RECL_MCP_OWNER_ID" to "owner\tspace",
            "RECL_MCP_OWNER_ID" to "",
        )) {
            val rejected = assertIs<McpRuntime.Rejected>(McpRuntime.resolve(mcpEnvironment() + (key to value)))
            assertTrue(rejected.reason.contains(key))
            if (value.isNotEmpty()) assertTrue(!rejected.reason.contains(value))
        }
        for (key in listOf("RECL_MCP_TOKEN", "RECL_MCP_TENANT_ID", "RECL_MCP_OWNER_ID")) {
            assertIs<McpRuntime.Rejected>(McpRuntime.resolve(mcpEnvironment() - key))
        }
    }

    @Test
    fun `oidc requires issuer and rejects insecure remote urls`() {
        assertIs<McpRuntime.Rejected>(McpRuntime.resolve(oidcEnvironment() - "RECL_OIDC_ISSUER"))
        for (issuer in listOf("http://user.kigawa.net/realms/kigawa-net", "https://", "not-a-url",
            "http://localhost.attacker.example/realm", "https://user:secret@example.com/realm")) {
            assertIs<McpRuntime.Rejected>(McpRuntime.resolve(oidcEnvironment() + ("RECL_OIDC_ISSUER" to issuer)))
        }
    }

    @Test
    fun `oidc enables without static credentials and retains metadata settings`() {
        val enabled = assertIs<McpRuntime.Enabled>(McpRuntime.resolve(oidcEnvironment() + mapOf(
            "RECL_OIDC_REQUIRED_SCOPE" to "openid",
            "RECL_OIDC_AUDIENCE" to "recl",
            "RECL_PUBLIC_URL" to "https://recl.kigawa.net/",
        )))
        assertEquals("oidc", enabled.authMode)
        assertEquals("in-memory (ephemeral)", enabled.storageMode)
        assertEquals("https://recl.kigawa.net", enabled.publicUrl)
        assertEquals("https://user.kigawa.net/realms/kigawa-net", enabled.issuer)
        assertEquals("openid", enabled.requiredScope)
        assertNotNull(enabled.metadataHandler)
    }

    @Test
    fun `oidc supports loopback http issuer and explicit jwks uri`() {
        for (issuer in listOf("http://127.0.0.1:12345/realm", "http://localhost:12345/realm")) {
            val enabled = assertIs<McpRuntime.Enabled>(McpRuntime.resolve(oidcEnvironment() + mapOf(
                "RECL_OIDC_ISSUER" to issuer,
                "RECL_OIDC_JWKS_URI" to "$issuer/jwks",
            )))
            assertEquals(issuer, enabled.issuer)
        }
    }

    @Test
    fun `oidc still requires tenant and ephemeral acknowledgement`() {
        for (key in listOf("RECL_MCP_TENANT_ID", "RECL_MCP_ALLOW_EPHEMERAL")) {
            assertIs<McpRuntime.Rejected>(McpRuntime.resolve(oidcEnvironment() - key))
        }
    }

    @Test
    fun `oidc without public url enables authentication without metadata`() {
        val enabled = assertIs<McpRuntime.Enabled>(McpRuntime.resolve(oidcEnvironment() - "RECL_PUBLIC_URL"))
        assertEquals("oidc", enabled.authMode)
        assertNull(enabled.publicUrl)
        assertNull(enabled.metadataHandler)
    }

    @Test
    fun `invalid optional oidc settings are rejected`() {
        for ((key, value) in listOf(
            "RECL_OIDC_JWKS_URI" to "http://remote.example/jwks",
            "RECL_OIDC_JWKS_URI" to "not-a-url",
            "RECL_PUBLIC_URL" to "https://recl.example/#fragment",
            "RECL_PUBLIC_URL" to "https://recl.example/?query=value",
            "RECL_OIDC_REQUIRED_SCOPE" to "openid profile",
            "RECL_OIDC_REQUIRED_SCOPE" to "",
            "RECL_OIDC_AUDIENCE" to "",
        )) {
            val rejected = assertIs<McpRuntime.Rejected>(McpRuntime.resolve(oidcEnvironment() + (key to value)))
            assertTrue(rejected.reason.contains(key))
        }
    }

    @Test
    fun `unknown auth mode is rejected and explicit token preserves existing behavior`() {
        assertIs<McpRuntime.Rejected>(McpRuntime.resolve(mcpEnvironment() + ("RECL_MCP_AUTH" to "unknown")))
        val enabled = assertIs<McpRuntime.Enabled>(McpRuntime.resolve(mcpEnvironment() + ("RECL_MCP_AUTH" to "token")))
        assertEquals("token", enabled.authMode)
        assertNull(enabled.metadataHandler)
        assertEquals("token", assertIs<McpRuntime.Enabled>(McpRuntime.resolve(mcpEnvironment())).authMode)
    }
}
