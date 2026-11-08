package net.kigawa.recl.app

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OidcTokenAuthenticatorTest {
    @Test
    fun `valid signature and claims authenticate subject`() = withJwks { fixture ->
        assertEquals("user-1", fixture.authenticator().authenticate(token())?.userId)
        assertEquals(1, fixture.requests.get())
    }

    @Test
    fun `expired or missing expiration is rejected`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        assertNull(auth.authenticate(token { expirationTime(Date.from(NOW.minusSeconds(1))) }))
        assertNull(auth.authenticate(token { expirationTime(null) }))
        assertNull(auth.authenticate(token { expirationTime(Date.from(NOW)) }))
    }

    @Test
    fun `issuer must match exactly`() = withJwks { fixture ->
        assertNull(fixture.authenticator().authenticate(token { issuer("https://other.example/realm") }))
        assertNull(fixture.authenticator().authenticate(token { issuer("$ISSUER/") }))
    }

    @Test
    fun `tampered signature is rejected`() = withJwks { fixture ->
        val signed = token()
        val parts = signed.split('.').toMutableList()
        parts[2] = (if (parts[2][0] == 'A') "B" else "A") + parts[2].drop(1)
        assertNull(fixture.authenticator().authenticate(parts.joinToString(".")))
    }

    @Test
    fun `audience must contain configured audience`() = withJwks { fixture ->
        val auth = fixture.authenticator(audience = "recl")
        assertNull(auth.authenticate(token { audience("other-client") }))
        assertNull(auth.authenticate(token()))
        assertNotNull(auth.authenticate(token { audience(listOf("other-client", "recl")) }))
    }

    @Test
    fun `scope requires exact space separated entry`() = withJwks { fixture ->
        val auth = fixture.authenticator(requiredScope = "recl:read")
        assertNull(auth.authenticate(token()))
        assertNull(auth.authenticate(token { claim("scope", "openid recl:reader") }))
        assertNotNull(auth.authenticate(token { claim("scope", "openid recl:read profile") }))
    }

    @Test
    fun `unknown kid refreshes jwks and authenticates rotated key`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        assertNotNull(auth.authenticate(token()))
        fixture.body = jwks(ROTATED_KEY)
        assertEquals("user-1", auth.authenticate(token(ROTATED_KEY))?.userId)
        assertEquals(2, fixture.requests.get())
        assertNotNull(auth.authenticate(token(ROTATED_KEY)))
        assertEquals(2, fixture.requests.get())
    }

    @Test
    fun `unknown kid refreshes are limited to once per thirty seconds`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        assertNotNull(auth.authenticate(token()))
        assertNull(auth.authenticate(token(ROTATED_KEY)))
        assertEquals(2, fixture.requests.get())
        fixture.body = jwks(ROTATED_KEY)
        repeat(3) { assertNull(auth.authenticate(token(ROTATED_KEY))) }
        assertEquals(2, fixture.requests.get())
        fixture.clock.now = NOW.plusSeconds(30)
        assertNotNull(auth.authenticate(token(ROTATED_KEY)))
        assertEquals(3, fixture.requests.get())
    }

    @Test
    fun `jwks errors and invalid documents fail closed`() = withJwks { fixture ->
        fixture.status = 500
        assertNull(fixture.authenticator().authenticate(token()))
        fixture.status = 200
        for (body in listOf("not json", "{}", "{\"keys\":[]}")) {
            fixture.body = body
            assertNull(fixture.authenticator().authenticate(token()))
        }
    }

    @Test
    fun `expired cache is not used after refresh failure`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        val longLived = token { expirationTime(Date.from(NOW.plusSeconds(3600))) }
        assertNotNull(auth.authenticate(longLived))
        fixture.clock.now = NOW.plusSeconds(301)
        fixture.status = 500
        assertNull(auth.authenticate(longLived))
    }

    @Test
    fun `none algorithm and malformed jwt are rejected without jwks access`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        assertNull(auth.authenticate(PlainJWT(claims().build()).serialize()))
        assertNull(auth.authenticate("not-a-jwt"))
        assertEquals(0, fixture.requests.get())
    }

    @Test
    fun `future nbf and iat beyond sixty seconds are rejected`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        assertNull(auth.authenticate(token { notBeforeTime(Date.from(NOW.plusSeconds(1))) }))
        assertNull(auth.authenticate(token { issueTime(Date.from(NOW.plusSeconds(61))) }))
        assertNotNull(auth.authenticate(token {
            notBeforeTime(Date.from(NOW))
            issueTime(Date.from(NOW.plusSeconds(60)))
        }))
    }

    @Test
    fun `preferred username is fallback and absent identity is rejected`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        assertEquals("fallback", auth.authenticate(token {
            subject("")
            claim("preferred_username", "fallback")
        })?.userId)
        assertEquals("user-1", auth.authenticate(token { claim("preferred_username", "fallback") })?.userId)
        assertNull(auth.authenticate(token { subject("") }))
        assertNull(auth.authenticate(token { subject(null) }))
    }

    @Test
    fun `all supported rsa algorithms are accepted`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        for (algorithm in listOf(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512)) {
            assertNotNull(auth.authenticate(token(algorithm = algorithm)), algorithm.name)
        }
    }

    @Test
    fun `all supported elliptic curve algorithms are accepted`() = withJwks { fixture ->
        for ((curve, algorithm) in listOf(Curve.P_256 to JWSAlgorithm.ES256,
            Curve.P_384 to JWSAlgorithm.ES384, Curve.P_521 to JWSAlgorithm.ES512)) {
            val key = ECKeyGenerator(curve).keyID(algorithm.name).generate()
            fixture.body = JWKSet(key.toPublicJWK()).toString()
            val jwt = SignedJWT(JWSHeader.Builder(algorithm).keyID(key.keyID).build(), claims().build())
                .apply { sign(ECDSASigner(key)) }.serialize()
            assertNotNull(fixture.authenticator().authenticate(jwt), algorithm.name)
        }
    }

    @Test
    fun `concurrent requests share a single jwks fetch`() = withJwks { fixture ->
        val auth = fixture.authenticator()
        val jwt = token()
        Executors.newFixedThreadPool(8).use { executor ->
            val results = (1..24).map { executor.submit<McpPrincipal?> { auth.authenticate(jwt) } }
            results.forEach { assertEquals("user-1", it.get()?.userId) }
        }
        assertEquals(1, fixture.requests.get())
    }

    private class MutableClock(var now: Instant = NOW) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private class Fixture : AutoCloseable {
        val clock = MutableClock()
        val requests = AtomicInteger()
        @Volatile var body: String = jwks(KEY)
        @Volatile var status: Int = 200
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                exchange.use {
                    requests.incrementAndGet()
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }
            start()
        }

        fun authenticator(audience: String? = null, requiredScope: String? = null) = OidcTokenAuthenticator(
            issuer = ISSUER,
            jwksUri = "http://127.0.0.1:${server.address.port}/jwks",
            tenantId = "tenant",
            audience = audience,
            requiredScope = requiredScope,
            clock = clock,
        )

        override fun close() = server.stop(0)
    }

    private fun withJwks(block: (Fixture) -> Unit) = Fixture().use(block)

    companion object {
        private const val ISSUER = "https://user.kigawa.net/realms/kigawa-net"
        private val NOW = Instant.parse("2026-11-08T00:00:00Z")
        private val KEY by lazy { RSAKeyGenerator(2048).keyID("original").generate() }
        private val ROTATED_KEY by lazy { RSAKeyGenerator(2048).keyID("rotated").generate() }

        private fun jwks(key: RSAKey): String = JWKSet(key.toPublicJWK()).toString()

        private fun claims(): JWTClaimsSet.Builder = JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject("user-1")
            .expirationTime(Date.from(NOW.plusSeconds(300)))
            .issueTime(Date.from(NOW.minusSeconds(1)))

        private fun token(
            key: RSAKey = KEY,
            algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
            configure: JWTClaimsSet.Builder.() -> Unit = {},
        ): String = SignedJWT(
            JWSHeader.Builder(algorithm).keyID(key.keyID).build(),
            claims().apply(configure).build(),
        ).apply { sign(RSASSASigner(key)) }.serialize()
    }
}
