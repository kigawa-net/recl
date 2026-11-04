package net.kigawa.recl.storage.memory

import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.testing.ContextStorageContract

class InMemoryContextStorageContractTest : ContextStorageContract() {
    override fun newStorage(): ContextStorage = InMemoryContextStorage()
}
