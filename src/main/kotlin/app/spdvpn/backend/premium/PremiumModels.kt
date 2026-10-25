package app.spdvpn.backend.premium

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable enum class AccountStatus { ACTIVE, EXPIRED, DISABLED }
@Serializable enum class SyncState { PENDING, SYNCED, FAILED }
@Serializable data class Plan(val id: String, val title: String, val description: String = "",
    val durationDays: Int? = null, val lifetime: Boolean = false, val quotaBytes: Long? = null,
    val enabled: Boolean = true, val published: Boolean = true,
    val priceMinor: Long? = null, val currency: String? = null,
    val displayOrder: Int = when (id) { "MONTH_1" -> 0; "MONTH_3" -> 1; else -> 2 },
    val localizedTitles: Map<String, String> = emptyMap(),
    val localizedDescriptions: Map<String, String> = emptyMap(),
    val isAssignable: Boolean = true)
@Serializable data class ProductResponse(val serverTimeEpochMillis: Long, val plans: List<Plan>,
    val premiumVpnConfigured: Boolean, val telegramBotUsername: String? = null,
    val premiumPurchaseUrl: String? = null, val premiumPurchaseEnabled: Boolean = false)
@Serializable data class AccountProfile(val username: String, val status: AccountStatus,
    val planId: String, val planTitle: String, val premiumUntilEpochMillis: Long? = null,
    val lifetime: Boolean = false, val premiumVpnConfigured: Boolean = false,
    val serverTimeEpochMillis: Long, val syncState: SyncState,
    val quotaBytes: Long? = null, val usedBytes: Long? = null)
@Serializable class CredentialsRequest(val username: String, val password: String)
@Serializable class RefreshRequest(val refreshToken: String)
@Serializable class PasswordRequest(val password: String)
@Serializable class LinkRequest(val pasarGuardUserId: Long, val planId: String, val confirm: Boolean)
@Serializable class CreateAccountRequest(val username: String, val password: String, val planId: String,
    val existingPasarGuardUserId: Long? = null, val confirmExistingUser: Boolean = false)
@Serializable data class ChangeSubscriptionRequest(val action: String, val planId: String? = null,
    val disabled: Boolean? = null, val confirmLifetimeToTimed: Boolean = false)
@Serializable class SessionTokens(val accessToken: String, val accessExpiresAtEpochMillis: Long,
    val refreshToken: String, val refreshExpiresAtEpochMillis: Long, val profile: AccountProfile,
    val serverTimeEpochMillis: Long)
@Serializable class AdminSessionData(val username: String, val csrfToken: String, val expiresAtEpochMillis: Long)
@Serializable data class RemoteUser(val id: Long, val username: String, val status: String,
    val expiresAtEpochMillis: Long? = null, val quotaBytes: Long? = null, val usedBytes: Long,
    val note: String? = null)
@Serializable data class SyncTarget(val expiresAtEpochMillis: Long? = null, val quotaBytes: Long? = null,
    val disabled: Boolean = false)
@Serializable data class AccountView(val id: String, val username: String, val planId: String,
    val createdAtEpochMillis: Long, val requested: SyncTarget, val confirmed: RemoteUser? = null,
    val lastObservedAtEpochMillis: Long? = null, val syncState: SyncState, val lastErrorCode: String? = null,
    val pasarGuardUserId: Long? = null, val operationId: String? = null)
@Serializable data class OperationView(val id: String, val accountId: String, val kind: String,
    val state: SyncState, val attempts: Int, val createdAtEpochMillis: Long, val errorCode: String? = null)
@Serializable data class AuditEvent(val id: Long, val actor: String, val action: String,
    val accountId: String? = null, val operationId: String? = null, val atEpochMillis: Long)
@Serializable data class Page<T>(val items: List<T>, val total: Int, val offset: Int, val limit: Int)
@Serializable data class PremiumServerSummary(val id: String, val countryCode: String,
    val countryName: String, val displayName: String, val protocol: String, val transport: String)
@Serializable data class PremiumCatalogResponse(val servers: List<PremiumServerSummary>, val serverTimeEpochMillis: Long)
@Serializable class XrayProfileResponse(val server: PremiumServerSummary, val xrayJson: String,
    val expiresAtEpochMillis: Long? = null)
data class IssuedProfile(val summary: PremiumServerSummary, val config: JsonObject) {
    override fun toString() = "IssuedProfile(redacted)"
}
class PremiumProblem(val status: Int, val code: String, val safeMessage: String) : RuntimeException(safeMessage)
internal fun bad(message: String): Nothing = throw PremiumProblem(400, "BAD_REQUEST", message)
internal fun conflict(message: String): Nothing = throw PremiumProblem(409, "CONFLICT", message)
internal fun missing(): Nothing = throw PremiumProblem(404, "NOT_FOUND", "رکورد پیدا نشد")
internal fun unauthorized(): Nothing = throw PremiumProblem(401, "TOKEN_INVALID", "ورود یا نشست معتبر نیست")
internal fun unavailable(code: String = "PASARGUARD_UNAVAILABLE"): Nothing =
    throw PremiumProblem(503, code, "ارتباط یا تأیید سرویس VPN انجام نشد؛ وضعیت حساب تغییر نکرد")
