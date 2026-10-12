package net.kigawa.recl.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import net.kigawa.recl.core.*

/** Only repository identities derived from a validated GitHub Source URI are accepted. */
data class GitHubRepository(val owner: String, val name: String) {
    init {
        val slug = Regex("[A-Za-z0-9._-]{1,128}")
        require(slug.matches(owner) && slug.matches(name))
    }
}

/** Implementations must be server owned; never derive grants from a client Scope. */
fun interface GitHubRepositoryGrant {
    fun canRead(scope: SourceVerificationScope, repository: GitHubRepository): Boolean
}

data class GitHubInstallationToken(val value: String, val expiresAtEpochMillis: Long) {
    init {
        require(value.isNotBlank() && !value.any { it.isISOControl() })
        require(expiresAtEpochMillis > 0)
    }
}
fun interface GitHubInstallationCredentials {
    fun getToken(scope: SourceVerificationScope, repository: GitHubRepository): GitHubInstallationToken?
}

data class GitHubSourceTarget(val repository: GitHubRepository, val type: SourceType, val identity: String) {
    init {
        require(type in setOf(SourceType.GITHUB_COMMIT, SourceType.GITHUB_ISSUE, SourceType.GITHUB_PR))
        if (type == SourceType.GITHUB_COMMIT)
            require(Regex("[0-9a-f]{40}").matches(identity))
        else
            require(Regex("[1-9][0-9]*").matches(identity))
    }

    companion object {
        fun from(source: ContextSource): GitHubSourceTarget? {
            if (source.type !in setOf(SourceType.GITHUB_COMMIT, SourceType.GITHUB_ISSUE, SourceType.GITHUB_PR))
                return null
            val canonical = try {
                SourceReferencePolicy.canonicalUri(source.type, source.uri)
            } catch (_: IllegalArgumentException) { return null }
            if (canonical != source.uri) return null
            val parts = canonical.removePrefix("https://github.com/").split("/")
            if (parts.size != 4) return null
            return try {
                GitHubSourceTarget(GitHubRepository(parts[0], parts[1]), source.type, parts[3])
            } catch (_: IllegalArgumentException) { null }
        }
    }
}

sealed interface GitHubReadResult {
    data class Success(val body: ByteArray) : GitHubReadResult
    data object ForbiddenOrMissing : GitHubReadResult
    data object Unavailable : GitHubReadResult
    data object OutcomeUnknown : GitHubReadResult
}

/** Fakes are for contract tests only; wire the real GitHubRestDocumentReader in production. */
fun interface GitHubDocumentReader {
    fun read(target: GitHubSourceTarget, accessToken: GitHubInstallationToken): GitHubReadResult
}

data class EvidenceProjection(val contentDigest: String, val version: String)

/**
 * Domain-separated SHA256 over length-prefixed UTF-8 fields of a defined GitHub API
 * projection. NOT the Git object SHA or the raw, noncanonical JSON.
 *
 * Commit: repository, SHA, tree SHA, commit message.
 * Issue: repository, number, updated_at, title, body, state.
 * Pull: repository, number, updated_at, title, body, state, head SHA, base SHA.
 * Comments, reviews, timeline, labels and attachments are not attested.
 */
object GitHubEvidenceDigest {
    private val mapper = ObjectMapper()
    private val sha = Regex("[0-9a-fA-F]{40}")

    fun fromResponse(target: GitHubSourceTarget, body: ByteArray): EvidenceProjection? {
        if (body.isEmpty() || body.size > 512 * 1024) return null
        val root = try { mapper.readTree(body) } catch (_: Exception) { return null }
        if (!root.isObject) return null
        fun field(vararg path: String): String? {
            var node: JsonNode = root
            for (part in path) node = node.path(part)
            return if (node.isTextual && node.textValue().length <= 64 * 1024)
                node.textValue() else null
        }
        val repo = target.repository.owner + "/" + target.repository.name
        val domain: String
        val fields: List<String>
        when (target.type) {
            SourceType.GITHUB_COMMIT -> {
                val gitSha = field("sha") ?: return null
                val treeSha = field("commit", "tree", "sha") ?: return null
                val message = field("commit", "message") ?: return null
                if (!sha.matches(gitSha) || !sha.matches(treeSha) ||
                    !gitSha.equals(target.identity, ignoreCase = true)) return null
                domain = "recl.github.commit.evidence.v1"
                fields = listOf(repo, gitSha.lowercase(Locale.ROOT),
                    treeSha.lowercase(Locale.ROOT), message)
            }
            SourceType.GITHUB_ISSUE, SourceType.GITHUB_PR -> {
                val number = root.path("number").asLong(-1)
                if (number <= 0 || number.toString() != target.identity) return null
                val updated = field("updated_at") ?: return null
                val title = field("title") ?: return null
                val bodyNode = root.path("body")
                val description = when {
                    bodyNode.isNull || bodyNode.isMissingNode -> ""
                    bodyNode.isTextual && bodyNode.textValue().length <= 64 * 1024 ->
                        bodyNode.textValue()
                    else -> return null
                }
                val state = field("state") ?: return null
                val updatedAt = try { Instant.parse(updated).toString() } catch (_: Exception) { return null }
                if (target.type == SourceType.GITHUB_PR) {
                    val head = field("head", "sha") ?: return null
                    val base = field("base", "sha") ?: return null
                    if (!sha.matches(head) || !sha.matches(base)) return null
                    domain = "recl.github.pull.evidence.v1"
                    fields = listOf(repo, target.identity, updatedAt, title, description,
                        state, head.lowercase(Locale.ROOT), base.lowercase(Locale.ROOT))
                } else {
                    if (root.has("pull_request") || root.has("head") || root.has("base")) return null
                    domain = "recl.github.issue.evidence.v1"
                    fields = listOf(repo, target.identity, updatedAt, title, description, state)
                }
            }
            else -> return null
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            for (part in listOf(domain) + fields) {
                val bytes = part.toByteArray(StandardCharsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return EvidenceProjection("sha256:" + hash, domain)
    }
}

/**
 * GitHub App grant + expiring installation token + bounded REST response are mandatory.
 * The grant and token providers are trusted-server dependencies, never client DTOs.
 */
class GitHubSourceAttestor(
    private val grants: GitHubRepositoryGrant,
    private val credentials: GitHubInstallationCredentials,
    private val reader: GitHubDocumentReader,
    private val audience: String,
    private val clock: Clock = Clock.systemUTC(),
    private val maxEvidenceMillis: Long = 10 * 60 * 1000L,
) : TrustedSourceAttestor {
    init {
        require(Regex("[A-Za-z0-9._:-]{1,128}").matches(audience))
        require(maxEvidenceMillis in 1..(60 * 60 * 1000L))
    }

    override fun attest(scope: SourceVerificationScope, source: ContextSource): SourceAttestationResult {
        if (scope.tenantId.isBlank() || scope.ownerId.isBlank() || scope.contentRevision < 1)
            return SourceAttestationResult.Unavailable
        if (source.contentDigest == null) return SourceAttestationResult.Invalid
        val target = GitHubSourceTarget.from(source) ?: return SourceAttestationResult.Invalid
        val allowed = try { grants.canRead(scope, target.repository) }
            catch (_: Exception) { false }
        if (!allowed) return SourceAttestationResult.Unavailable
        val token = try { credentials.getToken(scope, target.repository) }
            catch (_: Exception) { null }
        val now = clock.millis()
        if (token == null || token.expiresAtEpochMillis <= now + 30_000)
            return SourceAttestationResult.Unavailable
        val response = try { reader.read(target, token) }
            catch (_: Exception) { GitHubReadResult.Unavailable }
        when (response) {
            is GitHubReadResult.Success -> {
                val projection = GitHubEvidenceDigest.fromResponse(target, response.body)
                    ?: return SourceAttestationResult.Unavailable
                if (projection.contentDigest != source.contentDigest) {
                    return if (target.type == SourceType.GITHUB_COMMIT)
                        SourceAttestationResult.Invalid else SourceAttestationResult.Stale
                }
                val expiry = minOf(now + maxEvidenceMillis, token.expiresAtEpochMillis - 30_000)
                if (expiry <= now) return SourceAttestationResult.Unavailable
                // Diagnostic ID only: not a cryptographic signature or durable request receipt.
                val id = "github-" + projection.contentDigest.removePrefix("sha256:").take(24)
                return SourceAttestationResult.Verified(
                    VerificationEvidence(
                        tenantId = scope.tenantId, contextId = scope.contextId,
                        sourceId = source.sourceId, canonicalUri = source.uri,
                        contentDigest = projection.contentDigest,
                        boundContentRevision = scope.contentRevision,
                        providerIdentity = "github-app", audience = audience,
                        attestationId = id, verifiedAtEpochMillis = now,
                        expiresAtEpochMillis = expiry,
                    )
                )
            }
            GitHubReadResult.ForbiddenOrMissing -> return SourceAttestationResult.Unavailable
            GitHubReadResult.Unavailable -> return SourceAttestationResult.Unavailable
            GitHubReadResult.OutcomeUnknown -> return SourceAttestationResult.OutcomeUnknown
        }
    }
}
