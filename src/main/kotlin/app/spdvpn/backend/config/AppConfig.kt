package app.spdvpn.backend.config

import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AppConfig(
    val sourceUrl: String,
    val htmlUrl: String = DEFAULT_VPN_GATE_HTML_URL,
    val refreshInterval: Duration,
    val fetchTimeout: Duration,
    val cachePath: Path,
    val maxStaleAge: Duration,
    val rateLimitRequests: Int,
    val rateLimitWindow: Duration,
    val corsAllowedHosts: List<String>,
    val healthSnapshotPath: Path = Path.of("data/server-health.json"),
    val proberSharedSecret: String? = null,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppConfig {
            val sourceUrl = environment.valueOrDefault(
                key = "VPN_GATE_SOURCE_URL",
                default = "https://www.vpngate.net/api/iphone/",
            )
            require(URI(sourceUrl).scheme in setOf("http", "https")) {
                "VPN_GATE_SOURCE_URL must use http or https"
            }
            val htmlUrl = environment.valueOrDefault(
                key = "VPN_GATE_HTML_URL",
                default = DEFAULT_VPN_GATE_HTML_URL,
            )
            require(URI(htmlUrl).scheme in setOf("http", "https")) {
                "VPN_GATE_HTML_URL must use http or https"
            }
            val catalogCachePath = Path.of(
                environment.valueOrDefault("VPN_GATE_CACHE_PATH", "data/vpngate-cache.json"),
            )
            val healthSnapshotPath = Path.of(
                environment.valueOrDefault("HEALTH_SNAPSHOT_PATH", "data/server-health.json"),
            )
            require(catalogCachePath.normalize() != healthSnapshotPath.normalize()) {
                "HEALTH_SNAPSHOT_PATH must be different from VPN_GATE_CACHE_PATH"
            }

            return AppConfig(
                sourceUrl = sourceUrl,
                htmlUrl = htmlUrl,
                refreshInterval = environment.positiveLong("VPN_GATE_REFRESH_INTERVAL_SECONDS", 300).seconds,
                fetchTimeout = environment.positiveLong("VPN_GATE_FETCH_TIMEOUT_SECONDS", 30).seconds,
                cachePath = catalogCachePath,
                maxStaleAge = environment.positiveLong("VPN_GATE_MAX_STALE_SECONDS", 86_400).seconds,
                rateLimitRequests = environment.positiveInt("RATE_LIMIT_REQUESTS", 120),
                rateLimitWindow = environment.positiveLong("RATE_LIMIT_WINDOW_SECONDS", 60).seconds,
                corsAllowedHosts = environment.valueOrDefault("CORS_ALLOWED_HOSTS", "*")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty),
                healthSnapshotPath = healthSnapshotPath,
                proberSharedSecret = environment["PROBER_SHARED_SECRET"]?.takeIf(String::isNotBlank),
            )
        }

        const val DEFAULT_VPN_GATE_HTML_URL = "https://www.vpngate.net/en/"
    }
}

private fun Map<String, String>.valueOrDefault(key: String, default: String): String =
    this[key]?.trim()?.takeIf(String::isNotEmpty) ?: default

private fun Map<String, String>.positiveLong(key: String, default: Long): Long {
    val raw = this[key]?.trim()?.takeIf(String::isNotEmpty) ?: return default
    val value = raw.toLongOrNull() ?: error("$key must be an integer")
    require(value > 0) { "$key must be greater than zero" }
    return value
}

private fun Map<String, String>.positiveInt(key: String, default: Int): Int {
    val raw = this[key]?.trim()?.takeIf(String::isNotEmpty) ?: return default
    val value = raw.toIntOrNull() ?: error("$key must be an integer")
    require(value > 0) { "$key must be greater than zero" }
    return value
}
