package net.kigawa.recl.storage.fortis.inproc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.kigawa.fortis.io.fs.Fs
import net.kigawa.fortis.raft.FileRaftPersistentStateStore
import net.kigawa.fortis.raft.StorageEngineStateMachine
import net.kigawa.fortis.raft.batch.ConditionalBatchClient
import net.kigawa.fortis.raft.leader.LeaderNode
import net.kigawa.fortis.raft.log.FileRaftLog
import net.kigawa.fortis.raft.node.RaftNodeBuilder
import net.kigawa.fortis.raft.runtime.RaftRuntime
import net.kigawa.fortis.raft.scan.RevisionScanClient
import net.kigawa.fortis.raft.snapshot.FileRaftSnapshotStore
import net.kigawa.fortis.raft.snapshot.RaftSnapshotApplier
import net.kigawa.fortis.raft.transport.RaftRpcChannel
import net.kigawa.fortis.raft.transport.RpcRaftTransport
import net.kigawa.fortis.raft.vote.RaftVolatileState
import net.kigawa.fortis.storage.engine.mvcc.MemoryMvccStorageEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class FortisInprocNode private constructor(
    private val runtime: RaftRuntime,
    machine: StorageEngineStateMachine,
    private val owner: FortisDirectoryOwner,
    private val job: Job,
    snapshotEvery: Duration,
) : AutoCloseable {
    val port = FortisRaftKvPort(
        runtime, machine, ConditionalBatchClient(runtime, TIMEOUT), RevisionScanClient(runtime, TIMEOUT), TIMEOUT,
    )

    private var closed = false
    private val snapshots = CoroutineScope(Dispatchers.IO + job).launch {
        while (isActive) {
            delay(snapshotEvery)
            // Once publication starts, let it finish before shutdown releases the writer lock.
            withContext(NonCancellable) {
                try {
                    saveIfNeeded(runtime.readIndex(TIMEOUT).index)
                } catch (failure: Exception) {
                    LOGGER.log(System.Logger.Level.ERROR, "Could not save Fortis snapshot", failure)
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        port.stopAcceptingCalls()
        runBlocking {
            withContext(NonCancellable) {
                owner.use {
                    snapshots.cancelAndJoin()
                    try {
                        // A timed-out call may still be applying. Wait for a Raft read barrier
                        // before taking the final snapshot and cancelling the proposal scope.
                        saveIfNeeded(runtime.readIndex(TIMEOUT).index)
                    } finally {
                        try {
                            runtime.stop()
                        } finally {
                            job.cancelAndJoin()
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveIfNeeded(appliedIndex: Long) {
        val compactedIndex = runtime.currentNode.log.snapshotMetadata()?.lastIncludedIndex ?: 0L
        if (appliedIndex > compactedIndex) {
            owner.verifyLockFileIdentity()
            runtime.saveMvccSnapshot()
        }
    }

    companion object {
        private val LOGGER = System.getLogger(FortisInprocNode::class.java.name)
        private val TIMEOUT = 5.seconds

        fun open(dir: Path, nodeId: String, snapshotEvery: Duration): FortisInprocNode {
            require(nodeId.isNotBlank()) { "nodeId must be non-blank" }
            require(snapshotEvery.isPositive() && snapshotEvery.isFinite()) { "snapshotEvery must be positive and finite" }
            val owner = FortisDirectoryOwner.acquire(Files.createDirectories(dir))
            val job = SupervisorJob()
            try {
                return runBlocking {
                    val directory = owner.dataDirectory
                    val machine = StorageEngineStateMachine(MemoryMvccStorageEngine())
                    val log = FileRaftLog.open(Fs.getPath(directory.resolve("log").toString()).toFile())
                    val state = FileRaftPersistentStateStore(Fs.getPath(directory.resolve("state").toString()).toFile())
                    val snapshots = FileRaftSnapshotStore(Fs.getPath(directory.resolve("snapshot").toString()).toFile())
                    val node = RaftNodeBuilder(nodeId, emptySet(), state, RaftVolatileState(), log, machine).build(
                        snapshots, RaftSnapshotApplier { _, data -> machine.restoreSnapshot(data) },
                    )
                    val runtime = RaftRuntime(
                        node, RpcRaftTransport(NoPeers), snapshots,
                        proposalCoroutineContext = Dispatchers.IO + job,
                        snapshotApplier = RaftSnapshotApplier { _, data -> machine.installSnapshot(data) },
                    )
                    runtime.start()
                    runtime.onElectionTimeout()
                    check(runtime.currentNode is LeaderNode) { "Embedded Fortis did not become leader" }
                    FortisInprocNode(runtime, machine, owner, job, snapshotEvery)
                }
            } catch (failure: Throwable) {
                runCatching {
                    runBlocking { withContext(NonCancellable) { job.cancelAndJoin() } }
                }.onFailure(failure::addSuppressed)
                runCatching { owner.close() }.onFailure(failure::addSuppressed)
                throw failure
            }
        }

        private object NoPeers : RaftRpcChannel {
            override suspend fun request(peerId: String, frame: ByteArray): ByteArray =
                error("Embedded Fortis has no Raft peers")
        }
    }
}
