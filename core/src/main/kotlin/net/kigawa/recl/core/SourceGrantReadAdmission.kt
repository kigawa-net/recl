package net.kigawa.recl.core

/**
 * An internal, authenticated admission decision. It is not a user-configured Scope filter.
 * A production implementation must obtain grant epochs using a linearizable, server-owned
 * authority (and is never allowed to answer Current from a stale local cache).
 */
sealed interface GrantEpochReadResult {
    data object Current : GrantEpochReadResult
    data object RevokedOrChanged : GrantEpochReadResult
    data object Unsupported : GrantEpochReadResult
    data object Unavailable : GrantEpochReadResult
}

/** The grant record has to be checked against the FULL epoch, not a repository name. */
fun interface TrustedGrantEpochReader {
    fun check(principal: Principal, expected: SourceGrantFence): GrantEpochReadResult
}

sealed interface ReadAdmissionDecision {
    data object Allowed : ReadAdmissionDecision
    data object Denied : ReadAdmissionDecision
    data object Unsupported : ReadAdmissionDecision
    data object Unavailable : ReadAdmissionDecision
}

/** Must only receive a server-authenticated Principal and an authoritative ContextAggregate. */
fun interface TrustedPinnedReadAdmission {
    fun check(principal: Principal, context: ContextAggregate, nowEpochMillis: Long): ReadAdmissionDecision
}

/**
 * Phase-1 read admission for source-backed pinned contexts. Current means the tenant,
 * principal and immutable repo/installation/epoch still match authoritative grant state.
 *
 * Fail closed for unknown source types, missing proof, and missing grant capabilities;
 * this is not a general-purpose ACL or a permission to read history via Direct Get.
 */
class SourceGrantReadAdmission(
    private val grants: TrustedGrantEpochReader,
) : TrustedPinnedReadAdmission {
    override fun check(
        principal: Principal,
        context: ContextAggregate,
        nowEpochMillis: Long,
    ): ReadAdmissionDecision {
        if (context.tenantId != principal.tenantId ||
            context.ownerId != principal.id || context.deleted
        ) return ReadAdmissionDecision.Denied
        // A source-free Context has no provenance grant to check. Production requires an
        // independent repo/tenant read authorization, which this narrow port does not supply.
        if (context.sources.isEmpty()) return ReadAdmissionDecision.Unsupported
        for (source in context.sources) {
            if (source.effectiveVerificationAt(nowEpochMillis) != VerificationState.VERIFIED)
                return ReadAdmissionDecision.Denied
            val fence = source.evidence?.sourceGrantFence ?: return ReadAdmissionDecision.Unsupported
            if (!fence.authorizes(context.tenantId, context.ownerId, source, nowEpochMillis))
                return ReadAdmissionDecision.Denied
            when (try { grants.check(principal, fence) }
                catch (_: Exception) { GrantEpochReadResult.Unavailable }) {
                GrantEpochReadResult.Current -> Unit
                GrantEpochReadResult.RevokedOrChanged -> return ReadAdmissionDecision.Denied
                GrantEpochReadResult.Unsupported -> return ReadAdmissionDecision.Unsupported
                GrantEpochReadResult.Unavailable -> return ReadAdmissionDecision.Unavailable
            }
        }
        return ReadAdmissionDecision.Allowed
    }
}
