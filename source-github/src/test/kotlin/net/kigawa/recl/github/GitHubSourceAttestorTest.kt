package net.kigawa.recl.github

import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.recl.core.*
import net.kigawa.recl.storage.memory.InMemoryContextStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubSourceAttestorTest {
    private val now = Instant.parse("2026-10-12T08:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repo = GitHubRepository("kigawa-net", "recl")
    private val sha = "a".repeat(40)
    private val treeSha = "b".repeat(40)
    private val target = GitHubSourceTarget(repo, SourceType.GITHUB_COMMIT, sha)
    private val scope = SourceVerificationScope("tenant-a", "context-1", "owner", 1)
    private val token = GitHubInstallationToken("test-server-credential", now + 90 * 60 * 1000)
    private val audience = "recl-source-verifier"

    private fun commitJson(
        commitSha: String = sha,
        message: String = "trusted change",
    ) = """{"sha":"$commitSha","commit":{"tree":{"sha":"$treeSha"},"message":"$message"}}""".toByteArray()

    private fun issueJson(
        number: Int = 35,
        updated: String = "2026-10-12T07:00:00Z",
        title: String = "GitHub SourceVerifier",
        body: String = "first body",
    ) = """{"number":$number,"updated_at":"$updated","title":"$title","body":"$body","state":"open"}""".toByteArray()

    private fun pullJson(
        number: Int = 57,
        head: String = sha,
        updated: String = "2026-10-12T07:00:00Z",
    ) = """{"number":$number,"updated_at":"$updated","title":"Provider","body":"request","state":"open","head":{"sha":"$head"},"base":{"sha":"$treeSha"}}""".toByteArray()

    private fun src(t: GitHubSourceTarget, bytes: ByteArray): ContextSource {
        val kind = when (t.type) {
            SourceType.GITHUB_COMMIT -> "commit"
            SourceType.GITHUB_ISSUE -> "issues"
            SourceType.GITHUB_PR -> "pull"
            else -> error("unexpected")
        }
        val uri = "https://github.com/kigawa-net/recl/" + kind + "/" + t.identity
        return SourceSubmission("s-1", t.type, uri,
            contentDigest = GitHubEvidenceDigest.fromResponse(t, bytes)!!.contentDigest,
            trust = SourceTrust.PRIMARY_SOURCE).unverified()
    }

    private fun attestor(
        bytes: ByteArray = commitJson(),
        granted: Boolean = true,
        sourceReader: GitHubDocumentReader = GitHubDocumentReader { _, _ -> GitHubReadResult.Success(bytes) },
        credential: GitHubInstallationToken? = token,
        grant: GitHubRepositoryGrant = GitHubRepositoryGrant { request, repository ->
            granted && request.tenantId == "tenant-a" && request.ownerId == "owner" &&
                repository == repo
        },
    ) = GitHubSourceAttestor(grant,
        GitHubInstallationCredentials { _, _ -> credential }, sourceReader, audience, clock)

    @Test fun commitHasIndependentSha256ProjectionAndBoundedProof() {
        val body = commitJson()
        val citation = src(target, body)
        val result = attestor().attest(scope, citation)
        assertTrue(result is SourceAttestationResult.Verified)
        val evidence = (result as SourceAttestationResult.Verified).evidence
        assertEquals(citation.contentDigest, evidence.contentDigest)
        assertEquals(scope.tenantId, evidence.tenantId)
        assertEquals(scope.contentRevision, evidence.boundContentRevision)
        assertEquals(audience, evidence.audience)
        assertTrue(evidence.expiresAtEpochMillis > now)
        assertTrue(evidence.expiresAtEpochMillis <= now + 10 * 60 * 1000)
        assertTrue(citation.contentDigest!!.startsWith("sha256:"))
        assertFalse(citation.contentDigest!!.endsWith(sha)) // Git OID is NOT the evidence SHA256.
    }

    @Test fun wrongCommitContentDigestCannotVerify() {
        val citation = src(target, commitJson())
        val changed = citation.copy(contentDigest = "sha256:" + "f".repeat(64))
        assertEquals(SourceAttestationResult.Invalid, attestor().attest(scope, changed))
        assertEquals(SourceAttestationResult.Unavailable,
            attestor(bytes = commitJson(commitSha = "c".repeat(40))).attest(scope, citation))
    }

    @Test fun mutatedIssueSnapshotMustBeStaleEvenIfNumberMatches() {
        val issue = GitHubSourceTarget(repo, SourceType.GITHUB_ISSUE, "35")
        val original = issueJson()
        val citation = src(issue, original)
        assertTrue(attestor(sourceReader = GitHubDocumentReader { _, _ ->
            GitHubReadResult.Success(original)
        }).attest(scope, citation) is SourceAttestationResult.Verified)
        val altered = issueJson(updated = "2026-10-12T07:30:00Z", body = "changed")
        assertEquals(SourceAttestationResult.Stale,
            attestor(sourceReader = GitHubDocumentReader { _, _ ->
                GitHubReadResult.Success(altered)
            }).attest(scope, citation))
    }

    @Test fun pullHeadRevisionIsPartOfDigest() {
        val pull = GitHubSourceTarget(repo, SourceType.GITHUB_PR, "57")
        val citation = src(pull, pullJson())
        val changed = pullJson(head = "c".repeat(40))
        assertEquals(SourceAttestationResult.Stale,
            attestor(sourceReader = GitHubDocumentReader { _, _ ->
                GitHubReadResult.Success(changed)
            }).attest(scope, citation))
        assertEquals(null, GitHubEvidenceDigest.fromResponse(
            GitHubSourceTarget(repo, SourceType.GITHUB_ISSUE, "57"), pullJson()))
    }

    @Test fun repositoryGrantIsCheckedBeforeTokenOrNetworkAccess() {
        var credsCalled = false
        var networkCalled = false
        val verifier = GitHubSourceAttestor(
            GitHubRepositoryGrant { _, _ -> false },
            GitHubInstallationCredentials { _, _ ->
                credsCalled = true
                token
            },
            GitHubDocumentReader { _, _ ->
                networkCalled = true
                GitHubReadResult.Success(commitJson())
            }, audience, clock
        )
        assertEquals(SourceAttestationResult.Unavailable,
            verifier.attest(scope, src(target, commitJson())))
        assertFalse(credsCalled)
        assertFalse(networkCalled)
    }

    @Test fun wrongTenantOrOwnerNeverReceivesProof() {
        val citation = src(target, commitJson())
        val verifier = attestor()
        assertEquals(SourceAttestationResult.Unavailable,
            verifier.attest(scope.copy(tenantId = "tenant-b"), citation))
        assertEquals(SourceAttestationResult.Unavailable,
            verifier.attest(scope.copy(ownerId = "untrusted"), citation))
    }

    @Test fun expiringInstallationTokenIsNotUsed() {
        val citation = src(target, commitJson())
        assertEquals(SourceAttestationResult.Unavailable, attestor(
            credential = GitHubInstallationToken("expired", now + 10_000)
        ).attest(scope, citation))
    }

    @Test fun providerFailureAndLostResponseCannotProduceVerification() {
        val citation = src(target, commitJson())
        assertEquals(SourceAttestationResult.Unavailable,
            attestor(sourceReader = GitHubDocumentReader { _, _ ->
                GitHubReadResult.ForbiddenOrMissing
            }).attest(scope, citation))
        assertEquals(SourceAttestationResult.Unavailable,
            attestor(sourceReader = GitHubDocumentReader { _, _ ->
                GitHubReadResult.Unavailable
            }).attest(scope, citation))
        assertEquals(SourceAttestationResult.OutcomeUnknown,
            attestor(sourceReader = GitHubDocumentReader { _, _ ->
                GitHubReadResult.OutcomeUnknown
            }).attest(scope, citation))
    }

    @Test fun missingContentDigestCannotVerify() {
        val source = src(target, commitJson()).copy(contentDigest = null)
        assertEquals(SourceAttestationResult.Invalid, attestor().attest(scope, source))
    }

    @Test fun sourceInputCannotSelectArbitraryRemoteHostOrCredential() {
        val valid = src(target, commitJson())
        val bad = listOf(
            "https://example.com/kigawa-net/recl/commit/" + sha,
            "https://github.com/kigawa-net/recl/commit/main",
            "https://github.com/kigawa-net/recl/commit/" + sha + "?token=secret",
            "https://user:password@github.com/kigawa-net/recl/commit/" + sha,
        )
        for (uri in bad) {
            val check = try { valid.copy(uri = uri) } catch (_: IllegalArgumentException) { null }
            if (check != null)
                assertEquals(SourceAttestationResult.Invalid, attestor().attest(scope, check))
        }
    }

    @Test fun fixedHostAndHeadersAreAppliedToGitHubApiRequests() {
        var request: HttpRequest? = null
        val engine = GitHubHttpEngine { req, _ ->
            request = req
            BoundedGitHubHttpResponse(200, commitJson())
        }
        val result = GitHubRestDocumentReader(engine).read(target, token)
        assertTrue(result is GitHubReadResult.Success)
        val sent = request!!
        assertEquals("api.github.com", sent.uri().host)
        assertEquals("https", sent.uri().scheme)
        assertEquals("/repos/kigawa-net/recl/commits/" + sha, sent.uri().path)
        assertEquals(null, sent.uri().rawQuery)
        assertEquals("GET", sent.method())
        assertEquals("Bearer test-server-credential",
            sent.headers().firstValue("Authorization").orElse(null))
        assertFalse(sent.uri().toString().contains(token.value))
    }

    @Test fun redirectRateLimitForbiddenAndOversizeFailClosed() {
        for (status in listOf(301, 302, 304, 429, 500, 503)) {
            val reader = GitHubRestDocumentReader(GitHubHttpEngine { _, _ ->
                BoundedGitHubHttpResponse(status, byteArrayOf())
            })
            assertEquals(GitHubReadResult.Unavailable, reader.read(target, token))
        }
        for (status in listOf(401, 403, 404)) {
            val reader = GitHubRestDocumentReader(GitHubHttpEngine { _, _ ->
                BoundedGitHubHttpResponse(status, byteArrayOf())
            })
            assertEquals(GitHubReadResult.ForbiddenOrMissing, reader.read(target, token))
        }
        assertEquals(GitHubReadResult.Unavailable,
            GitHubRestDocumentReader(GitHubHttpEngine { _, max ->
                BoundedGitHubHttpResponse(200, ByteArray(max + 1))
            }).read(target, token))
    }

    @Test fun transportExceptionsFailClosed() {
        val reader = GitHubRestDocumentReader(GitHubHttpEngine { _, _ ->
            throw IOException("simulated connection reset")
        })
        assertEquals(GitHubReadResult.Unavailable, reader.read(target, token))
        val timeout = GitHubRestDocumentReader(GitHubHttpEngine { _, _ ->
            throw HttpTimeoutException("simulated timeout")
        })
        assertEquals(GitHubReadResult.OutcomeUnknown, timeout.read(target, token))
    }

    @Test fun evidenceBindsThroughTrustedSourceVerificationService() {
        val source = src(target, commitJson())
        val store = InMemoryContextStorage()
        val contextId = "019a1a2b-3c4d-7e8f-8a9b-1c2d3e4f5a61"
        val context = ContextAggregate("tenant-a", contextId, "owner", ContextKind.FINDING,
            ContextStatus.CONFIRMED, "Finding", "summary", "text",
            setOf("project:recl"), pinned = true, sources = listOf(source))
        assertTrue(store.createIfAbsent(context) is WriteResult.Applied)
        val trusted = SourceVerificationService(store, attestor(), audience, clock)
        assertEquals(SourceVerificationResult.Applied(2),
            trusted.verify("tenant-a", contextId, source.sourceId, 1))
        val stored = (store.get("tenant-a", contextId) as ReadResult.Found).stored.value
        assertEquals(VerificationState.VERIFIED, stored.sources.single().verification)
        assertEquals(2L, stored.contentRevision)
    }
}
