package net.kigawa.recl.storage.fortis.inproc

import net.kigawa.recl.core.OpenedStorage
import net.kigawa.recl.core.StorageProvider
import net.kigawa.recl.storage.fortis.FortisContextStorage
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class FortisStorageProvider : StorageProvider {
    override val id = "fortis"

    override fun open(settings: Map<String, String>): OpenedStorage {
        val dir = requireNotNull(settings["dir"]?.takeIf { it.isNotBlank() }) { "dir is required" }
        val snapshotEvery = settings["snapshotEvery"]?.let {
            requireNotNull(it.toIntOrNull()?.takeIf { seconds -> seconds > 0 }) {
                "snapshotEvery must be a positive number of seconds"
            }
        } ?: 30
        val node = FortisInprocNode.open(Path.of(dir), settings["nodeId"] ?: "recl", snapshotEvery.seconds)
        return OpenedStorage(FortisContextStorage(node.port), "fortis (in-process, single-node, durable)", node::close)
    }
}
