package net.kigawa.recl.core

import java.util.Locale

/** IDs entering a user-facing Context endpoint must be UUIDv7.
 * Keep all entrypoints on this shared validator rather than trusting arbitrary strings.
 */
object ContextIds {
    private val uuidV7 = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )

    fun isUuidV7(value: String): Boolean = uuidV7.matches(value)

    fun canonicalUuidV7(value: String): String? =
        if (isUuidV7(value)) value.lowercase(Locale.ROOT) else null
}
