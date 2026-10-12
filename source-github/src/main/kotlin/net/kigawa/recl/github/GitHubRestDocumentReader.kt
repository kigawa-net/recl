package net.kigawa.recl.github

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

data class BoundedGitHubHttpResponse(val status: Int, val body: ByteArray)

/**
 * Injectable HTTP boundary for deterministic tests. A test HTTP engine returning a successful
 * fixture is NOT evidence of a real GitHub App installation or repository grant.
 */
fun interface GitHubHttpEngine {
    fun execute(request: HttpRequest, maxBytes: Int): BoundedGitHubHttpResponse
}

/**
 * Real JDK transport: redirects disabled, finite timeouts, byte-capped streamed responses.
 * The request origin is always constructed by GitHubRestDocumentReader as api.github.com.
 */
class JdkGitHubHttpEngine(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : GitHubHttpEngine {
    override fun execute(request: HttpRequest, maxBytes: Int): BoundedGitHubHttpResponse {
        require(maxBytes in 1..(512 * 1024))
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return response.body().use { stream ->
            val limited = stream.readNBytes(maxBytes + 1)
            if (limited.size > maxBytes) throw GitHubResponseTooLarge()
            BoundedGitHubHttpResponse(response.statusCode(), limited)
        }
    }
}

private class GitHubResponseTooLarge : IOException()

/**
 * Fixed-origin GitHub REST reader. Cannot be configured to fetch arbitrary Source URIs,
 * Cloud metadata, local IPs or presigned artifact URLs. Never follows 3xx redirects.
 */
class GitHubRestDocumentReader(
    private val engine: GitHubHttpEngine = JdkGitHubHttpEngine(),
    private val maxResponseBytes: Int = 512 * 1024,
) : GitHubDocumentReader {
    init { require(maxResponseBytes in 1..(512 * 1024)) }

    override fun read(target: GitHubSourceTarget, accessToken: GitHubInstallationToken): GitHubReadResult {
        // GitHubSourceTarget construction validates every path segment.
        val resource = when (target.type) {
            net.kigawa.recl.core.SourceType.GITHUB_COMMIT -> "commits"
            net.kigawa.recl.core.SourceType.GITHUB_ISSUE -> "issues"
            net.kigawa.recl.core.SourceType.GITHUB_PR -> "pulls"
            else -> return GitHubReadResult.Unavailable
        }
        val endpoint = "https://api.github.com/repos/" +
            target.repository.owner + "/" + target.repository.name + "/" + resource + "/" + target.identity
        val request = try {
            HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + accessToken.value)
                .header("Cache-Control", "no-store")
                .GET().build()
        } catch (_: IllegalArgumentException) { return GitHubReadResult.Unavailable }
        val response = try {
            engine.execute(request, maxResponseBytes)
        } catch (_: HttpTimeoutException) {
            return GitHubReadResult.OutcomeUnknown
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return GitHubReadResult.OutcomeUnknown
        } catch (_: IOException) {
            return GitHubReadResult.Unavailable
        } catch (_: Exception) {
            return GitHubReadResult.Unavailable
        }
        if (response.body.size > maxResponseBytes) return GitHubReadResult.Unavailable
        return when (response.status) {
            200 -> GitHubReadResult.Success(response.body)
            401, 403, 404 -> GitHubReadResult.ForbiddenOrMissing
            else -> GitHubReadResult.Unavailable  // 3xx, rate limit, bad JSON, 5xx: fail closed.
        }
    }
}
