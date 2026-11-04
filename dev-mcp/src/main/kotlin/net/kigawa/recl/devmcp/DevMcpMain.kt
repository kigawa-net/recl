package net.kigawa.recl.devmcp

import net.kigawa.recl.core.ContextReader
import net.kigawa.recl.core.ContextService
import net.kigawa.recl.core.ContextTokenCounter
import net.kigawa.recl.core.PinnedContextReader
import net.kigawa.recl.core.Principal
import net.kigawa.recl.core.SessionAuthenticator
import net.kigawa.recl.mcp.McpContextServer
import net.kigawa.recl.mcp.McpContextTools
import net.kigawa.recl.mcp.McpStdioTransport
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Development-only stdio launcher for Claude Code (recl #7).
 *
 * This process uses `InMemoryContextStorage`: every Context disappears when the process exits.
 * It exists so the MCP protocol can be exercised locally before Fortis and a real authenticator
 * are available. It is deliberately a separate Gradle module and is NOT part of the production
 * `:app` image. Starting it requires the explicit `RECL_DEV_MCP_ALLOW_EPHEMERAL=1` acknowledgement.
 */
object DevMcpMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(System.getenv(), System.`in`, System.out, System.err))
    }

    fun run(
        env: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        error: PrintStream,
    ): Int {
        val config = DevMcpConfig.from(env)
        if (config is DevMcpConfig.Rejected) {
            error.println("recl dev MCP refused to start: ${config.reason}")
            return 2
        }
        val accepted = config as DevMcpConfig.Accepted
        error.println(
            "WARNING: recl dev MCP uses EPHEMERAL in-memory storage; all Context is lost on exit. " +
                "Never deploy this process or treat it as production memory.",
        )

        val storage = InMemoryContextStorage()
        val authenticator = SessionAuthenticator { presented ->
            if (constantTimeEquals(presented, accepted.token)) {
                Principal(accepted.tenantId, accepted.ownerId)
            } else {
                null
            }
        }
        val tools = McpContextTools(
            service = ContextService(storage),
            reader = ContextReader(storage),
            pinnedReader = PinnedContextReader(storage, DEV_TOKEN_COUNTER),
            authenticator = authenticator,
            bearerCredential = accepted.token,
        )
        McpStdioTransport(McpContextServer(tools), input, output).run()
        return 0
    }

    /** A character heuristic, not a model tokenizer; only the dev budget guard uses it. */
    private val DEV_TOKEN_COUNTER = ContextTokenCounter {
        it.title.length + it.summary.length + it.content.length
    }

    private fun constantTimeEquals(presented: String, expected: String): Boolean =
        MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

sealed interface DevMcpConfig {
    data class Accepted(val token: String, val tenantId: String, val ownerId: String) : DevMcpConfig
    data class Rejected(val reason: String) : DevMcpConfig

    companion object {
        const val ALLOW_ENV = "RECL_DEV_MCP_ALLOW_EPHEMERAL"
        const val TOKEN_ENV = "RECL_DEV_MCP_TOKEN"
        const val TENANT_ENV = "RECL_DEV_MCP_TENANT_ID"
        const val OWNER_ENV = "RECL_DEV_MCP_OWNER_ID"
        private const val MIN_TOKEN_LENGTH = 16

        fun from(env: Map<String, String>): DevMcpConfig {
            if (env[ALLOW_ENV] != "1") {
                return Rejected("set $ALLOW_ENV=1 to acknowledge that storage is ephemeral and dev-only")
            }
            val token = env[TOKEN_ENV] ?: return Rejected("$TOKEN_ENV is required")
            if (token.isBlank() || token.length < MIN_TOKEN_LENGTH) {
                return Rejected("$TOKEN_ENV must be at least $MIN_TOKEN_LENGTH non-blank characters")
            }
            val tenantId = env[TENANT_ENV] ?: return Rejected("$TENANT_ENV is required")
            if (!validId(tenantId)) return Rejected("$TENANT_ENV must be non-blank without whitespace")
            val ownerId = env[OWNER_ENV] ?: return Rejected("$OWNER_ENV is required")
            if (!validId(ownerId)) return Rejected("$OWNER_ENV must be non-blank without whitespace")
            return Accepted(token, tenantId, ownerId)
        }

        private fun validId(value: String): Boolean = value.isNotBlank() && value.none { it.isWhitespace() }
    }
}
