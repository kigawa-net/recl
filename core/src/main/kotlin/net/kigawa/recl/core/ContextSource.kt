package net.kigawa.recl.core

import java.net.URI
import java.util.Locale

/** Classification of how an assertion was obtained, not proof of its truth. */
enum class SourceTrust { USER_STATED, PRIMARY_SOURCE, AGENT_OBSERVED, AGENT_INFERRED }

/** Names deliberately match the Kivra memory contract; transport/verification is not implemented. */
enum class SourceType {
    GITHUB_COMMIT, GITHUB_PR, GITHUB_ISSUE, FILE,
    EXKES_ARTIFACT, EXKES_LOG, FUSHA_TASK, USER_MESSAGE, URL
}

enum class VerificationState { UNVERIFIED, VERIFIED, STALE, INVALID }

/**
 * Untrusted client-supplied citation metadata. There is intentionally no 'verification' field:
 * only a future server-side attestor may promote the stored verification state.
 */
data class SourceSubmission(
    val sourceId: String,
    val type: SourceType,
    val uri: String,
    val locator: String? = null,
    val retrievedAtEpochMillis: Long? = null,
    val contentDigest: String? = null,
    val trust: SourceTrust,
) {
    fun normalized(): SourceSubmission = copy(
        sourceId = normalize(sourceId),
        uri = SourceReferencePolicy.canonicalUri(type, uri),
        locator = locator?.let(::normalize),
        contentDigest = contentDigest?.lowercase(Locale.ROOT),
    ).also { SourceReferencePolicy.validate(it) }

    /** Always creates unverified evidence: the caller cannot attest its own reference. */
    fun unverified(): ContextSource {
        val value = normalized()
        return ContextSource(
            sourceId = value.sourceId, type = value.type, uri = value.uri,
            locator = value.locator, retrievedAtEpochMillis = value.retrievedAtEpochMillis,
            contentDigest = value.contentDigest, trust = value.trust,
            verification = VerificationState.UNVERIFIED,
        )
    }
}

/** Internal authoritative Context metadata; verification is never accepted from a client DTO. */
data class ContextSource(
    val sourceId: String,
    val type: SourceType,
    val uri: String,
    val locator: String?,
    val retrievedAtEpochMillis: Long?,
    val contentDigest: String?,
    val trust: SourceTrust,
    val verification: VerificationState = VerificationState.UNVERIFIED,
    /** Internal only; never accepted on a caller-submitted SourceSubmission. */
    val evidence: VerificationEvidence? = null,
) {
    init {
        SourceReferencePolicy.validate(SourceSubmission(
            sourceId, type, uri, locator, retrievedAtEpochMillis, contentDigest, trust
        ))
        require(verification == VerificationState.VERIFIED || evidence == null) {
            "only VERIFIED citations may retain an attestation"
        }
        if (verification == VerificationState.VERIFIED) {
            require(evidence != null && evidence.sourceId == sourceId &&
                evidence.canonicalUri == uri && contentDigest != null &&
                evidence.contentDigest == contentDigest) {
                "VERIFIED requires matching internal evidence"
            }
        }
    }

    fun effectiveVerificationAt(nowEpochMillis: Long): VerificationState =
        if (verification == VerificationState.VERIFIED &&
            (evidence == null || evidence.expiresAtEpochMillis <= nowEpochMillis ||
             evidence.verifiedAtEpochMillis > nowEpochMillis)
        ) VerificationState.STALE else verification

    /** Public citation never includes provider identity, audience or attestation receipt. */
    fun publicAt(nowEpochMillis: Long): SourceCitation = SourceCitation(
        sourceId = sourceId, type = type, uri = uri, locator = locator,
        retrievedAtEpochMillis = retrievedAtEpochMillis,
        contentDigest = contentDigest, trust = trust,
        verification = effectiveVerificationAt(nowEpochMillis),
    )
}

/** Public-safe citation view without the internal attestation or provider identity. */
data class SourceCitation(
    val sourceId: String,
    val type: SourceType,
    val uri: String,
    val locator: String?,
    val retrievedAtEpochMillis: Long?,
    val contentDigest: String?,
    val trust: SourceTrust,
    val verification: VerificationState,
)

/** Strict MVP reference syntax. No network requests are made from this validator. */
object SourceReferencePolicy {
    private const val MAX_SOURCES = 16
    const val MAX_PER_CONTEXT = MAX_SOURCES
    private val id = Regex("[A-Za-z0-9._-]{1,128}")
    private val slug = "[A-Za-z0-9._-]{1,128}"
    private val sha = "[0-9a-fA-F]{40}"
    private val digest = Regex("sha256:[0-9a-f]{64}")
    private val dangerousSegment = Regex(
        "(?i)(token|password|secret|api[-_]?key|access[-_]?key|credential|signature|session|authorization)"
    )

    fun normalizeMany(values: List<SourceSubmission>): List<SourceSubmission> {
        require(values.size <= MAX_SOURCES) { "too many sources" }
        val canonical = values.map { it.normalized() }.sortedBy { it.sourceId }
        require(canonical.map { it.sourceId }.distinct().size == canonical.size) {
            "sourceId must be unique within a Context"
        }
        return canonical
    }

    fun validate(source: SourceSubmission) {
        require(id.matches(source.sourceId)) { "invalid sourceId" }
        require(source.uri.length in 1..2048 && !source.uri.any { it.isISOControl() })
        require(source.locator == null || (source.locator.length <= 512 &&
            !source.locator.any { it.isISOControl() || it in "?&=@" } &&
            !dangerousSegment.containsMatchIn(source.locator))) { "unsafe locator" }
        require(source.contentDigest == null || digest.matches(source.contentDigest))
        require(source.retrievedAtEpochMillis == null || source.retrievedAtEpochMillis >= 0L)
        // Must be an approved immutable/opaque reference shape; not necessarily verified evidence.
        canonicalUri(source.type, source.uri)
    }

    fun canonicalUri(type: SourceType, raw: String): String {
        require(raw.length in 1..2048 && !raw.any { it.isISOControl() })
        require(!raw.contains('%')) { "percent-encoded secrets/paths are not accepted by the MVP" }
        require(!raw.contains('\\')) { "ambiguous URL separators are rejected" }
        val uri = try { URI(raw) } catch (_: Exception) {
            throw IllegalArgumentException("invalid source URI")
        }
        require(uri.isAbsolute && !uri.isOpaque && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) {
            "source URI must not contain credentials, query or fragment"
        }
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("source host missing")
        require(uri.port == -1) { "non-standard ports are not allowed" }
        val path = uri.path ?: ""
        require(path.split('/').none { it == "." || it == ".." }) { "path traversal" }
        require(path.split('/').none { dangerousSegment.matches(it) }) { "secret-like path segment" }

        when (type) {
            SourceType.GITHUB_COMMIT, SourceType.GITHUB_PR, SourceType.GITHUB_ISSUE -> {
                require(scheme == "https" && host == "github.com")
                val segments = path.trim('/').split('/')
                require(segments.size == 4 && segments.take(2).all { id.matches(it) })
                when (type) {
                    SourceType.GITHUB_COMMIT ->
                        require(segments[2] == "commit" && Regex(sha).matches(segments[3]))
                    SourceType.GITHUB_PR ->
                        require(segments[2] == "pull" && Regex("[1-9][0-9]*").matches(segments[3]))
                    SourceType.GITHUB_ISSUE ->
                        require(segments[2] == "issues" && Regex("[1-9][0-9]*").matches(segments[3]))
                    else -> error("unreachable")
                }
                return "https://github.com/${segments[0].lowercase(Locale.ROOT)}/${segments[1].lowercase(Locale.ROOT)}/${segments[2]}/${segments[3].lowercase(Locale.ROOT)}"
            }
            SourceType.URL -> {
                require(scheme == "https")
                // Store only public DNS names, never literal IPv4/IPv6 targets.
                require(Regex("[a-z0-9-]+(\\.[a-z0-9-]+)+").matches(host) &&
                    host.substringAfterLast('.').any { it in 'a'..'z' }) {
                    "URL host must be a public DNS name, not an IP literal"
                }
                require(host != "localhost" && !host.endsWith(".localhost") &&
                    !host.endsWith(".local") && !host.endsWith(".internal") &&
                    !host.endsWith(".test") && !host.endsWith(".invalid")) { "private host" }
                require(!Regex("^(127|10|169\\.254|192\\.168)\\.").containsMatchIn(host)) {
                    "private or loopback IP"
                }
                // This is NOT an SSRF defense for later fetchers; DNS must be checked at fetch time.
                return "https://$host$path"
            }
            SourceType.FILE -> {
                require(scheme == "repo" && id.matches(host))
                val parts = path.trim('/').split('/')
                require(parts.size >= 3 && id.matches(parts[0]) && Regex(sha).matches(parts[1]))
                require(parts.drop(2).all { id.matches(it) && it != "." && it != ".." })
                return "repo://$host/${parts[0].lowercase(Locale.ROOT)}/${parts[1].lowercase(Locale.ROOT)}/${parts.drop(2).joinToString("/")}"
            }
            SourceType.EXKES_ARTIFACT, SourceType.EXKES_LOG -> {
                require(scheme == "exkes" && id.matches(host))
                val parts = path.trim('/').split('/')
                val kind = if (type == SourceType.EXKES_ARTIFACT) "artifact" else "log"
                require(parts.size == 3 && id.matches(parts[0]) && parts[1] == kind &&
                    digest.matches("sha256:${parts[2].lowercase(Locale.ROOT)}")) {
                    "exkes reference must identify execution and immutable content digest"
                }
                return "exkes://$host/${parts[0]}/$kind/${parts[2].lowercase(Locale.ROOT)}"
            }
            SourceType.FUSHA_TASK, SourceType.USER_MESSAGE -> {
                val expected = if (type == SourceType.FUSHA_TASK) "fusha" else "conversation"
                require(scheme == expected && id.matches(host))
                val parts = path.trim('/').split('/')
                require(parts.size == 1 && id.matches(parts[0]))
                return "$scheme://$host/${parts[0]}"
            }
        }
    }
}
