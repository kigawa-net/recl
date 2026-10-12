package net.kigawa.recl.core

import java.time.Clock

/**
 * Internal verifier receipt metadata, not a cryptographic signature. A production adapter must
 * validate the provider's credentials and source bytes BEFORE returning this object.
 * IDs and digest are non-secret, fixed-format labels; never place tokens or signed URLs here.
 */
data class VerificationEvidence(
    val tenantId: String,
    val contextId: String,
    val sourceId: String,
    val canonicalUri: String,
    val contentDigest: String,
    val boundContentRevision: Long,
    val providerIdentity: String,
    val audience: String,
    val attestationId: String,
    val verifiedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        val safeId = Regex("[A-Za-z0-9._:-]{1,128}")
        require(tenantId.isNotBlank() && contextId.isNotBlank() && sourceId.isNotBlank())
        require(safeId.matches(providerIdentity) && safeId.matches(audience) && safeId.matches(attestationId))
        require(Regex("sha256:[0-9a-f]{64}").matches(contentDigest))
        require(boundContentRevision >= 1)
        require(verifiedAtEpochMillis >= 0 && expiresAtEpochMillis > verifiedAtEpochMillis)
    }
}

data class SourceVerificationScope(
    val tenantId: String,
    val contextId: String,
    val ownerId: String,
    val contentRevision: Long,
)

/** This interface MUST be implemented by a trusted, server-side provider, never by client DTOs. */
fun interface TrustedSourceAttestor {
    fun attest(scope: SourceVerificationScope, source: ContextSource): SourceAttestationResult
}

sealed interface SourceAttestationResult {
    data class Verified(val evidence: VerificationEvidence) : SourceAttestationResult
    data object Stale : SourceAttestationResult
    data object Invalid : SourceAttestationResult
    data object Unavailable : SourceAttestationResult
    data object OutcomeUnknown : SourceAttestationResult
}

sealed interface SourceVerificationResult {
    data class Applied(val contentRevision: Long) : SourceVerificationResult
    data class Conflict(val actualContentRevision: Long?) : SourceVerificationResult
    data object NotFound : SourceVerificationResult
    data object Unavailable : SourceVerificationResult
    data object OutcomeUnknown : SourceVerificationResult
    data object RejectedEvidence : SourceVerificationResult
}

/**
 * INTERNAL trusted worker operation. No HTTP/MCP route should expose this service.
 *
 * Read -> attestation -> re-read -> conditional CAS. Only a physical bookkeeping conflict
 * (e.g., index ACK) permits a retry. Any semantic update invalidates the earlier proof.
 * There is no durable request receipt: a lost CAS response is always OutcomeUnknown.
 */
class SourceVerificationService(
    private val storage: ContextStorage,
    private val attestor: TrustedSourceAttestor,
    private val expectedAudience: String,
    private val clock: Clock = Clock.systemUTC(),
    private val maxEvidenceMillis: Long = 24L * 60 * 60 * 1000,
    private val maxCasAttempts: Int = 8,
) {
    init {
        require(expectedAudience.isNotBlank() && expectedAudience.length <= 128)
        require(maxEvidenceMillis in 1..(7L * 24 * 60 * 60 * 1000))
        require(maxCasAttempts in 1..100)
    }

    fun verify(
        tenantId: String,
        contextId: String,
        sourceId: String,
        expectedContentRevision: Long,
    ): SourceVerificationResult {
        if (tenantId.isBlank() || contextId.isBlank() || sourceId.isBlank() ||
            expectedContentRevision < 1
        ) return SourceVerificationResult.RejectedEvidence

        val start = when (val r = storage.get(tenantId, contextId)) {
            is ReadResult.Found -> r.stored.value
            ReadResult.NotFound -> return SourceVerificationResult.NotFound
            ReadResult.Unavailable -> return SourceVerificationResult.Unavailable
        }
        if (start.tenantId != tenantId || start.deleted) return SourceVerificationResult.NotFound
        if (start.contentRevision != expectedContentRevision)
            return SourceVerificationResult.Conflict(start.contentRevision)
        val source = start.sources.firstOrNull { it.sourceId == sourceId }
            ?: return SourceVerificationResult.NotFound
        val result = try {
            attestor.attest(SourceVerificationScope(
                start.tenantId, start.id, start.ownerId, start.contentRevision
            ), source)
        } catch (_: Exception) {
            return SourceVerificationResult.Unavailable
        }

        val now = clock.millis()
        val targetEvidence: VerificationEvidence?
        val targetState: VerificationState
        when (result) {
            is SourceAttestationResult.Verified -> {
                val e = result.evidence
                // Identity and content binding are checked independently of the adapter.
                if (e.tenantId != tenantId ||
                    e.contextId != contextId ||
                    e.sourceId != sourceId ||
                    e.boundContentRevision != expectedContentRevision ||
                    e.canonicalUri != source.uri ||
                    source.contentDigest == null ||
                    e.contentDigest != source.contentDigest ||
                    e.audience != expectedAudience ||
                    e.verifiedAtEpochMillis > now ||
                    e.expiresAtEpochMillis <= now ||
                    e.expiresAtEpochMillis - e.verifiedAtEpochMillis > maxEvidenceMillis
                ) return SourceVerificationResult.RejectedEvidence
                targetEvidence = e
                targetState = VerificationState.VERIFIED
            }
            SourceAttestationResult.Stale -> {
                targetState = VerificationState.STALE
                targetEvidence = null
            }
            SourceAttestationResult.Invalid -> {
                targetState = VerificationState.INVALID
                targetEvidence = null
            }
            SourceAttestationResult.Unavailable -> return SourceVerificationResult.Unavailable
            SourceAttestationResult.OutcomeUnknown -> return SourceVerificationResult.OutcomeUnknown
        }

        repeat(maxCasAttempts) {
            val current = when (val r = storage.get(tenantId, contextId)) {
                is ReadResult.Found -> r.stored
                ReadResult.NotFound -> return SourceVerificationResult.NotFound
                ReadResult.Unavailable -> return SourceVerificationResult.Unavailable
            }
            if (current.value.tenantId != tenantId || current.value.deleted)
                return SourceVerificationResult.NotFound
            if (current.value.contentRevision != expectedContentRevision)
                return SourceVerificationResult.Conflict(current.value.contentRevision)
            val matched = current.value.sources.firstOrNull { it.sourceId == sourceId }
                ?: return SourceVerificationResult.NotFound
            if (matched != source) return SourceVerificationResult.Conflict(current.value.contentRevision)

            val nextRevision = current.value.contentRevision + 1
            val updated = try {
                current.value.copy(
                    sources = current.value.sources.map {
                        if (it.sourceId == sourceId)
                            it.copy(verification = targetState, evidence = targetEvidence)
                        else it
                    },
                    contentRevision = nextRevision,
                    indexAction = IndexAction.UPSERT,
                )
            } catch (_: IllegalArgumentException) {
                return SourceVerificationResult.RejectedEvidence
            }
            when (storage.compareAndSwap(tenantId, contextId, current.storageRevision, updated)) {
                is WriteResult.Applied -> return SourceVerificationResult.Applied(nextRevision)
                WriteResult.Conflict -> Unit
                WriteResult.OutcomeUnknown -> return SourceVerificationResult.OutcomeUnknown
                WriteResult.Unavailable -> return SourceVerificationResult.Unavailable
            }
        }
        return SourceVerificationResult.OutcomeUnknown
    }
}
