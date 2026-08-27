package app.spdvpn.backend.model

import kotlinx.serialization.Serializable

@Serializable
enum class VpnProtocol {
    TCP,
    UDP,
    UNKNOWN,
}

@Serializable
data class VpnServerRecord(
    val id: String,
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int? = null,
    val speedBps: Long,
    val countryName: String,
    val countryCode: String,
    val activeSessions: Int,
    val uptimeMillis: Long,
    val totalUsers: Long,
    val totalTrafficBytes: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val protocol: VpnProtocol,
    val port: Int? = null,
    val openVpnConfig: String,
    val softEtherTcpPort: Int? = null,
    val softEtherTlsServerName: String? = null,
)

@Serializable
data class CatalogSnapshot(
    val schemaVersion: Int = 3,
    val version: String,
    val fetchedAtEpochMillis: Long,
    val sourceRows: Int,
    val rejectedRows: Int,
    val servers: List<VpnServerRecord>,
)

@Serializable
data class VpnServerSummary(
    val id: String,
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int? = null,
    val speedBps: Long,
    val countryName: String,
    val countryCode: String,
    val activeSessions: Int,
    val uptimeMillis: Long,
    val totalUsers: Long,
    val totalTrafficBytes: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val protocol: VpnProtocol,
    val port: Int? = null,
    val softEtherTcpPort: Int? = null,
    val softEtherTlsServerName: String? = null,
    val healthStatus: ServerHealthStatus = ServerHealthStatus.UNKNOWN,
    val lastCheckedAtEpochMillis: Long? = null,
    val lastSuccessfulAtEpochMillis: Long? = null,
    val lastProbeDurationMs: Long? = null,
    val lastSuccessfulConnectionTimeMs: Long? = null,
    val consecutiveFailures: Int = 0,
)

fun VpnServerRecord.toSummary(health: ServerHealthRecord? = null) = VpnServerSummary(
    id = id,
    hostName = hostName,
    ip = ip,
    score = score,
    pingMs = pingMs,
    speedBps = speedBps,
    countryName = countryName,
    countryCode = countryCode,
    activeSessions = activeSessions,
    uptimeMillis = uptimeMillis,
    totalUsers = totalUsers,
    totalTrafficBytes = totalTrafficBytes,
    logType = logType,
    operator = operator,
    message = message,
    protocol = protocol,
    port = port,
    softEtherTcpPort = softEtherTcpPort,
    softEtherTlsServerName = softEtherTlsServerName,
    healthStatus = health?.status ?: ServerHealthStatus.UNKNOWN,
    lastCheckedAtEpochMillis = health?.lastCheckedAtEpochMillis,
    lastSuccessfulAtEpochMillis = health?.lastSuccessfulAtEpochMillis,
    lastProbeDurationMs = health?.lastProbeDurationMs,
    lastSuccessfulConnectionTimeMs = health?.lastSuccessfulConnectionTimeMs,
    consecutiveFailures = health?.consecutiveFailures ?: 0,
)
