package net.kigawa.recl.github

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.recl.core.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubGrantFenceTest {
    private val millis = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    private val repo = GitHubRepository("kigawa-net", "recl")
    private val scope = SourceVerificationScope("tenant-a", "context-1", "owner", 1)
    private val sha = "a".repeat(40)
    private val treeSha = "b".repeat(40)
    private val target = GitHubSourceTarget(repo, SourceType.GITHUB_COMMIT, sha)
    private val bytes = """{"sha":"$sha","commit":{"tree":{"sha":"$treeSha"},"message":"example"}}""".toByteArray()
    private val src = SourceSubmission("ref-1", SourceType.GITHUB_COMMIT,
        "https://github.com/kigawa-net/recl/commit/$sha",
        contentDigest = GitHubEvidenceDigest.fromResponse(target, bytes)!!.contentDigest,
        trust = SourceTrust.PRIMARY_SOURCE).unverified()

    private var grant = GitHubInstallationGrant("tenant-a", "owner", repo,
        installationId = 1001, generation = 1, expiresAtEpochMillis = millis + 3_600_000,
        repositoryId = 9001)

    private fun access(): AuthoritativeGitHubAppAccess = AuthoritativeGitHubAppAccess(
        GitHubInstallationGrantLedger { request, requestedRepo ->
            if (request.tenantId == scope.tenantId && request.ownerId == scope.ownerId &&
                requestedRepo == repo
            ) grant else null
        },
        GitHubInstallationTokenIssuer { current ->
            GitHubInstallationToken("fake-installation-token",
                minOf(current.expiresAtEpochMillis, millis + 600_000))
        },
        clock,
    )

    private fun attest(read: GitHubDocumentReader): SourceAttestationResult {
        val access = access()
        return GitHubSourceAttestor(access, access, read,
            "recl-source-verifier", clock).attest(scope, src)
    }

    @Test fun `authoritative grant binds stable GitHub repo and installation epoch to evidence`() {
        val result = attest(GitHubDocumentReader { _, _ -> GitHubReadResult.Success(bytes) })
        assertTrue(result is SourceAttestationResult.Verified)
        val fence = (result as SourceAttestationResult.Verified).evidence.sourceGrantFence!!
        assertEquals(9001L, fence.repositoryId)
        assertEquals(1001L, fence.installationId)
        assertEquals(1L, fence.generation)
        assertEquals(scope.tenantId, fence.tenantId)
        assertEquals(scope.ownerId, fence.ownerId)
    }

    @Test fun `revoke grant while awaiting GitHub must fail closed`() {
        val result = attest(GitHubDocumentReader { _, _ ->
            grant = grant.copy(revoked = true)
            GitHubReadResult.Success(bytes)
        })
        assertEquals(SourceAttestationResult.Unavailable, result)
    }

    @Test fun `changed installation or generation during API request never creates old proof`() {
        val result = attest(GitHubDocumentReader { _, _ ->
            grant = grant.copy(installationId = 1002, generation = 2)
            GitHubReadResult.Success(bytes)
        })
        assertEquals(SourceAttestationResult.Unavailable, result)
    }

    @Test fun `no immutable repository id cannot yield fence or VERIFIED evidence`() {
        grant = grant.copy(repositoryId = 0)
        val result = attest(GitHubDocumentReader { _, _ -> GitHubReadResult.Success(bytes) })
        assertEquals(SourceAttestationResult.Unavailable, result)
    }

    @Test fun `fenced grant cannot be paired with an unrelated credential authority`() {
        val authoritative = access()
        var contacted = false
        val result = GitHubSourceAttestor(
            authoritative,
            GitHubInstallationCredentials { _, _ ->
                GitHubInstallationToken("unrelated-installation", millis + 600_000)
            },
            GitHubDocumentReader { _, _ ->
                contacted = true
                GitHubReadResult.Success(bytes)
            },
            "recl-source-verifier", clock,
        ).attest(scope, src)
        assertEquals(SourceAttestationResult.Unavailable, result)
        assertTrue(!contacted)
    }
}
