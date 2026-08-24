package app.spdvpn.backend.health

import app.spdvpn.backend.model.ProbeResult
import app.spdvpn.backend.model.ServerHealthRecord
import app.spdvpn.backend.model.ServerHealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

interface ServerHealthSnapshotStore {
    suspend fun load(): ServerHealthSnapshot?
    suspend fun save(snapshot: ServerHealthSnapshot)
}

class FileServerHealthSnapshotStore(
    private val path: Path,
    private val json: Json,
) : ServerHealthSnapshotStore {
    override suspend fun load(): ServerHealthSnapshot? = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext null
        json.decodeFromString<ServerHealthSnapshot>(Files.readString(path))
    }

    override suspend fun save(snapshot: ServerHealthSnapshot) = withContext(Dispatchers.IO) {
        path.parent?.let(Files::createDirectories)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(snapshot))

        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }
}

class InMemoryServerHealthSnapshotStore(
    initial: ServerHealthSnapshot? = null,
) : ServerHealthSnapshotStore {
    @Volatile
    private var snapshot: ServerHealthSnapshot? = initial

    override suspend fun load(): ServerHealthSnapshot? = snapshot

    override suspend fun save(snapshot: ServerHealthSnapshot) {
        this.snapshot = snapshot
    }
}

class ServerHealthTracker(
    private val snapshotStore: ServerHealthSnapshotStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val absentRetention: Duration = 24.hours,
) {
    private val mutationMutex = Mutex()
    private val state = AtomicReference(TrackerState())

    suspend fun load() = mutationMutex.withLock {
        val loaded = runCatching { snapshotStore.load() }
            .onFailure { error ->
                logger.warn("Could not load the server health snapshot; starting with no health records", error)
            }
            .getOrNull()

        if (loaded == null) {
            logger.info("Loaded 0 server health records")
            return@withLock
        }
        if (loaded.schemaVersion != HEALTH_SCHEMA_VERSION) {
            logger.warn("Unsupported server health snapshot schema {}; starting with no health records", loaded.schemaVersion)
            return@withLock
        }

        val records = loaded.entries
            .asSequence()
            .filter { it.isValid() }
            .associateBy { it.health.serverId }
        val revision = loaded.revision.coerceAtLeast(0)
        state.set(TrackerState(revision = revision, entries = records))
        logger.info("Loaded {} server health records", records.size)
    }

    fun healthFor(serverId: String): ServerHealthRecord? = state.get().entries[serverId]?.health

    fun statusFor(serverId: String): ServerHealthStatus =
        healthFor(serverId)?.status ?: ServerHealthStatus.UNKNOWN

    fun snapshotView(): ServerHealthSnapshotView {
        val current = state.get()
        return ServerHealthSnapshotView(
            revision = current.revision,
            records = current.entries.mapValues { it.value.health },
        )
    }

    suspend fun recordProbeResult(result: ProbeResult): ServerHealthRecord = mutationMutex.withLock {
        val currentState = state.get()
        val previous = currentState.entries[result.serverId]
        if (previous != null && result.checkedAtEpochMillis <= previous.health.lastCheckedAtEpochMillis) {
            throw StaleProbeResultException()
        }

        val health = if (result.success) {
            ServerHealthRecord(
                serverId = result.serverId,
                status = ServerHealthStatus.HEALTHY,
                lastCheckedAtEpochMillis = result.checkedAtEpochMillis,
                lastSuccessfulAtEpochMillis = result.checkedAtEpochMillis,
                lastProbeDurationMs = result.durationMs,
                lastSuccessfulConnectionTimeMs = result.durationMs,
                consecutiveFailures = 0,
                lastFailureStage = null,
                lastFailureReason = null,
            )
        } else {
            val failures = previous?.health?.consecutiveFailures
                ?.let { if (it == Int.MAX_VALUE) it else it + 1 }
                ?: 1
            ServerHealthRecord(
                serverId = result.serverId,
                status = if (failures >= 3) ServerHealthStatus.UNHEALTHY else ServerHealthStatus.DEGRADED,
                lastCheckedAtEpochMillis = result.checkedAtEpochMillis,
                lastSuccessfulAtEpochMillis = previous?.health?.lastSuccessfulAtEpochMillis,
                lastProbeDurationMs = result.durationMs,
                lastSuccessfulConnectionTimeMs = previous?.health?.lastSuccessfulConnectionTimeMs,
                consecutiveFailures = failures,
                lastFailureStage = result.stage,
                lastFailureReason = result.reason,
            )
        }

        val nextState = currentState.copy(
            revision = currentState.revision.nextRevision(),
            entries = currentState.entries + (
                result.serverId to ServerHealthEntry(
                    health = health,
                    absentSinceEpochMillis = null,
                )
            ),
        )
        state.set(nextState)
        persist(nextState)
        health
    }

    suspend fun reconcileCatalog(activeServerIds: Set<String>) = mutationMutex.withLock {
        val currentState = state.get()
        if (currentState.entries.isEmpty()) return@withLock
        val now = clock()
        var changed = false
        val reconciled = buildMap {
            currentState.entries.forEach { (serverId, entry) ->
                when {
                    serverId in activeServerIds -> {
                        if (entry.absentSinceEpochMillis != null) changed = true
                        put(serverId, entry.copy(absentSinceEpochMillis = null))
                    }

                    entry.absentSinceEpochMillis == null -> {
                        changed = true
                        put(serverId, entry.copy(absentSinceEpochMillis = now))
                    }

                    (now - entry.absentSinceEpochMillis).coerceAtLeast(0) < absentRetention.inWholeMilliseconds -> {
                        put(serverId, entry)
                    }

                    else -> changed = true
                }
            }
        }
        if (!changed) return@withLock

        val nextState = TrackerState(
            revision = currentState.revision.nextRevision(),
            entries = reconciled,
        )
        state.set(nextState)
        persist(nextState)
    }

    private suspend fun persist(currentState: TrackerState) {
        val snapshot = ServerHealthSnapshot(
            revision = currentState.revision,
            entries = currentState.entries.values.sortedBy { it.health.serverId },
        )
        runCatching { snapshotStore.save(snapshot) }
            .onFailure { error -> logger.error("Could not persist the server health snapshot", error) }
    }

    private data class TrackerState(
        val revision: Long = 0,
        val entries: Map<String, ServerHealthEntry> = emptyMap(),
    )

    private companion object {
        private val logger = LoggerFactory.getLogger(ServerHealthTracker::class.java)
        private const val HEALTH_SCHEMA_VERSION = 2
    }
}

@Serializable
data class ServerHealthSnapshot(
    val schemaVersion: Int = 2,
    val revision: Long = 0,
    val entries: List<ServerHealthEntry> = emptyList(),
)

@Serializable
data class ServerHealthEntry(
    val health: ServerHealthRecord,
    val absentSinceEpochMillis: Long? = null,
)

data class ServerHealthSnapshotView(
    val revision: Long,
    val records: Map<String, ServerHealthRecord>,
) {
    fun statusFor(serverId: String): ServerHealthStatus =
        records[serverId]?.status ?: ServerHealthStatus.UNKNOWN
}

private fun ServerHealthEntry.isValid(): Boolean {
    if (health.serverId.length !in 1..128) return false
    if (health.lastCheckedAtEpochMillis <= 0 || health.consecutiveFailures < 0) return false
    if (health.lastSuccessfulAtEpochMillis?.let { it <= 0 || it > health.lastCheckedAtEpochMillis } == true) return false
    if (health.lastProbeDurationMs?.let { it !in 0..MAX_PERSISTED_DURATION_MS } == true) return false
    if (health.lastSuccessfulConnectionTimeMs?.let { it !in 0..MAX_PERSISTED_DURATION_MS } == true) return false
    if ((health.lastSuccessfulAtEpochMillis == null) != (health.lastSuccessfulConnectionTimeMs == null)) return false
    if (absentSinceEpochMillis?.let { it <= 0 } == true) return false
    return when (health.status) {
        ServerHealthStatus.UNKNOWN -> false
        ServerHealthStatus.HEALTHY -> health.consecutiveFailures == 0 &&
            health.lastSuccessfulAtEpochMillis != null &&
            health.lastProbeDurationMs != null &&
            health.lastSuccessfulConnectionTimeMs != null &&
            health.lastFailureStage == null &&
            health.lastFailureReason == null

        ServerHealthStatus.DEGRADED -> health.consecutiveFailures in 1..2 &&
            health.lastProbeDurationMs != null &&
            !health.lastFailureStage.isNullOrBlank() &&
            !health.lastFailureReason.isNullOrBlank()

        ServerHealthStatus.UNHEALTHY -> health.consecutiveFailures >= 3 &&
            health.lastProbeDurationMs != null &&
            !health.lastFailureStage.isNullOrBlank() &&
            !health.lastFailureReason.isNullOrBlank()
    }
}

private fun Long.nextRevision(): Long = if (this == Long.MAX_VALUE) 1 else this + 1

class StaleProbeResultException : IllegalArgumentException("Probe result is older than the current health record")

private const val MAX_PERSISTED_DURATION_MS = 600_000L
