package net.kigawa.recl.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Versioned, length-prefixed and domain-separated binary encoding for trusted request receipts.
 * Do not interpret caller-provided hashes as evidence of payload identity.
 */
object CanonicalMutationDigest {
    fun create(principal: Principal, requestId: String, command: CreateContextMutation): String =
        Encoder("create", principal, requestId, command.contextId).apply {
            string("kind", command.kind.name)
            string("status", command.status.name)
            string("title", command.title)
            string("summary", command.summary)
            string("content", command.content)
            scopes("scopes", command.scopes)
            boolean("pinned", command.pinned)
        }.sha256()

    fun update(principal: Principal, requestId: String, command: UpdateContextMutation): String =
        Encoder("update", principal, requestId, command.contextId).apply {
            long("expectedContentRevision", command.expectedContentRevision)
            string("patch.title", command.patch.title)
            string("patch.summary", command.patch.summary)
            string("patch.content", command.patch.content)
            string("patch.status", command.patch.status?.name)
            optionalScopes("patch.scopes", command.patch.scopes)
            boolean("patch.pinned", command.patch.pinned)
        }.sha256()

    fun delete(principal: Principal, requestId: String, command: DeleteContextMutation): String =
        Encoder("delete", principal, requestId, command.contextId).apply {
            long("expectedContentRevision", command.expectedContentRevision)
        }.sha256()

    private class Encoder(operation: String, principal: Principal, requestId: String, contextId: String) {
        private val buffer = ByteArrayOutputStream()
        private val data = DataOutputStream(buffer)

        init {
            string("domain", "recl.mutation.v2")
            string("operation", operation)
            string("tenant", principal.tenantId)
            string("principal", principal.id)
            string("requestId", requestId)
            string("contextId", contextId)
        }

        fun string(name: String, value: String?) {
            bytes(name.toByteArray(StandardCharsets.UTF_8))
            data.writeBoolean(value != null)
            if (value != null) bytes(normalize(value).toByteArray(StandardCharsets.UTF_8))
        }

        fun long(name: String, value: Long?) {
            bytes(name.toByteArray(StandardCharsets.UTF_8))
            data.writeBoolean(value != null)
            if (value != null) data.writeLong(value)
        }

        fun boolean(name: String, value: Boolean?) {
            bytes(name.toByteArray(StandardCharsets.UTF_8))
            data.writeBoolean(value != null)
            if (value != null) data.writeBoolean(value)
        }

        fun scopes(name: String, values: Set<String>) = optionalScopes(name, values)

        fun optionalScopes(name: String, values: Set<String>?) {
            bytes(name.toByteArray(StandardCharsets.UTF_8))
            data.writeBoolean(values != null)
            if (values != null) {
                val canonical = values.map(::normalize).toSortedSet()
                data.writeInt(canonical.size)
                for (value in canonical) bytes(value.toByteArray(StandardCharsets.UTF_8))
            }
        }

        fun sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(buffer.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

        private fun bytes(value: ByteArray) {
            data.writeInt(value.size)
            data.write(value)
        }
    }
}

internal fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
