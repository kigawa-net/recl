package net.kigawa.recl.storage.fortis.inproc;

import java.nio.file.Path;
import net.kigawa.fortis.io.fs.JvmExclusiveFileOwner;

// Fortis exposes this guard in its JVM bytecode, but marks it internal in Kotlin.
final class FortisDirectoryOwner implements AutoCloseable {
    private final JvmExclusiveFileOwner owner;

    private FortisDirectoryOwner(JvmExclusiveFileOwner owner) {
        this.owner = owner;
    }

    static FortisDirectoryOwner acquire(Path directory) {
        return new FortisDirectoryOwner(JvmExclusiveFileOwner.Companion.tryAcquire(directory));
    }

    Path getDataDirectory() {
        return owner.getDataDirectory();
    }

    void verifyLockFileIdentity() {
        owner.verifyLockFileIdentity();
    }

    @Override
    public void close() {
        owner.close();
    }
}
