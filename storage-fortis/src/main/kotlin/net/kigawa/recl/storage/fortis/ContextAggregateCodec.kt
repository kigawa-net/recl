package net.kigawa.recl.storage.fortis

import net.kigawa.recl.core.ContextAggregate
import net.kigawa.recl.core.ContextKind
import net.kigawa.recl.core.ContextSource
import net.kigawa.recl.core.ContextStatus
import net.kigawa.recl.core.IndexAction
import net.kigawa.recl.core.OperationReceipt
import net.kigawa.recl.core.Sensitivity
import net.kigawa.recl.core.SourceGrantFence
import net.kigawa.recl.core.SourceReferencePolicy
import net.kigawa.recl.core.SourceTrust
import net.kigawa.recl.core.SourceType
import net.kigawa.recl.core.VerificationEvidence
import net.kigawa.recl.core.VerificationState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** A stored value that cannot be decoded into a valid aggregate. Adapters must fail closed on it. */
class CorruptContextRecord(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Deterministic, schema-versioned binary encoding of [ContextAggregate].
 *
 * Layout: magic "RCLA", int schemaVersion, then fields in declaration order. Strings are
 * length-prefixed UTF-8, optional values carry a presence byte, enums are encoded by name, and
 * the scope set is written sorted. Unknown versions, trailing bytes, and any value that fails the
 * domain constructors' validation are rejected with [CorruptContextRecord].
 */
object ContextAggregateCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_ENCODED_BYTES = 1 shl 20

    private val magic = "RCLA".toByteArray(Charsets.US_ASCII)
    private const val MAX_STRING_BYTES = MAX_ENCODED_BYTES
    private const val MAX_SCOPES = 1024

    fun encode(value: ContextAggregate): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.write(magic)
            out.writeInt(SCHEMA_VERSION)
            out.str(value.tenantId)
            out.str(value.id)
            out.str(value.ownerId)
            out.enumName(value.kind)
            out.enumName(value.status)
            out.str(value.title)
            out.str(value.summary)
            out.str(value.content)
            out.writeInt(value.scopes.size)
            value.scopes.sorted().forEach { out.str(it) }
            out.writeBoolean(value.pinned)
            out.enumName(value.sensitivity)
            out.optLong(value.validFromEpochMillis)
            out.optLong(value.validUntilEpochMillis)
            out.writeInt(value.sources.size)
            value.sources.forEach { out.source(it) }
            out.writeLong(value.contentRevision)
            out.writeLong(value.indexedContentRevision)
            out.enumName(value.indexAction)
            out.writeBoolean(value.deleted)
            out.writeInt(value.operationReceipts.size)
            value.operationReceipts.forEach {
                out.str(it.requestId)
                out.str(it.payloadDigest)
                out.writeLong(it.resultingContentRevision)
            }
        }
        val encoded = buffer.toByteArray()
        require(encoded.size <= MAX_ENCODED_BYTES) { "encoded Context exceeds $MAX_ENCODED_BYTES bytes" }
        return encoded
    }

    fun decode(bytes: ByteArray): ContextAggregate {
        if (bytes.size > MAX_ENCODED_BYTES) throw CorruptContextRecord("record exceeds $MAX_ENCODED_BYTES bytes")
        val input = ByteArrayInputStream(bytes)
        val decoded = try {
            DataInputStream(input).use { inp -> inp.readAggregate() }
        } catch (e: CorruptContextRecord) {
            throw e
        } catch (e: Exception) {
            throw CorruptContextRecord("malformed Context record", e)
        }
        if (input.available() != 0) throw CorruptContextRecord("trailing bytes after Context record")
        return decoded
    }

    private fun DataInputStream.readAggregate(): ContextAggregate {
        val header = ByteArray(magic.size).also { readFully(it) }
        if (!header.contentEquals(magic)) throw CorruptContextRecord("bad magic")
        val version = readInt()
        if (version != SCHEMA_VERSION) throw CorruptContextRecord("unsupported schema version $version")
        val tenantId = str()
        val id = str()
        val ownerId = str()
        val kind = enum<ContextKind>()
        val status = enum<ContextStatus>()
        val title = str()
        val summary = str()
        val content = str()
        val scopeCount = count(MAX_SCOPES)
        val scopes = LinkedHashSet<String>()
        repeat(scopeCount) { scopes.add(str()) }
        val pinned = readBoolean()
        val sensitivity = enum<Sensitivity>()
        val validFrom = optLong()
        val validUntil = optLong()
        val sourceCount = count(SourceReferencePolicy.MAX_PER_CONTEXT)
        val sources = List(sourceCount) { readSource() }
        val contentRevision = readLong()
        val indexedContentRevision = readLong()
        val indexAction = enum<IndexAction>()
        val deleted = readBoolean()
        val receiptCount = count(ContextAggregate.MAX_RECEIPTS)
        val receipts = List(receiptCount) {
            OperationReceipt(str(), str(), readLong())
        }
        return ContextAggregate(
            tenantId = tenantId, id = id, ownerId = ownerId, kind = kind, status = status,
            title = title, summary = summary, content = content, scopes = scopes,
            pinned = pinned, sensitivity = sensitivity,
            validFromEpochMillis = validFrom, validUntilEpochMillis = validUntil,
            sources = sources, contentRevision = contentRevision,
            indexedContentRevision = indexedContentRevision, indexAction = indexAction,
            deleted = deleted, operationReceipts = receipts,
        )
    }

    private fun DataOutputStream.source(s: ContextSource) {
        str(s.sourceId)
        enumName(s.type)
        str(s.uri)
        optStr(s.locator)
        optLong(s.retrievedAtEpochMillis)
        optStr(s.contentDigest)
        enumName(s.trust)
        enumName(s.verification)
        val evidence = s.evidence
        writeBoolean(evidence != null)
        if (evidence != null) {
            str(evidence.tenantId)
            str(evidence.contextId)
            str(evidence.sourceId)
            str(evidence.canonicalUri)
            str(evidence.contentDigest)
            writeLong(evidence.boundContentRevision)
            str(evidence.providerIdentity)
            str(evidence.audience)
            str(evidence.attestationId)
            writeLong(evidence.verifiedAtEpochMillis)
            writeLong(evidence.expiresAtEpochMillis)
            val fence = evidence.sourceGrantFence
            writeBoolean(fence != null)
            if (fence != null) {
                str(fence.tenantId)
                str(fence.ownerId)
                str(fence.repositoryOwner)
                str(fence.repositoryName)
                writeLong(fence.repositoryId)
                writeLong(fence.installationId)
                writeLong(fence.generation)
                writeLong(fence.expiresAtEpochMillis)
            }
        }
    }

    private fun DataInputStream.readSource(): ContextSource {
        val sourceId = str()
        val type = enum<SourceType>()
        val uri = str()
        val locator = optStr()
        val retrievedAt = optLong()
        val contentDigest = optStr()
        val trust = enum<SourceTrust>()
        val verification = enum<VerificationState>()
        val evidence = if (readBoolean()) {
            val tenantId = str()
            val contextId = str()
            val evidenceSourceId = str()
            val canonicalUri = str()
            val evidenceDigest = str()
            val boundRevision = readLong()
            val providerIdentity = str()
            val audience = str()
            val attestationId = str()
            val verifiedAt = readLong()
            val expiresAt = readLong()
            val fence = if (readBoolean()) {
                SourceGrantFence(
                    tenantId = str(), ownerId = str(), repositoryOwner = str(), repositoryName = str(),
                    repositoryId = readLong(), installationId = readLong(), generation = readLong(),
                    expiresAtEpochMillis = readLong(),
                )
            } else null
            VerificationEvidence(
                tenantId = tenantId, contextId = contextId, sourceId = evidenceSourceId,
                canonicalUri = canonicalUri, contentDigest = evidenceDigest,
                boundContentRevision = boundRevision, providerIdentity = providerIdentity,
                audience = audience, attestationId = attestationId,
                verifiedAtEpochMillis = verifiedAt, expiresAtEpochMillis = expiresAt,
                sourceGrantFence = fence,
            )
        } else null
        return ContextSource(
            sourceId = sourceId, type = type, uri = uri, locator = locator,
            retrievedAtEpochMillis = retrievedAt, contentDigest = contentDigest, trust = trust,
            verification = verification, evidence = evidence,
        )
    }

    private fun DataOutputStream.str(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.optStr(value: String?) {
        writeBoolean(value != null)
        if (value != null) str(value)
    }

    private fun DataOutputStream.optLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.enumName(value: Enum<*>) = str(value.name)

    private fun DataInputStream.str(): String {
        val size = readInt()
        if (size !in 0..MAX_STRING_BYTES) throw CorruptContextRecord("string length out of range")
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.optStr(): String? = if (readBoolean()) str() else null

    private fun DataInputStream.optLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataInputStream.count(max: Int): Int {
        val n = readInt()
        if (n !in 0..max) throw CorruptContextRecord("collection size out of range")
        return n
    }

    private inline fun <reified T : Enum<T>> DataInputStream.enum(): T = enumValueOf<T>(str())
}
