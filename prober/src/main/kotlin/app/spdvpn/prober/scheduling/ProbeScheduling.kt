package app.spdvpn.prober.scheduling

import app.spdvpn.prober.model.ProbeServer
import app.spdvpn.prober.model.ServerHealthStatus
import app.spdvpn.prober.time.ProberClock
import app.spdvpn.prober.time.SuspendSleeper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

class ConfigRequestGate(
    interval: Duration,
    private val clock: ProberClock,
    private val sleeper: SuspendSleeper,
) {
    private val intervalMillis = interval.inWholeMilliseconds
    private val mutex = Mutex()
    private var lastStartedAtMillis: Long? = null

    init {
        require(intervalMillis >= 1_100L) { "Configuration request interval must be at least 1100ms" }
    }

    suspend fun awaitTurn() {
        mutex.withLock {
            val now = clock.nowEpochMillis()
            val waitMillis = lastStartedAtMillis
                ?.let { previous -> (intervalMillis - (now - previous)).coerceAtLeast(0) }
                ?: 0
            sleeper.sleep(waitMillis)
            lastStartedAtMillis = clock.nowEpochMillis()
        }
    }
}

class ServerSelector(
    private val clock: ProberClock,
    private val healthyCooldown: Duration,
) {
    fun select(servers: List<ProbeServer>, maximum: Int): List<ProbeServer> {
        require(maximum > 0) { "maximum must be positive" }
        val now = clock.nowEpochMillis()
        val cooldownMillis = healthyCooldown.inWholeMilliseconds

        return servers
            .distinctBy(ProbeServer::id)
            .asSequence()
            .filter { server ->
                server.healthStatus != ServerHealthStatus.HEALTHY ||
                    server.lastCheckedAtEpochMillis == null ||
                    now - server.lastCheckedAtEpochMillis >= cooldownMillis
            }
            .sortedWith(
                compareBy<ProbeServer> { it.healthStatus.selectionRank() }
                    .thenBy { it.lastCheckedAtEpochMillis ?: Long.MIN_VALUE }
                    .thenByDescending(ProbeServer::score)
                    .thenBy(ProbeServer::id),
            )
            .take(maximum)
            .toList()
    }
}

private fun ServerHealthStatus.selectionRank(): Int = when (this) {
    ServerHealthStatus.UNKNOWN -> 0
    ServerHealthStatus.DEGRADED -> 1
    ServerHealthStatus.UNHEALTHY -> 2
    ServerHealthStatus.HEALTHY -> 3
}
