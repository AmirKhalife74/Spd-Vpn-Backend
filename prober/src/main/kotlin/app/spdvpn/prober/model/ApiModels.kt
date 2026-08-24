package app.spdvpn.prober.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T,
)

@Serializable
data class ServersData(
    val servers: List<ProbeServer>,
    val page: PageMetadata,
)

@Serializable
data class PageMetadata(
    val offset: Int,
    val limit: Int,
    val returned: Int,
    val total: Int,
    val hasMore: Boolean,
)

@Serializable
data class ProbeServer(
    val id: String,
    val hostName: String,
    val ip: String,
    val score: Long = 0,
    val healthStatus: ServerHealthStatus = ServerHealthStatus.UNKNOWN,
    val lastCheckedAtEpochMillis: Long? = null,
)

@Serializable
enum class ServerHealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
}

@Serializable
data class ServerConfigData(
    val id: String,
    val openVpnConfig: String,
)

@Serializable
data class ProbeResultRequest(
    val serverId: String,
    val success: Boolean,
    val stage: String,
    val reason: String? = null,
    val durationMs: Long,
    val checkedAtEpochMillis: Long,
)

@Serializable
data class ProbeResultAcceptedData(
    val serverId: String,
    val healthStatus: ServerHealthStatus,
    val consecutiveFailures: Int,
)
