# Embedded Fortis storage

`:storage-fortis-inproc` embeds one Fortis Raft node in the recl JVM. The app loads it through the
`StorageProvider` SPI when `RECL_STORAGE=fortis`. The node serves linearizable reads, applies
conditional writes, and persists Contexts and request receipts in the Raft log and MVCC snapshots.
When it opens, it restores the last snapshot and replays the remaining log through the single-node
leader election. The node has no peers and exposes no network KV endpoint.

## Build and run

Use JDK 21 and a Fortis checkout containing the APIs from Fortis PR #77 (`getWithRevision` and
`BatchOutcome.Rejected.raftIndex`). Gradle uses `../fortis` by default. Point it at the compatible
checkout if that default checkout lacks the APIs:

```sh
./gradlew -Pfortis.dir=/path/to/fortis :app:installDist
```

If the selected directory is absent, Gradle omits the module. The app still builds with memory
storage and explains how to include Fortis if requested at startup.

| Environment variable | Value |
| --- | --- |
| `RECL_MCP_ENABLED` | `1` enables the HTTP MCP runtime. |
| `RECL_STORAGE` | `fortis` selects embedded durable storage. The default is `memory`. |
| `RECL_FORTIS_DIR` | Required writable data directory in Fortis mode, for example `/var/lib/recl/fortis`. |
| `RECL_MCP_TENANT_ID` | In Fortis mode use lowercase letters, digits, and hyphens, with an initial letter or digit and at most 63 characters. |
| `RECL_MCP_ALLOW_EPHEMERAL` | Required as `1` only in memory mode. |

Set the [HTTP MCP authentication variables](mcp-http.md) for token or OIDC authentication. Launch
`app/build/install/app/bin/app`. The dashboard reports
`fortis (in-process, single-node, durable)` in `modules.mcpStorage`.

The provider settings are `dir`, `nodeId` (default `recl`), and `snapshotEvery` (positive integer
seconds, default 30). The app supplies `dir` from the environment and uses the defaults for the
other two settings. The node saves a snapshot when the applied Raft index has advanced, compacts the
covered Raft log, and takes a final snapshot on orderly shutdown.

## Single-node operation and backup

Run one recl replica with one writable data directory on a persistent volume (PVC in Kubernetes).
The directory contains `log`, `state`, `snapshot`, and the Fortis writer lock. The JVM writer
guard rejects another opener of the same directory. This mode provides restart durability on that
volume. It has no high availability, remote failover, or cross-replica coordination.

For backup, stop recl and copy the entire `RECL_FORTIS_DIR` as one unit. Restore the entire copy
while recl is stopped and point `RECL_FORTIS_DIR` to the restored directory.

Production remote storage remains blocked on Fortis #33 (client SDK/API) and #34 (KV authentication
and authorization). The [binding assessment](fortis-binding-assessment.md) tracks those requirements.
Bundling a compatible Fortis checkout into the Docker build and provisioning it in CI are separate
tasks. The current Dockerfile and deployment workflows are unchanged.
