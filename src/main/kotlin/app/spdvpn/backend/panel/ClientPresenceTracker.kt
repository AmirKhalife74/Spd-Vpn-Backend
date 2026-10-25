package app.spdvpn.backend.panel

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class ClientPresenceTracker(
    private val ttl: Duration,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = ConcurrentHashMap<String, ActiveClientSession>()

    @Synchronized
    fun heartbeat(request: ClientHeartbeatRequest): PresenceAcceptedData {
        validateHeartbeat(request)
        val now = clock()
        prune(now)
        require(sessions.containsKey(request.sessionId) || sessions.size < 10_000) {
            "Presence capacity reached; retry later"
        }
        sessions[request.sessionId] = ActiveClientSession(
            sessionId = request.sessionId,
            installationId = request.installationId,
            platform = request.platform,
            appVersionCode = request.appVersionCode,
            appVersionName = request.appVersionName.trim(),
            serverId = request.serverId?.trim()?.takeIf(String::isNotEmpty),
            serverCountryCode = request.serverCountryCode
                ?.trim()
                ?.uppercase()
                ?.takeIf(String::isNotEmpty),
            protocol = request.protocol?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            connectedAtEpochMillis = request.connectedAtEpochMillis,
            lastSeenAtEpochMillis = now,
        )
        return PresenceAcceptedData(
            activeConnections = sessions.size,
            expiresAfterSeconds = ttl.inWholeSeconds.toInt(),
        )
    }

    fun disconnect(sessionId: String): PresenceAcceptedData {
        requireSessionId(sessionId)
        sessions.remove(sessionId)
        val now = clock()
        prune(now)
        return PresenceAcceptedData(
            activeConnections = sessions.size,
            expiresAfterSeconds = ttl.inWholeSeconds.toInt(),
        )
    }

    fun dashboard(): PresenceDashboard {
        val now = clock()
        prune(now)
        val active = sessions.values.toList()
        return PresenceDashboard(
            activeConnections = active.size,
            uniqueActiveInstallations = active.map { it.installationId }.distinct().size,
            byCountry = active.toCounts { it.serverCountryCode ?: "UNKNOWN" },
            byProtocol = active.toCounts { it.protocol ?: "UNKNOWN" },
            byVersion = active.toCounts { "${it.platform.name} • ${it.appVersionName}" },
            generatedAtEpochMillis = now,
        )
    }

    private fun prune(now: Long) {
        val oldestAllowed = now - ttl.inWholeMilliseconds
        sessions.entries.removeIf { it.value.lastSeenAtEpochMillis <= oldestAllowed }
    }

    private fun validateHeartbeat(request: ClientHeartbeatRequest) {
        requireSessionId(request.sessionId)
        require(request.installationId.matches(SAFE_ID_REGEX)) {
            "installationId must be a safe identifier between 8 and 128 characters"
        }
        require(request.appVersionCode > 0) { "appVersionCode must be greater than zero" }
        require(request.appVersionName.isNotBlank() && request.appVersionName.length <= 64) {
            "appVersionName must contain between 1 and 64 characters"
        }
        request.serverId?.let {
            require(it.isNotBlank() && it.length <= 128) { "serverId must not exceed 128 characters" }
        }
        request.serverCountryCode?.let {
            require(it.trim().matches(COUNTRY_CODE_REGEX)) {
                "serverCountryCode must be a two-letter country code"
            }
        }
        request.protocol?.let {
            require(it.isNotBlank() && it.length <= 32) { "protocol must not exceed 32 characters" }
        }
        val now = clock()
        require(request.connectedAtEpochMillis in 1..(now + ALLOWED_CLOCK_SKEW_MILLIS)) {
            "connectedAtEpochMillis is invalid"
        }
    }

    private fun requireSessionId(sessionId: String) {
        require(sessionId.matches(SAFE_ID_REGEX)) {
            "sessionId must be a safe identifier between 8 and 128 characters"
        }
    }

    private fun List<ActiveClientSession>.toCounts(
        label: (ActiveClientSession) -> String,
    ): List<CountSummary> = groupingBy(label)
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { CountSummary(label = it.key, count = it.value) }

    private data class ActiveClientSession(
        val sessionId: String,
        val installationId: String,
        val platform: ClientPlatform,
        val appVersionCode: Int,
        val appVersionName: String,
        val serverId: String?,
        val serverCountryCode: String?,
        val protocol: String?,
        val connectedAtEpochMillis: Long,
        val lastSeenAtEpochMillis: Long,
    )

    private companion object {
        private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
        private val COUNTRY_CODE_REGEX = Regex("^[A-Za-z]{2}$")
        private const val ALLOWED_CLOCK_SKEW_MILLIS = 10 * 60 * 1_000L
    }
}
