package net.kigawa.recl.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Clock

/** A server-derived reference. Public SourceCitation does not contain the grant epoch. */
data class PromptSourceGrantEpoch(
    val contextId: String,
    val sourceId: String,
    val fence: SourceGrantFence,
) {
    init {
        require(contextId.isNotBlank() && contextId.length <= 128 && !contextId.any(Char::isISOControl))
        require(sourceId.isNotBlank() && sourceId.length <= 128 && !sourceId.any(Char::isISOControl))
    }
}

/**
 * Canonical SHA-256 over the *authoritative* Context/Source -> immutable Grant Epoch mapping.
 * Never derive this from client Scope, GitHub URL or the public SourceCitation.
 *
 * The domain, length-prefix framing and uniqueness rules are versioned independently from
 * PromptDispatchBinding.digest(), which intentionally contains only the public snapshot.
 */
object PromptGrantEpochBinding {
    fun digest(entries: List<PromptSourceGrantEpoch>): String {
        require(entries.isNotEmpty() && entries.size <= 1_000)
        val ordered = entries.sortedWith(compareBy({ it.contextId }, { it.sourceId }))
        require(ordered.zipWithNext().none { (a, b) ->
            a.contextId == b.contextId && a.sourceId == b.sourceId
        }) { "duplicate Context/Source grant binding" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 1_024)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            field("recl.prompt.source-grant-epoch.v1")
            out.writeInt(ordered.size)
            for (entry in ordered) {
                val fence = entry.fence
                field(entry.contextId)
                field(entry.sourceId)
                field(fence.tenantId)
                field(fence.ownerId)
                field(fence.repositoryOwner.lowercase(java.util.Locale.ROOT))
                field(fence.repositoryName.lowercase(java.util.Locale.ROOT))
                out.writeLong(fence.repositoryId)
                out.writeLong(fence.installationId)
                out.writeLong(fence.generation)
                out.writeLong(fence.expiresAtEpochMillis)
            }
        }
        return "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(buffer.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

sealed interface PromptPermitAdmissionDecision {
    data object Allowed : PromptPermitAdmissionDecision
    data object Denied : PromptPermitAdmissionDecision
    data object Unsupported : PromptPermitAdmissionDecision
    data object Unavailable : PromptPermitAdmissionDecision
}

/**
 * Trust boundary for the actual model-wire send-start point. An implementation must check
 * the permit against the authoritative Journal/Grant/Context state; a local clock alone is
 * insufficient to prove issuer authenticity, token uniqueness or revocation.
 */
fun interface TrustedPromptPermitAdmission {
    fun check(permit: PromptSendStartPermit, contexts: List<ContextSnapshot>): PromptPermitAdmissionDecision
}

/**
 * Test/port-composition helper. A trusted sink is invoked only after a server-side gate
 * admits the exact permit, context snapshot set, current time and intended destination.
 * A denied/unavailable gate proves that this wrapper made no network call; it does NOT
 * authorize issuing another Operation ID after a SEND_STARTED journal record was written.
 *
 * This is not, by itself, a distributed revocation barrier: the admission authority and
 * model transport must implement a coordinated send-start/epoch protocol in production.
 */
class AdmissionCheckedPromptSink(
    private val admission: TrustedPromptPermitAdmission?,
    private val downstream: TrustedLeaseBoundPromptSink,
    private val clock: Clock = Clock.systemUTC(),
) : TrustedLeaseBoundPromptSink {
    override fun send(
        permit: PromptSendStartPermit,
        contexts: List<ContextSnapshot>,
    ): TrustedPromptSendResult {
        val now = clock.millis()
        if (admission == null ||
            now < permit.startedAtEpochMillis ||
            now >= permit.expiresAtEpochMillis
        ) return TrustedPromptSendResult.DefinitelyNotSent
        val decision = try { admission.check(permit, contexts) }
            catch (_: Exception) { PromptPermitAdmissionDecision.Unavailable }
        if (decision != PromptPermitAdmissionDecision.Allowed)
            return TrustedPromptSendResult.DefinitelyNotSent
        return downstream.send(permit, contexts)
    }
}
