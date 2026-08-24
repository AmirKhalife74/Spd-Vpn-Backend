package app.spdvpn.backend.model

import kotlinx.serialization.Serializable

@Serializable
enum class ServerHealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
}

@Serializable
data class ServerHealthRecord(
    val serverId: String,
    val status: ServerHealthStatus,
    val lastCheckedAtEpochMillis: Long,
    val lastSuccessfulAtEpochMillis: Long? = null,
    val lastProbeDurationMs: Long? = null,
    val lastSuccessfulConnectionTimeMs: Long? = null,
    val consecutiveFailures: Int,
    val lastFailureStage: String? = null,
    val lastFailureReason: String? = null,
)

@Serializable
data class ProbeResult(
    val serverId: String,
    val success: Boolean,
    val stage: String,
    val reason: String? = null,
    val durationMs: Long,
    val checkedAtEpochMillis: Long,
)
