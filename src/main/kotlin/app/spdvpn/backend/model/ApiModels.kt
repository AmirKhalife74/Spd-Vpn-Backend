package app.spdvpn.backend.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean = true,
    val data: T,
)

@Serializable
data class ApiErrorResponse(
    val success: Boolean = false,
    val error: ApiError,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class SourceMetadata(
    val version: String,
    val fetchedAtEpochMillis: Long,
    val ageSeconds: Long,
    val stale: Boolean,
    val sourceRows: Int,
    val rejectedRows: Int,
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
data class ServersData(
    val servers: List<VpnServerSummary>,
    val page: PageMetadata,
    val source: SourceMetadata,
)

@Serializable
data class ServerConfigData(
    val id: String,
    val protocol: VpnProtocol,
    val port: Int? = null,
    val openVpnConfig: String,
    val source: SourceMetadata,
)

@Serializable
data class CountrySummary(
    val code: String,
    val name: String,
    val serverCount: Int,
    val protocols: List<VpnProtocol>,
)

@Serializable
data class CountriesData(
    val countries: List<CountrySummary>,
    val source: SourceMetadata,
)

@Serializable
data class HealthData(
    val status: String,
    val ready: Boolean,
    val serverCount: Int,
    val source: SourceMetadata? = null,
    val lastRefreshAttemptAtEpochMillis: Long? = null,
    val lastRefreshSuccessAtEpochMillis: Long? = null,
    val lastRefreshError: String? = null,
)

@Serializable
data class ServiceInfo(
    val name: String = "SPD VPN Backend",
    val version: String = "0.1.0",
    val apiVersion: String = "v1",
)

@Serializable
data class ProbeResultAcceptedData(
    val serverId: String,
    val healthStatus: ServerHealthStatus,
    val consecutiveFailures: Int,
)
