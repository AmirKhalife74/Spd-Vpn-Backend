package app.spdvpn.backend.service

import app.spdvpn.backend.cache.CatalogCacheStore
import app.spdvpn.backend.health.InMemoryServerHealthSnapshotStore
import app.spdvpn.backend.health.ServerHealthTracker
import app.spdvpn.backend.health.ServerHealthSnapshotView
import app.spdvpn.backend.model.CatalogSnapshot
import app.spdvpn.backend.model.CountrySummary
import app.spdvpn.backend.model.HealthData
import app.spdvpn.backend.model.ProbeResult
import app.spdvpn.backend.model.ServerHealthRecord
import app.spdvpn.backend.model.ServerHealthStatus
import app.spdvpn.backend.model.SourceMetadata
import app.spdvpn.backend.model.VpnProtocol
import app.spdvpn.backend.model.VpnServerRecord
import app.spdvpn.backend.source.SoftEtherPortState
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateHtmlParseResult
import app.spdvpn.backend.source.VpnGateHtmlParser
import app.spdvpn.backend.source.VpnGateHtmlServerMetadata
import app.spdvpn.backend.source.VpnGateHtmlSource
import app.spdvpn.backend.source.VpnGateSource
import app.spdvpn.backend.source.normalizeIpv4
import app.spdvpn.backend.source.normalizeVpnGateHostName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CatalogService(
    private val source: VpnGateSource,
    private val parser: VpnGateCsvParser,
    private val cacheStore: CatalogCacheStore,
    private val refreshInterval: Duration,
    private val maxStaleAge: Duration,
    private val htmlSource: VpnGateHtmlSource? = null,
    private val htmlParser: VpnGateHtmlParser = VpnGateHtmlParser(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val healthTracker: ServerHealthTracker = ServerHealthTracker(
        snapshotStore = InMemoryServerHealthSnapshotStore(),
        clock = clock,
    ),
) {
    private val snapshot = AtomicReference<CatalogSnapshot?>()
    private val refreshMutex = Mutex()
    private val lastRefreshAttempt = AtomicLong(0)
    private val lastRefreshSuccess = AtomicLong(0)

    @Volatile
    private var lastRefreshError: String? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        healthTracker.load()
        loadLastKnownGood()

        while (isActive) {
            val result = refresh()
            delay(if (result is RefreshResult.Success) refreshInterval else minOf(refreshInterval, 30.seconds))
        }
    }

    suspend fun loadLastKnownGood() {
        runCatching { cacheStore.load() }
            .onSuccess { cached ->
                if (cached != null && cached.servers.isNotEmpty()) {
                    snapshot.set(cached)
                    healthTracker.reconcileCatalog(cached.servers.mapTo(mutableSetOf(), VpnServerRecord::id))
                    logger.info(
                        "Loaded {} cached VPN servers from snapshot {}",
                        cached.servers.size,
                        cached.version,
                    )
                }
            }
            .onFailure { error ->
                logger.warn("Could not load the last-known-good VPN Gate cache", error)
            }
    }

    suspend fun refresh(): RefreshResult = refreshMutex.withLock {
        val attemptAt = clock()
        lastRefreshAttempt.set(attemptAt)

        try {
            val downloaded = source.fetch()
            val csvSnapshot = parser.parse(downloaded)
            val previousSnapshot = snapshot.get()
            val htmlResult = fetchSoftEtherMetadata()
            val enrichment = enrichSoftEtherMetadata(
                csvSnapshot = csvSnapshot,
                htmlResult = htmlResult,
                previousSnapshot = previousSnapshot,
            )
            val freshSnapshot = enrichment.snapshot
            snapshot.set(freshSnapshot)
            healthTracker.reconcileCatalog(freshSnapshot.servers.mapTo(mutableSetOf(), VpnServerRecord::id))

            runCatching { cacheStore.save(freshSnapshot) }
                .onFailure { error -> logger.error("Could not persist VPN Gate cache", error) }

            lastRefreshSuccess.set(clock())
            lastRefreshError = null
            logger.info(
                "VPN Gate catalog refreshed: totalCsvServers={} htmlRowsInspected={} " +
                    "softEtherEnriched={} softEtherMissing={} malformedRows={} ignoredNonServerRows={} " +
                    "cachedMetadataReused={} cachedMetadataReusedCount={} rejectedCsvRows={} version={}",
                freshSnapshot.servers.size,
                htmlResult?.rowsInspected ?: 0,
                enrichment.enrichedServers,
                enrichment.missingServers,
                htmlResult?.malformedRows ?: 0,
                htmlResult?.ignoredNonServerRows ?: 0,
                enrichment.cachedMetadataReusedCount > 0,
                enrichment.cachedMetadataReusedCount,
                freshSnapshot.rejectedRows,
                freshSnapshot.version,
            )
            RefreshResult.Success(freshSnapshot.servers.size, freshSnapshot.version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastRefreshError = error.message ?: error::class.simpleName ?: "Unknown refresh error"
            logger.warn("VPN Gate catalog refresh failed; keeping last-known-good data", error)
            RefreshResult.Failure(lastRefreshError.orEmpty())
        }
    }

    private suspend fun fetchSoftEtherMetadata(): VpnGateHtmlParseResult? {
        val configuredSource = htmlSource ?: return null
        return try {
            htmlParser.parse(configuredSource.fetch())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn(
                "VPN Gate SoftEther metadata refresh failed; continuing with CSV data and safe cached metadata",
                error,
            )
            null
        }
    }

    private fun enrichSoftEtherMetadata(
        csvSnapshot: CatalogSnapshot,
        htmlResult: VpnGateHtmlParseResult?,
        previousSnapshot: CatalogSnapshot?,
    ): SoftEtherEnrichmentResult {
        val currentIndex = htmlResult?.servers?.let(::SoftEtherMetadataIndex)
        val cachedIndex = previousSnapshot?.servers
            ?.mapNotNull { server ->
                val cachedPort = server.softEtherTcpPort?.takeIf { it in VALID_PORT_RANGE }
                    ?: return@mapNotNull null
                val hostName = normalizeVpnGateHostName(server.hostName)
                val ip = normalizeIpv4(server.ip)
                if (hostName == null && ip == null) return@mapNotNull null
                VpnGateHtmlServerMetadata(
                    hostName = hostName,
                    ip = ip,
                    softEtherTcpPort = cachedPort,
                    portState = SoftEtherPortState.KNOWN,
                )
            }
            ?.let(::SoftEtherMetadataIndex)

        var cachedMetadataReusedCount = 0
        val enrichedServers = csvSnapshot.servers.map { server ->
            val current = currentIndex?.match(server)
            val cachedPort = cachedIndex?.match(server)?.softEtherTcpPort
            val softEtherTcpPort = when (current?.portState) {
                SoftEtherPortState.KNOWN -> current.softEtherTcpPort
                SoftEtherPortState.MISSING -> null
                SoftEtherPortState.MALFORMED, null -> cachedPort.also {
                    if (it != null) cachedMetadataReusedCount++
                }
            }
            server.copy(softEtherTcpPort = softEtherTcpPort)
        }
        val knownPortCount = enrichedServers.count { it.softEtherTcpPort != null }
        val version = versionWithSoftEtherMetadata(csvSnapshot.version, enrichedServers)

        return SoftEtherEnrichmentResult(
            snapshot = csvSnapshot.copy(
                schemaVersion = CURRENT_CATALOG_SCHEMA_VERSION,
                version = version,
                servers = enrichedServers,
            ),
            enrichedServers = knownPortCount,
            missingServers = enrichedServers.size - knownPortCount,
            cachedMetadataReusedCount = cachedMetadataReusedCount,
        )
    }

    fun currentSnapshot(): CatalogSnapshot =
        snapshot.get() ?: throw CatalogUnavailableException()

    fun sourceMetadata(current: CatalogSnapshot = currentSnapshot()): SourceMetadata {
        val ageMillis = (clock() - current.fetchedAtEpochMillis).coerceAtLeast(0)
        return SourceMetadata(
            version = current.version,
            fetchedAtEpochMillis = current.fetchedAtEpochMillis,
            ageSeconds = ageMillis / 1_000,
            stale = ageMillis > maxStaleAge.inWholeMilliseconds,
            sourceRows = current.sourceRows,
            rejectedRows = current.rejectedRows,
        )
    }

    fun health(): HealthData {
        val current = snapshot.get()
        val metadata = current?.let(::sourceMetadata)
        val ready = current != null
        val status = when {
            !ready -> "unavailable"
            metadata?.stale == true || lastRefreshError != null -> "degraded"
            else -> "healthy"
        }

        return HealthData(
            status = status,
            ready = ready,
            serverCount = current?.servers?.size ?: 0,
            source = metadata,
            lastRefreshAttemptAtEpochMillis = lastRefreshAttempt.get().takeIf { it > 0 },
            lastRefreshSuccessAtEpochMillis = lastRefreshSuccess.get().takeIf { it > 0 },
            lastRefreshError = lastRefreshError,
        )
    }

    fun countries(current: CatalogSnapshot = currentSnapshot()): List<CountrySummary> =
        current.servers
            .groupBy { it.countryCode to it.countryName }
            .map { (country, servers) ->
                CountrySummary(
                    code = country.first,
                    name = country.second,
                    serverCount = servers.size,
                    protocols = servers.map(VpnServerRecord::protocol)
                        .distinct()
                        .sortedBy(VpnProtocol::name),
                )
            }
            .sortedWith(compareByDescending<CountrySummary>(CountrySummary::serverCount).thenBy(CountrySummary::name))

    fun findServer(id: String, current: CatalogSnapshot = currentSnapshot()): VpnServerRecord? =
        current.servers.firstOrNull { it.id == id }

    fun serverHealthSnapshot(): ServerHealthSnapshotView = healthTracker.snapshotView()

    fun serverListVersion(
        current: CatalogSnapshot = currentSnapshot(),
        health: ServerHealthSnapshotView = serverHealthSnapshot(),
    ): String = "${current.version}-health-${health.revision}"

    suspend fun recordProbeResult(result: ProbeResult): ServerHealthRecord {
        validateProbeResult(result)
        if (findServer(result.serverId) == null) throw ProbeServerNotFoundException()
        return healthTracker.recordProbeResult(result)
    }

    fun queryServers(
        query: ServerQuery,
        current: CatalogSnapshot = currentSnapshot(),
        health: ServerHealthSnapshotView = serverHealthSnapshot(),
    ): ServerQueryResult {
        val normalizedSearch = query.search?.lowercase(Locale.ROOT)
        val secondaryComparator = query.comparator()
        val filtered = current.servers.asSequence()
            .filter { query.countryCode == null || it.countryCode == query.countryCode }
            .filter { query.protocol == null || it.protocol == query.protocol }
            .filter { query.minimumScore == null || it.score >= query.minimumScore }
            .filter { server ->
                query.healthStatus == null || health.statusFor(server.id) == query.healthStatus
            }
            .filter { server ->
                query.maximumPingMs?.let { maximum ->
                    server.pingMs?.let { ping -> ping <= maximum } ?: false
                } ?: true
            }
            .filter { server ->
                normalizedSearch == null || sequenceOf(
                    server.hostName,
                    server.ip,
                    server.countryName,
                    server.countryCode,
                    server.operator,
                ).any { it.lowercase(Locale.ROOT).contains(normalizedSearch) }
            }
            .sortedWith { first, second ->
                val healthComparison = healthRank(health.statusFor(first.id))
                    .compareTo(healthRank(health.statusFor(second.id)))
                if (healthComparison != 0) healthComparison else secondaryComparator.compare(first, second)
            }
            .toList()

        return ServerQueryResult(
            servers = filtered.drop(query.offset).take(query.limit),
            total = filtered.size,
        )
    }

    private fun validateProbeResult(result: ProbeResult) {
        requireProbe(result.serverId.length in 1..128, "serverId must be between 1 and 128 characters")
        requireProbe(SERVER_ID_PATTERN.matches(result.serverId), "serverId contains invalid characters")
        requireProbe(result.stage.length in 1..64, "stage must be between 1 and 64 characters")
        requireProbe(PROBE_STAGE_PATTERN.matches(result.stage), "stage must use uppercase letters, digits, or underscores")
        requireProbe(result.reason == null || result.reason.length <= 512, "reason must not exceed 512 characters")
        requireProbe(result.durationMs in 0..MAX_PROBE_DURATION_MS, "durationMs is out of range")
        requireProbe(!result.success || result.stage == CONNECTED_STAGE, "successful probes must use the CONNECTED stage")
        requireProbe(result.success || !result.reason.isNullOrBlank(), "reason is required for failed probes")

        val now = clock()
        requireProbe(result.checkedAtEpochMillis > 0, "checkedAtEpochMillis must be positive")
        requireProbe(
            result.checkedAtEpochMillis <= now + MAX_FUTURE_CLOCK_SKEW_MILLIS,
            "checkedAtEpochMillis is too far in the future",
        )
        requireProbe(
            now - result.checkedAtEpochMillis <= MAX_PROBE_RESULT_AGE_MILLIS,
            "checkedAtEpochMillis is too old",
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(CatalogService::class.java)
        private val SERVER_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
        private val PROBE_STAGE_PATTERN = Regex("^[A-Z][A-Z0-9_]*$")
        private const val CONNECTED_STAGE = "CONNECTED"
        private const val MAX_PROBE_DURATION_MS = 600_000L
        private const val MAX_FUTURE_CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
        private const val MAX_PROBE_RESULT_AGE_MILLIS = 24 * 60 * 60 * 1_000L
        private const val CURRENT_CATALOG_SCHEMA_VERSION = 2
        private val VALID_PORT_RANGE = 1..65_535
    }
}

private data class SoftEtherEnrichmentResult(
    val snapshot: CatalogSnapshot,
    val enrichedServers: Int,
    val missingServers: Int,
    val cachedMetadataReusedCount: Int,
)

private class SoftEtherMetadataIndex(entries: List<VpnGateHtmlServerMetadata>) {
    private val byIp = entries.groupBy(VpnGateHtmlServerMetadata::ip)
        .mapValues { (_, matches) -> matches.consensusOrNull() }
    private val byHostName = entries.groupBy(VpnGateHtmlServerMetadata::hostName)
        .mapValues { (_, matches) -> matches.consensusOrNull() }

    fun match(server: VpnServerRecord): VpnGateHtmlServerMetadata? {
        normalizeIpv4(server.ip)?.let { ip ->
            if (byIp.containsKey(ip)) return byIp[ip]
        }
        normalizeVpnGateHostName(server.hostName)?.let { hostName ->
            if (byHostName.containsKey(hostName)) return byHostName[hostName]
        }
        return null
    }
}

private fun List<VpnGateHtmlServerMetadata>.consensusOrNull(): VpnGateHtmlServerMetadata? {
    val distinctPorts = map { it.portState to it.softEtherTcpPort }.distinct()
    return if (distinctPorts.size == 1) first() else null
}

private fun versionWithSoftEtherMetadata(
    csvVersion: String,
    servers: List<VpnServerRecord>,
): String {
    if (servers.none { it.softEtherTcpPort != null }) return csvVersion
    val metadata = servers.joinToString(separator = "|") { "${it.id}:${it.softEtherTcpPort ?: "-"}" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$csvVersion|$metadata".toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return digest.take(32)
}

data class ServerQuery(
    val countryCode: String? = null,
    val protocol: VpnProtocol? = null,
    val minimumScore: Long? = null,
    val maximumPingMs: Int? = null,
    val healthStatus: ServerHealthStatus? = null,
    val search: String? = null,
    val sort: ServerSort = ServerSort.SCORE,
    val order: SortOrder = SortOrder.DESC,
    val offset: Int = 0,
    val limit: Int = 50,
) {
    fun comparator(): Comparator<VpnServerRecord> {
        val ascending = when (sort) {
            ServerSort.SCORE -> compareBy(VpnServerRecord::score)
            ServerSort.SPEED -> compareBy(VpnServerRecord::speedBps)
            ServerSort.PING -> compareBy { it.pingMs ?: Int.MAX_VALUE }
            ServerSort.UPTIME -> compareBy(VpnServerRecord::uptimeMillis)
            ServerSort.USERS -> compareBy(VpnServerRecord::totalUsers)
            ServerSort.SESSIONS -> compareBy(VpnServerRecord::activeSessions)
        }
        return if (order == SortOrder.ASC) ascending else ascending.reversed()
    }
}

data class ServerQueryResult(
    val servers: List<VpnServerRecord>,
    val total: Int,
)

enum class ServerSort { SCORE, SPEED, PING, UPTIME, USERS, SESSIONS }
enum class SortOrder { ASC, DESC }

sealed interface RefreshResult {
    data class Success(val serverCount: Int, val version: String) : RefreshResult
    data class Failure(val reason: String) : RefreshResult
}

class CatalogUnavailableException : RuntimeException("No VPN server catalog is available yet")

class InvalidProbeResultException(message: String) : IllegalArgumentException(message)

class ProbeServerNotFoundException : RuntimeException("VPN server was not found in the current catalog")

private fun requireProbe(condition: Boolean, message: String) {
    if (!condition) throw InvalidProbeResultException(message)
}

private fun healthRank(status: ServerHealthStatus): Int = when (status) {
    ServerHealthStatus.HEALTHY -> 0
    ServerHealthStatus.UNKNOWN -> 1
    ServerHealthStatus.DEGRADED -> 2
    ServerHealthStatus.UNHEALTHY -> 3
}
