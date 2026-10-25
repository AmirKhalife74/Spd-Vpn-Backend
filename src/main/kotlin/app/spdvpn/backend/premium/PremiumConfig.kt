package app.spdvpn.backend.premium

import java.net.URI
import java.nio.file.Path

class PremiumConfig(
    val databasePath: Path = Path.of("data/premium.sqlite"),
    val adminOrigin: String = "https://localhost:8080",
    val insecureLocalCookies: Boolean = false,
    val adminSessionMillis: Long = 43_200_000,
    val accessMillis: Long = 900_000,
    val refreshMillis: Long = 2_592_000_000,
    val pasarGuardUrl: String? = null,
    val pasarGuardUsername: String? = null,
    val pasarGuardPassword: String? = null,
    val pasarGuardTimeoutMillis: Long = 10_000,
    val pasarGuardGroupIds: List<Long> = emptyList(),
    val allowLocalPasarGuardHttp: Boolean = false,
    val defaultQuotaBytes: Long = 107_374_182_400,
    val panelContentPath: Path = databasePath.resolveSibling("panel-content.json"),
) {
    override fun toString() = "PremiumConfig(redacted)"
    init {
        require(databasePath.toAbsolutePath().normalize() != panelContentPath.toAbsolutePath().normalize()) { "SPD database and panel paths must differ" }
        val origin = URI(adminOrigin)
        require(origin.host != null && origin.userInfo == null && origin.rawQuery == null &&
            origin.rawFragment == null && origin.path.orEmpty() in listOf("", "/")) { "Invalid SPD_ADMIN_ORIGIN" }
        require(origin.scheme == "https" || (insecureLocalCookies && origin.scheme == "http" &&
            origin.host in setOf("127.0.0.1", "localhost", "[::1]"))) { "Admin origin must use HTTPS (or explicit local HTTP)" }
        require(!insecureLocalCookies || origin.host in setOf("127.0.0.1", "localhost", "[::1]")) {
            "Insecure cookies are restricted to loopback development"
        }
        require(adminSessionMillis in 60_000..86_400_000 && accessMillis in 60_000..3_600_000 &&
            refreshMillis in 60_000..7_776_000_000L && pasarGuardTimeoutMillis in 1_000..60_000 && defaultQuotaBytes in 1..9_007_199_254_740_991L)
        val configured = listOf(pasarGuardUrl, pasarGuardUsername, pasarGuardPassword).count { !it.isNullOrBlank() }
        require(configured == 0 || configured == 3) { "All PASARGUARD connection settings must be supplied together" }
        pasarGuardUrl?.let {
            val uri = URI(it)
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.scheme == "https" || (allowLocalPasarGuardHttp && uri.scheme == "http" &&
                    uri.host in setOf("127.0.0.1", "localhost", "[::1]")))) { "Invalid PASARGUARD_BASE_URL" }
            require(pasarGuardGroupIds.isNotEmpty() && pasarGuardGroupIds.all { id -> id > 0 }) {
                "PASARGUARD_GROUP_IDS must name existing groups"
            }
        }
    }
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()) = PremiumConfig(
            databasePath = Path.of(env["SPD_DATABASE_PATH"] ?: "data/premium.sqlite"),
            adminOrigin = (env["SPD_ADMIN_ORIGIN"] ?: "https://localhost:8080").trimEnd('/'),
            insecureLocalCookies = env["SPD_ADMIN_INSECURE_LOCAL"] == "true",
            adminSessionMillis = (env["SPD_ADMIN_SESSION_SECONDS"] ?: "43200").toLong() * 1000,
            accessMillis = (env["SPD_ACCESS_TOKEN_SECONDS"] ?: "900").toLong() * 1000,
            refreshMillis = (env["SPD_REFRESH_TOKEN_SECONDS"] ?: "2592000").toLong() * 1000,
            pasarGuardUrl = env["PASARGUARD_BASE_URL"]?.takeIf { it.isNotBlank() },
            pasarGuardUsername = env["PASARGUARD_USERNAME"]?.takeIf { it.isNotBlank() },
            pasarGuardPassword = env["PASARGUARD_PASSWORD"]?.takeIf { it.isNotBlank() },
            pasarGuardTimeoutMillis = (env["PASARGUARD_TIMEOUT_MS"] ?: "10000").toLong(),
            pasarGuardGroupIds = env["PASARGUARD_GROUP_IDS"].orEmpty().split(',').filter(String::isNotBlank).map { it.trim().toLong() },
            allowLocalPasarGuardHttp = env["PASARGUARD_ALLOW_LOCAL_HTTP"] == "true",
            defaultQuotaBytes = (env["SPD_DEFAULT_QUOTA_BYTES"] ?: "107374182400").toLong(),
            panelContentPath = Path.of(env["PANEL_CONTENT_PATH"] ?: Path.of(env["SPD_DATABASE_PATH"] ?: "data/premium.sqlite").resolveSibling("panel-content.json").toString()),
        )
    }
}
