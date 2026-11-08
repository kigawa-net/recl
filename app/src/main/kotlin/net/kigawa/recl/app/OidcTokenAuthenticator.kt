package net.kigawa.recl.app

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

interface McpPrincipal { val userId: String }

interface McpRequestAuthenticator {
    /** Authorization ヘッダの credential 部分。検証成功時のみ Principal を返す。 */
    fun authenticate(credential: String): McpPrincipal?
}

private data class AuthenticatedMcpPrincipal(override val userId: String) : McpPrincipal

class TokenMcpAuthenticator(token: String, private val userId: String) : McpRequestAuthenticator {
    private val expected = token.toByteArray(Charsets.UTF_8)
    override fun authenticate(credential: String): McpPrincipal? =
        if (MessageDigest.isEqual(expected, credential.toByteArray(Charsets.UTF_8))) AuthenticatedMcpPrincipal(userId) else null
}

/** Verifies signatures against configured JWKS only; token-supplied URLs are never followed. */
class OidcTokenAuthenticator(
    private val issuer: String,
    private val jwksUri: String,
    val tenantId: String,
    private val audience: String? = null,
    private val requiredScope: String? = null,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val clock: Clock = Clock.systemUTC(),
) : McpRequestAuthenticator {
    private var cached: JWKSet? = null
    private var expiresAt = Instant.MIN
    private var lastFetch: Instant? = null
    private var lastForcedFetch: Instant? = null

    override fun authenticate(credential: String): McpPrincipal? = try {
        verify(credential)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        // Fail closed without logging credentials, claims, keys, or exception messages.
        null
    }

    private fun verify(credential: String): McpPrincipal? {
        val jwt = SignedJWT.parse(credential)
        val algorithm = jwt.header.algorithm
        if (algorithm !in ALGORITHMS || !jwt.header.isBase64URLEncodePayload) return null
        val kid = jwt.header.keyID?.takeIf { it.isNotBlank() } ?: return null
        val keys = keys(kid) ?: return null
        val verified = keys.any { key ->
            if (key.keyUse != null && key.keyUse != KeyUse.SIGNATURE ||
                key.keyOperations != null && KeyOperation.VERIFY !in key.keyOperations ||
                key.algorithm != null && key.algorithm != algorithm) false
            else when (key) {
                is RSAKey -> algorithm in RSA_ALGORITHMS && jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))
                is ECKey -> algorithm in EC_ALGORITHMS && jwt.verify(ECDSAVerifier(key.toECPublicKey()))
                else -> false
            }
        }
        if (!verified) return null
        val claims = jwt.jwtClaimsSet
        val now = clock.instant()
        if (claims.issuer != issuer) return null
        if (claims.expirationTime?.toInstant()?.isAfter(now) != true) return null
        if (claims.notBeforeTime?.toInstant()?.isAfter(now) == true) return null
        if (claims.issueTime?.toInstant()?.isAfter(now.plusSeconds(60)) == true) return null
        if (audience != null && audience !in claims.audience.orEmpty()) return null
        if (requiredScope != null && requiredScope !in claims.getStringClaim("scope").orEmpty().split(' ')) return null
        val userId = claims.subject?.takeIf { it.isNotBlank() }
            ?: claims.getStringClaim("preferred_username")?.takeIf { it.isNotBlank() } ?: return null
        return AuthenticatedMcpPrincipal(userId)
    }

    /** Serializes refreshes, including failed attempts, to bound network traffic from unknown kids. */
    @Synchronized
    private fun keys(kid: String): List<JWK>? {
        val now = clock.instant()
        if (cached == null || !now.isBefore(expiresAt)) {
            if (lastFetch?.let { now.isBefore(it.plusSeconds(30)) } == true) return null
            fetch(now)
        }
        var matches = cached?.keys?.filter { it.keyID == kid }.orEmpty()
        if (matches.isEmpty()) {
            if (lastForcedFetch?.let { now.isBefore(it.plusSeconds(30)) } == true) return null
            lastForcedFetch = now
            fetch(now)
            matches = cached?.keys?.filter { it.keyID == kid }.orEmpty()
        }
        return matches.takeIf { it.isNotEmpty() }
    }

    private fun fetch(now: Instant) {
        lastFetch = now
        // An unsuccessful refresh invalidates the previous set; stale keys never grant access.
        cached = null
        expiresAt = Instant.MIN
        val request = HttpRequest.newBuilder(URI(jwksUri)).timeout(Duration.ofSeconds(5)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200)
        val set = JWKSet.parse(response.body())
        check(set.keys.isNotEmpty())
        cached = set
        expiresAt = now.plusSeconds(300)
    }

    companion object {
        private val RSA_ALGORITHMS = setOf(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512)
        private val EC_ALGORITHMS = setOf(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512)
        private val ALGORITHMS = RSA_ALGORITHMS + EC_ALGORITHMS
    }
}
