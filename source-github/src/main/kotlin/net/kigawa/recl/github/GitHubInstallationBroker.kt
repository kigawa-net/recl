package net.kigawa.recl.github

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * A trusted authoritative record, not a claim from Context.scopes or a GitHub URI.
 * The repository grant ledger must provide linearizable/revocation-aware reads in production.
 */
data class GitHubInstallationGrant(
    val tenantId: String,
    val ownerId: String,
    val repository: GitHubRepository,
    val installationId: Long,
    val generation: Long,
    val expiresAtEpochMillis: Long,
    val revoked: Boolean = false,
) {
    init {
        require(tenantId.isNotBlank() && ownerId.isNotBlank())
        require(tenantId.length <= 128 && ownerId.length <= 128)
        require(!tenantId.any(Char::isISOControl) && !ownerId.any(Char::isISOControl))
        require(installationId > 0 && generation > 0 && expiresAtEpochMillis > 0)
    }
}

/**
 * This is a server-only trust boundary. A fake implementation is intended only for tests.
 * Real deployments must independently authenticate tenant, principal, installation and revocation.
 */
fun interface GitHubInstallationGrantLedger {
    fun read(scope: net.kigawa.recl.core.SourceVerificationScope, repository: GitHubRepository): GitHubInstallationGrant?
}

fun interface GitHubInstallationTokenIssuer {
    fun issue(grant: GitHubInstallationGrant): GitHubInstallationToken?
}

/**
 * Implements both existing GitHubSourceAttestor dependencies with the same authoritative grant.
 * Re-reads the grant before AND after issuing an installation token, fencing revoked or replaced
 * grant generations. No cross-request token cache is used, so stale grants cannot authorize minting.
 */
class AuthoritativeGitHubAppAccess(
    private val ledger: GitHubInstallationGrantLedger,
    private val issuer: GitHubInstallationTokenIssuer,
    private val clock: Clock = Clock.systemUTC(),
) : GitHubRepositoryGrant, GitHubInstallationCredentials {
    private fun current(
        scope: net.kigawa.recl.core.SourceVerificationScope,
        repo: GitHubRepository,
    ): GitHubInstallationGrant? {
        if (scope.tenantId.isBlank() || scope.ownerId.isBlank()) return null
        val found = try { ledger.read(scope, repo) } catch (_: Exception) { null } ?: return null
        val now = clock.millis()
        if (found.tenantId != scope.tenantId || found.ownerId != scope.ownerId ||
            !found.repository.owner.equals(repo.owner, ignoreCase = true) ||
            !found.repository.name.equals(repo.name, ignoreCase = true) ||
            found.revoked || found.expiresAtEpochMillis <= now + 30_000
        ) return null
        return found
    }

    override fun canRead(
        scope: net.kigawa.recl.core.SourceVerificationScope,
        repository: GitHubRepository,
    ): Boolean = current(scope, repository) != null

    override fun getToken(
        scope: net.kigawa.recl.core.SourceVerificationScope,
        repository: GitHubRepository,
    ): GitHubInstallationToken? {
        val grant = current(scope, repository) ?: return null
        val token = try { issuer.issue(grant) } catch (_: Exception) { null } ?: return null
        val again = current(scope, repository) ?: return null
        if (again != grant || token.expiresAtEpochMillis <= clock.millis() + 30_000)
            return null
        val boundedExpiry = minOf(grant.expiresAtEpochMillis, token.expiresAtEpochMillis)
        if (boundedExpiry <= clock.millis() + 30_000) return null
        return GitHubInstallationToken(token.value, boundedExpiry)
    }
}

/** A server-owned GitHub App RSA signing key. Never serialize or log the key. */
class GitHubAppSigningIdentity(val appId: Long, val privateKey: PrivateKey) {
    init {
        require(appId > 0)
        require(privateKey.algorithm.equals("RSA", ignoreCase = true))
    }

    internal fun signedJwt(clock: Clock): String {
        val seconds = clock.instant().epochSecond
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = """{"iat":${seconds - 60},"exp":${seconds + 8 * 60},"iss":$appId}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun part(s: String) = encoder.encodeToString(s.toByteArray(StandardCharsets.UTF_8))
        val signingInput = part(header) + "." + part(claims)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(signingInput.toByteArray(StandardCharsets.US_ASCII))
        return signingInput + "." + encoder.encodeToString(signature.sign())
    }

    override fun toString(): String = "GitHubAppSigningIdentity(appId=$appId, privateKey=[REDACTED])"
}

/**
 * GitHub App installation token endpoint client, restricted to a single repository.
 * Always posts to fixed https://api.github.com; does not follow redirects in the production engine.
 * Does not accept PATs, arbitrary URLs or client-provided repo grants.
 */
class GitHubRestInstallationTokenIssuer(
    private val signingIdentity: GitHubAppSigningIdentity,
    private val engine: GitHubHttpEngine = JdkGitHubHttpEngine(),
    private val clock: Clock = Clock.systemUTC(),
) : GitHubInstallationTokenIssuer {
    private val mapper = ObjectMapper()
    private val allowedPermissions = setOf("contents", "issues", "pull_requests", "metadata")

    override fun issue(grant: GitHubInstallationGrant): GitHubInstallationToken? {
        if (grant.revoked || grant.expiresAtEpochMillis <= clock.millis() + 30_000) return null
        val jsonName = mapper.writeValueAsString(grant.repository.name)
        val requestBody = """{"repositories":[$jsonName],"permissions":{"contents":"read","issues":"read","pull_requests":"read"}}"""
        val endpoint = URI.create("https://api.github.com/app/installations/${grant.installationId}/access_tokens")
        val request = try {
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + signingIdentity.signedJwt(clock))
                .header("Content-Type", "application/json")
                .header("Cache-Control", "no-store")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
        } catch (_: Exception) { return null }

        val response = try { engine.execute(request, 16 * 1024) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            catch (_: Exception) { return null }
        if (response.status != 201 || response.body.size > 16 * 1024) return null
        val obj = try { mapper.readTree(response.body) } catch (_: Exception) { return null }
        if (!obj.isObject) return null
        val value = obj.path("token").takeIf { it.isTextual }?.textValue() ?: return null
        if (value.isBlank() || value.length > 1024 || value.any(Char::isISOControl)) return null
        val expiry = try {
            Instant.parse(obj.path("expires_at").textValue() ?: return null).toEpochMilli()
        } catch (_: Exception) { return null }
        if (expiry <= clock.millis() + 30_000) return null

        // Tokens must be scoped only to the selected repository. A missing repositories array
        // could indicate an all-repository token and must not be accepted.
        val repos = obj.path("repositories")
        if (!repos.isArray || repos.size() != 1) return null
        val actualFullName = repos[0].path("full_name").takeIf { it.isTextual }?.textValue()
            ?: return null
        val expectedFullName = grant.repository.owner + "/" + grant.repository.name
        if (!actualFullName.equals(expectedFullName, ignoreCase = true)) return null

        val perms = obj.path("permissions")
        if (!perms.isObject) return null
        val fields = perms.fieldNames().asSequence().toList()
        if (fields.any { it !in allowedPermissions }) return null
        for (permission in listOf("contents", "issues", "pull_requests")) {
            if (perms.path(permission).textValue() != "read") return null
        }
        if (perms.has("metadata") && perms.path("metadata").textValue() != "read") return null
        return GitHubInstallationToken(value, expiry)
    }
}
