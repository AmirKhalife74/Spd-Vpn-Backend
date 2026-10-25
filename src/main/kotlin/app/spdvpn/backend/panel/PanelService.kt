package app.spdvpn.backend.panel

import app.spdvpn.backend.api.ApiBadRequestException
import app.spdvpn.backend.api.ApiNotFoundException
import java.net.URI
import java.util.Locale
import java.util.UUID

class PanelService(
    private val store: PanelContentStore,
    private val presenceTracker: ClientPresenceTracker,
    private val heartbeatIntervalSeconds: Int = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun bootstrap(platform: ClientPlatform, currentVersionCode: Int): AppBootstrapData {
        if (currentVersionCode <= 0) throw ApiBadRequestException("versionCode must be greater than zero")
        val now = clock()
        val content = store.load()
        val latest = content.releases
            .asSequence()
            .filter { it.enabled && it.platform == platform }
            .maxByOrNull(ManagedRelease::versionCode)
        val update = latest
            ?.takeIf { currentVersionCode < it.versionCode }
            ?.let {
                AppUpdate(
                    versionCode = it.versionCode,
                    versionName = it.versionName,
                    downloadUrl = it.downloadUrl,
                    sha256 = it.sha256,
                    changelog = it.changelog,
                    required = it.mandatory || currentVersionCode < it.minimumSupportedVersionCode,
                    publishedAtEpochMillis = it.publishedAtEpochMillis,
                )
            }
        val announcements = content.announcements
            .filter { announcement ->
                announcement.enabled &&
                    announcement.startsAtEpochMillis <= now &&
                    (announcement.endsAtEpochMillis == null || announcement.endsAtEpochMillis > now) &&
                    (announcement.platforms.isEmpty() || platform in announcement.platforms) &&
                    (announcement.minimumVersionCode == null || currentVersionCode >= announcement.minimumVersionCode) &&
                    (announcement.maximumVersionCode == null || currentVersionCode <= announcement.maximumVersionCode)
            }
            .sortedByDescending(ManagedAnnouncement::createdAtEpochMillis)

        return AppBootstrapData(
            serverTimeEpochMillis = now,
            heartbeatIntervalSeconds = heartbeatIntervalSeconds,
            update = update,
            announcements = announcements,
            recommendation = content.recommendation?.takeIf(ServerRecommendation::enabled),
        )
    }

    fun heartbeat(request: ClientHeartbeatRequest): PresenceAcceptedData = try {
        presenceTracker.heartbeat(request)
    } catch (error: IllegalArgumentException) {
        throw ApiBadRequestException(error.message.orEmpty())
    }

    fun disconnect(request: ClientDisconnectRequest): PresenceAcceptedData = try {
        presenceTracker.disconnect(request.sessionId)
    } catch (error: IllegalArgumentException) {
        throw ApiBadRequestException(error.message.orEmpty())
    }

    suspend fun dashboard(): AdminDashboardData {
        val now = clock()
        val content = store.load()
        return AdminDashboardData(
            presence = presenceTracker.dashboard(),
            enabledReleaseCount = content.releases.count(ManagedRelease::enabled),
            activeAnnouncementCount = content.announcements.count {
                it.enabled && it.startsAtEpochMillis <= now && (it.endsAtEpochMillis == null || it.endsAtEpochMillis > now)
            },
            recommendationEnabled = content.recommendation?.enabled == true,
        )
    }

    suspend fun releases(): List<ManagedRelease> = store.load().releases
        .sortedWith(compareBy<ManagedRelease> { it.platform }.thenByDescending { it.versionCode })

    suspend fun createRelease(draft: ReleaseDraft): ManagedRelease {
        validateRelease(draft)
        val release = draft.toManaged(id = UUID.randomUUID().toString(), publishedAt = clock())
        store.update { snapshot ->
            if (snapshot.releases.any { it.platform == release.platform && it.versionCode == release.versionCode }) {
                throw ApiBadRequestException("A release with this platform and versionCode already exists")
            }
            snapshot.copy(releases = snapshot.releases + release)
        }
        return release
    }

    suspend fun updateRelease(id: String, draft: ReleaseDraft): ManagedRelease {
        validateRelease(draft)
        var updated: ManagedRelease? = null
        store.update { snapshot ->
            val existing = snapshot.releases.firstOrNull { it.id == id }
                ?: throw ApiNotFoundException("Release was not found")
            if (snapshot.releases.any {
                    it.id != id && it.platform == draft.platform && it.versionCode == draft.versionCode
                }
            ) {
                throw ApiBadRequestException("A release with this platform and versionCode already exists")
            }
            updated = draft.toManaged(id = existing.id, publishedAt = existing.publishedAtEpochMillis)
            snapshot.copy(releases = snapshot.releases.map { if (it.id == id) updated!! else it })
        }
        return updated!!
    }

    suspend fun deleteRelease(id: String) {
        store.update { snapshot ->
            if (snapshot.releases.none { it.id == id }) throw ApiNotFoundException("Release was not found")
            snapshot.copy(releases = snapshot.releases.filterNot { it.id == id })
        }
    }

    suspend fun announcements(): List<ManagedAnnouncement> = store.load().announcements
        .sortedByDescending(ManagedAnnouncement::createdAtEpochMillis)

    suspend fun createAnnouncement(draft: AnnouncementDraft): ManagedAnnouncement {
        validateAnnouncement(draft)
        val now = clock()
        val announcement = draft.toManaged(
            id = UUID.randomUUID().toString(),
            startsAt = draft.startsAtEpochMillis ?: now,
            createdAt = now,
        )
        store.update { it.copy(announcements = it.announcements + announcement) }
        return announcement
    }

    suspend fun updateAnnouncement(id: String, draft: AnnouncementDraft): ManagedAnnouncement {
        validateAnnouncement(draft)
        var updated: ManagedAnnouncement? = null
        store.update { snapshot ->
            val existing = snapshot.announcements.firstOrNull { it.id == id }
                ?: throw ApiNotFoundException("Announcement was not found")
            updated = draft.toManaged(
                id = existing.id,
                startsAt = draft.startsAtEpochMillis ?: existing.startsAtEpochMillis,
                createdAt = existing.createdAtEpochMillis,
            )
            snapshot.copy(announcements = snapshot.announcements.map { if (it.id == id) updated!! else it })
        }
        return updated!!
    }

    suspend fun deleteAnnouncement(id: String) {
        store.update { snapshot ->
            if (snapshot.announcements.none { it.id == id }) {
                throw ApiNotFoundException("Announcement was not found")
            }
            snapshot.copy(announcements = snapshot.announcements.filterNot { it.id == id })
        }
    }

    suspend fun recommendation(): ServerRecommendation? = store.load().recommendation

    suspend fun premiumPurchaseSettings(): PremiumPurchaseSettings = store.load().premiumPurchase

    suspend fun updatePremiumPurchaseSettings(settings: PremiumPurchaseSettings): PremiumPurchaseSettings {
        val value = settings.premiumPurchaseUrl?.trim()?.takeIf(String::isNotEmpty)
        val normalized = PremiumPurchaseUrlPolicy.normalize(value)
        if ((value != null && normalized == null) || (settings.premiumPurchaseEnabled && normalized == null)) {
            throw ApiBadRequestException("Purchase URL must be an HTTPS t.me contact username link")
        }
        val validated = settings.copy(premiumPurchaseUrl = normalized)
        store.update { it.copy(premiumPurchase = validated) }
        return validated
    }

    suspend fun updateRecommendation(draft: RecommendationDraft): ServerRecommendation {
        val recommendation = validateRecommendation(draft).let {
            ServerRecommendation(
                countryCode = draft.countryCode?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty),
                serverId = draft.serverId?.trim()?.takeIf(String::isNotEmpty),
                title = draft.title.trim(),
                message = draft.message.trim(),
                enabled = draft.enabled,
                updatedAtEpochMillis = clock(),
            )
        }
        store.update { it.copy(recommendation = recommendation) }
        return recommendation
    }

    private fun validateRelease(draft: ReleaseDraft) {
        if (draft.versionCode <= 0) throw ApiBadRequestException("versionCode must be greater than zero")
        if (draft.minimumSupportedVersionCode <= 0 || draft.minimumSupportedVersionCode > draft.versionCode) {
            throw ApiBadRequestException("minimumSupportedVersionCode must be between 1 and versionCode")
        }
        if (draft.versionName.isBlank() || draft.versionName.length > 64) {
            throw ApiBadRequestException("versionName must contain between 1 and 64 characters")
        }
        if (draft.changelog.length > 4_000) throw ApiBadRequestException("changelog must not exceed 4000 characters")
        if (!draft.sha256.matches(Regex("^[A-Fa-f0-9]{64}$"))) {
            throw ApiBadRequestException("sha256 must contain exactly 64 hexadecimal characters")
        }
        val uri = runCatching { URI(draft.downloadUrl) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            throw ApiBadRequestException("downloadUrl must be an absolute HTTPS URL")
        }
    }

    private fun validateAnnouncement(draft: AnnouncementDraft) {
        if (draft.title.isBlank() || draft.title.length > 120) {
            throw ApiBadRequestException("title must contain between 1 and 120 characters")
        }
        if (draft.message.isBlank() || draft.message.length > 2_000) {
            throw ApiBadRequestException("message must contain between 1 and 2000 characters")
        }
        if (draft.minimumVersionCode != null && draft.minimumVersionCode <= 0) {
            throw ApiBadRequestException("minimumVersionCode must be greater than zero")
        }
        if (draft.maximumVersionCode != null && draft.maximumVersionCode <= 0) {
            throw ApiBadRequestException("maximumVersionCode must be greater than zero")
        }
        if (
            draft.minimumVersionCode != null && draft.maximumVersionCode != null &&
            draft.minimumVersionCode > draft.maximumVersionCode
        ) {
            throw ApiBadRequestException("minimumVersionCode must not exceed maximumVersionCode")
        }
        val startsAt = draft.startsAtEpochMillis ?: clock()
        if (draft.endsAtEpochMillis != null && draft.endsAtEpochMillis <= startsAt) {
            throw ApiBadRequestException("endsAtEpochMillis must be after startsAtEpochMillis")
        }
    }

    private fun validateRecommendation(draft: RecommendationDraft) {
        val country = draft.countryCode?.trim()?.takeIf(String::isNotEmpty)
        val serverId = draft.serverId?.trim()?.takeIf(String::isNotEmpty)
        if (country != null && !country.matches(Regex("^[A-Za-z]{2}$"))) {
            throw ApiBadRequestException("countryCode must be a two-letter country code")
        }
        if (serverId != null && serverId.length > 128) {
            throw ApiBadRequestException("serverId must not exceed 128 characters")
        }
        if (draft.enabled && country == null && serverId == null) {
            throw ApiBadRequestException("An enabled recommendation requires countryCode or serverId")
        }
        if (draft.title.isBlank() || draft.title.length > 120) {
            throw ApiBadRequestException("title must contain between 1 and 120 characters")
        }
        if (draft.message.isBlank() || draft.message.length > 500) {
            throw ApiBadRequestException("message must contain between 1 and 500 characters")
        }
    }

    private fun ReleaseDraft.toManaged(id: String, publishedAt: Long) = ManagedRelease(
        id = id,
        platform = platform,
        versionCode = versionCode,
        versionName = versionName.trim(),
        downloadUrl = downloadUrl.trim(),
        sha256 = sha256.lowercase(Locale.ROOT),
        changelog = changelog.trim(),
        mandatory = mandatory,
        minimumSupportedVersionCode = minimumSupportedVersionCode,
        enabled = enabled,
        publishedAtEpochMillis = publishedAt,
    )

    private fun AnnouncementDraft.toManaged(id: String, startsAt: Long, createdAt: Long) = ManagedAnnouncement(
        id = id,
        title = title.trim(),
        message = message.trim(),
        severity = severity,
        platforms = platforms.distinct(),
        minimumVersionCode = minimumVersionCode,
        maximumVersionCode = maximumVersionCode,
        startsAtEpochMillis = startsAt,
        endsAtEpochMillis = endsAtEpochMillis,
        dismissible = dismissible,
        enabled = enabled,
        createdAtEpochMillis = createdAt,
    )
}
