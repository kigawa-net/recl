package net.kigawa.recl.storage.fortis

import net.kigawa.recl.core.ContextStorage
import net.kigawa.recl.core.testing.ContextStorageContract

/** The shared ContextStorage contract, run against the Fortis adapter over the faithful fake. */
class FortisContextStorageContractTest : ContextStorageContract() {
    override fun newStorage(): ContextStorage = FortisContextStorage(FaithfulFortisKvFake())
}
