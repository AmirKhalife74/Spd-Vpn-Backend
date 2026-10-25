package app.spdvpn.backend.panel

import kotlinx.serialization.Serializable

@Serializable
enum class ClientPlatform {
    ANDROID_DIRECT,
    ANDROID_PLAY,
    DESKTOP_MACOS,
    DESKTOP_WINDOWS,
}

@Serializable
enum class AnnouncementSeverity {
    INFO,
    SUCCESS,
    WARNING,
    CRITICAL,
}

@Serializable
data class ManagedRelease(
    val id: String,
    val platform: ClientPlatform,
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val changelog: String,
    val mandatory: Boolean,
    val minimumSupportedVersionCode: Int,
    val enabled: Boolean,
    val publishedAtEpochMillis: Long,
)

@Serializable
data class ReleaseDraft(
    val platform: ClientPlatform,
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val changelog: String,
    val mandatory: Boolean = false,
    val minimumSupportedVersionCode: Int = 1,
    val enabled: Boolean = true,
)

@Serializable
data class ManagedAnnouncement(
    val id: String,
    val title: String,
    val message: String,
    val severity: AnnouncementSeverity,
    val platforms: List<ClientPlatform> = emptyList(),
    val minimumVersionCode: Int? = null,
    val maximumVersionCode: Int? = null,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long? = null,
    val dismissible: Boolean = true,
    val enabled: Boolean = true,
    val createdAtEpochMillis: Long,
)

@Serializable
data class AnnouncementDraft(
    val title: String,
    val message: String,
    val severity: AnnouncementSeverity = AnnouncementSeverity.INFO,
    val platforms: List<ClientPlatform> = emptyList(),
    val minimumVersionCode: Int? = null,
    val maximumVersionCode: Int? = null,
    val startsAtEpochMillis: Long? = null,
    val endsAtEpochMillis: Long? = null,
    val dismissible: Boolean = true,
    val enabled: Boolean = true,
)

@Serializable
data class ServerRecommendation(
    val countryCode: String? = null,
    val serverId: String? = null,
    val title: String,
    val message: String,
    val enabled: Boolean = true,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class RecommendationDraft(
    val countryCode: String? = null,
    val serverId: String? = null,
    val title: String,
    val message: String,
    val enabled: Boolean = true,
)

@Serializable
data class PanelContentSnapshot(
    val schemaVersion: Int = 1,
    val releases: List<ManagedRelease> = emptyList(),
    val announcements: List<ManagedAnnouncement> = emptyList(),
    val recommendation: ServerRecommendation? = null,
    val premiumPurchase: PremiumPurchaseSettings = PremiumPurchaseSettings(),
)

@Serializable
data class PremiumPurchaseSettings(
    val premiumPurchaseUrl: String? = null,
    val premiumPurchaseEnabled: Boolean = false,
)

@Serializable
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val changelog: String,
    val required: Boolean,
    val publishedAtEpochMillis: Long,
)

@Serializable
data class AppBootstrapData(
    val serverTimeEpochMillis: Long,
    val heartbeatIntervalSeconds: Int,
    val update: AppUpdate? = null,
    val announcements: List<ManagedAnnouncement> = emptyList(),
    val recommendation: ServerRecommendation? = null,
)

@Serializable
data class ClientHeartbeatRequest(
    val sessionId: String,
    val installationId: String,
    val platform: ClientPlatform,
    val appVersionCode: Int,
    val appVersionName: String,
    val serverId: String? = null,
    val serverCountryCode: String? = null,
    val protocol: String? = null,
    val connectedAtEpochMillis: Long,
)

@Serializable
data class ClientDisconnectRequest(
    val sessionId: String,
)

@Serializable
data class PresenceAcceptedData(
    val accepted: Boolean = true,
    val activeConnections: Int,
    val expiresAfterSeconds: Int,
)

@Serializable
data class CountSummary(
    val label: String,
    val count: Int,
)

@Serializable
data class PresenceDashboard(
    val activeConnections: Int,
    val uniqueActiveInstallations: Int,
    val byCountry: List<CountSummary>,
    val byProtocol: List<CountSummary>,
    val byVersion: List<CountSummary>,
    val generatedAtEpochMillis: Long,
)

@Serializable
data class AdminDashboardData(
    val presence: PresenceDashboard,
    val enabledReleaseCount: Int,
    val activeAnnouncementCount: Int,
    val recommendationEnabled: Boolean,
)
