package net.kigawa.recl.github

import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubInstallationBrokerTest {
    private val nowInstant = Instant.parse("2026-10-12T09:00:00Z")
    private val now = nowInstant.toEpochMilli()
    private val clock = Clock.fixed(nowInstant, ZoneOffset.UTC)
    private val scope = SourceVerificationScope("tenant-a", "context-1", "owner-1", 1)
    private val repo = GitHubRepository("kigawa-net", "recl")
    private val grant = GitHubInstallationGrant(
        "tenant-a", "owner-1", repo, installationId = 1234,
        generation = 5, expiresAtEpochMillis = now + 3_600_000,
    )

    private fun fixture(
        jwtKey: String = "ghs_fake_installation",
        name: String = "kigawa-net/recl",
        expiry: String = "2026-10-12T09:30:00Z",
        permissions: String = """{"contents":"read","issues":"read","pull_requests":"read"}""",
        withRepositories: Boolean = true,
    ): ByteArray {
        val repository = if (withRepositories)
            ""","repositories":[{"full_name":"$name"}]""" else ""
        return """{"token":"$jwtKey","expires_at":"$expiry","permissions":$permissions$repository}"""
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun makeIdentity(): Pair<GitHubAppSigningIdentity, java.security.PublicKey> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return GitHubAppSigningIdentity(998877, pair.private) to pair.public
    }

    @Test fun `trusted tenant owner and repo grant needed for token issue`() {
        var record: GitHubInstallationGrant? = grant
        var issued = 0
        val broker = AuthoritativeGitHubAppAccess(
            GitHubInstallationGrantLedger { _, _ -> record },
            GitHubInstallationTokenIssuer {
                issued++
                GitHubInstallationToken("server-token", now + 900_000)
            }, clock
        )
        assertTrue(broker.canRead(scope, repo))
        assertNotNull(broker.getToken(scope, repo))
        assertEquals(1, issued)
        assertFalse(broker.canRead(scope.copy(tenantId = "tenant-b"), repo))
        assertEquals(null, broker.getToken(scope.copy(ownerId = "other"), repo))
        assertEquals(null, broker.getToken(scope, GitHubRepository("elsewhere", "repo")))
        assertEquals(1, issued)
        record = grant.copy(revoked = true)
        assertFalse(broker.canRead(scope, repo))
        assertEquals(null, broker.getToken(scope, repo))
        assertEquals(1, issued)
    }

    @Test fun `grant generation change during token request rejects token`() {
        var record = grant
        val broker = AuthoritativeGitHubAppAccess(
            GitHubInstallationGrantLedger { _, _ -> record },
            GitHubInstallationTokenIssuer {
                record = record.copy(generation = record.generation + 1)
                GitHubInstallationToken("late-token", now + 900_000)
            }, clock
        )
        assertEquals(null, broker.getToken(scope, repo))
    }

    @Test fun `revocation during token request and expired grants reject access`() {
        var record = grant
        val broker = AuthoritativeGitHubAppAccess(
            GitHubInstallationGrantLedger { _, _ -> record },
            GitHubInstallationTokenIssuer {
                record = record.copy(revoked = true)
                GitHubInstallationToken("late-token", now + 900_000)
            }, clock
        )
        assertEquals(null, broker.getToken(scope, repo))
        record = grant.copy(expiresAtEpochMillis = now + 10_000)
        assertFalse(broker.canRead(scope, repo))
        assertEquals(null, broker.getToken(scope, repo))
    }

    @Test fun `token lifetime cannot exceed the tenant grant lifetime`() {
        val short = grant.copy(expiresAtEpochMillis = now + 120_000)
        val broker = AuthoritativeGitHubAppAccess(
            GitHubInstallationGrantLedger { _, _ -> short },
            GitHubInstallationTokenIssuer {
                GitHubInstallationToken("token", now + 900_000)
            }, clock
        )
        assertEquals(now + 120_000, broker.getToken(scope, repo)?.expiresAtEpochMillis)
    }

    @Test fun `installation and signing key never appear in token string representation`() {
        val secret = "ghs_PLEASE_NEVER_PRINT"
        val token = GitHubInstallationToken(secret, now + 100_000)
        assertFalse(token.toString().contains(secret))
        assertTrue(token.toString().contains("[REDACTED]"))
        assertFalse(makeIdentity().first.toString().contains("PRIVATE KEY"))
    }

    @Test fun `app RS256 JWT is signed and pinned to app id`() {
        val (identity, publicKey) = makeIdentity()
        val jwt = identity.signedJwt(clock)
        val segments = jwt.split(".")
        assertEquals(3, segments.size)
        val decoder = Base64.getUrlDecoder()
        val payload = String(decoder.decode(segments[1]), StandardCharsets.UTF_8)
        assertTrue(payload.contains("\"iss\":998877"))
        assertTrue(payload.contains("\"exp\":"))
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(publicKey)
        sig.update((segments[0] + "." + segments[1]).toByteArray(StandardCharsets.US_ASCII))
        assertTrue(sig.verify(decoder.decode(segments[2])))
    }

    @Test fun `real installation endpoint is fixed and asks for only repository read grants`() {
        val identity = makeIdentity().first
        var request: HttpRequest? = null
        val engine = GitHubHttpEngine { r, maxBytes ->
            request = r
            assertEquals(16 * 1024, maxBytes)
            BoundedGitHubHttpResponse(201, fixture())
        }
        val token = GitHubRestInstallationTokenIssuer(identity, engine, clock).issue(grant)
        assertEquals("ghs_fake_installation", token?.value)
        assertEquals("POST", request?.method())
        assertEquals("https", request?.uri()?.scheme)
        assertEquals("api.github.com", request?.uri()?.host)
        assertEquals("/app/installations/1234/access_tokens", request?.uri()?.path)
        assertTrue(request!!.headers().firstValue("Authorization").orElse("").startsWith("Bearer eyJ"))
        assertFalse(request!!.uri().toString().contains("ghs_"))
    }

    @Test fun `overprivileged or wrong repository token response never passes`() {
        val identity = makeIdentity().first
        val badResponses = listOf(
            fixture(name = "elsewhere/repo"),
            fixture(permissions = """{"contents":"write","issues":"read","pull_requests":"read"}"""),
            fixture(permissions = """{"contents":"read","issues":"read","pull_requests":"read","administration":"read"}"""),
            fixture(permissions = """{"contents":"read","issues":"read"}"""),
            fixture(withRepositories = false),
            fixture(expiry = "2026-10-12T08:59:59Z"),
        )
        for (bad in badResponses) {
            val issuer = GitHubRestInstallationTokenIssuer(
                identity, GitHubHttpEngine { _, _ -> BoundedGitHubHttpResponse(201, bad) }, clock
            )
            assertEquals(null, issuer.issue(grant))
        }
    }

    @Test fun `github token endpoint failure or invalid payload never mints credential`() {
        val identity = makeIdentity().first
        for (status in listOf(200, 301, 302, 401, 403, 404, 429, 500)) {
            val issuer = GitHubRestInstallationTokenIssuer(
                identity, GitHubHttpEngine { _, _ -> BoundedGitHubHttpResponse(status, fixture()) },
                clock
            )
            assertEquals(null, issuer.issue(grant))
        }
        val malformed = GitHubRestInstallationTokenIssuer(
            identity, GitHubHttpEngine { _, _ ->
                BoundedGitHubHttpResponse(201, "{malformed".toByteArray())
            }, clock
        )
        assertEquals(null, malformed.issue(grant))
    }

    @Test fun `gitHub source attestor rechecks repository access after slow remote read`() {
        var allowed = true
        val githubGrant = GitHubRepositoryGrant { _, _ -> allowed }
        val identity = GitHubInstallationCredentials { _, _ ->
            GitHubInstallationToken("server-token", now + 900_000)
        }
        val commit = "a".repeat(40)
        val treeSha = "b".repeat(40)
        val response = """{"sha":"$commit","commit":{"tree":{"sha":"$treeSha"},"message":"ok"}}"""
            .toByteArray()
        val citation = SourceSubmission(
            sourceId = "github-ref",
            type = SourceType.GITHUB_COMMIT,
            uri = "https://github.com/kigawa-net/recl/commit/$commit",
            contentDigest = GitHubEvidenceDigest.fromResponse(
                GitHubSourceTarget(repo, SourceType.GITHUB_COMMIT, commit), response
            )!!.contentDigest,
            trust = SourceTrust.PRIMARY_SOURCE
        ).unverified()
        val reader = GitHubDocumentReader { _, _ ->
            allowed = false
            GitHubReadResult.Success(response)
        }
        val attestor = GitHubSourceAttestor(githubGrant, identity, reader, "recl-source-verifier", clock)
        assertEquals(
            SourceAttestationResult.Unavailable,
            attestor.attest(scope, citation)
        )
    }
}
