package net.kigawa.recl.core

interface StorageProvider {
    val id: String
    fun open(settings: Map<String, String>): OpenedStorage
}

class OpenedStorage(
    val storage: ContextStorage,
    val mode: String,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() = onClose()
}
