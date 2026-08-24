package app.spdvpn.prober.config

import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ProberConfig(
    val apiBaseUrl: String,
    val sharedSecret: String?,
    val sweepInterval: Duration,
    val initialJitter: Duration,
    val concurrency: Int,
    val probeTimeout: Duration,
    val configRequestInterval: Duration,
    val maxServersPerSweep: Int,
    val runOnce: Boolean,
    val dryRun: Boolean,
    val openVpnExecutable: String = "/usr/sbin/openvpn",
    val temporaryDirectory: String = "/tmp",
) {
    companion object {
        private const val MIN_CONFIG_REQUEST_INTERVAL_MS = 1_100L
        private const val MAX_PROBE_TIMEOUT_SECONDS = 600L

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ProberConfig {
            val dryRun = environment.boolean("PROBER_DRY_RUN", false)
            val baseUrl = environment.valueOrDefault("PROBER_API_BASE_URL", "http://api:8080")
                .trimEnd('/')
            validateBaseUrl(baseUrl)

            val sharedSecret = environment["PROBER_SHARED_SECRET"]?.takeIf(String::isNotBlank)
            require(dryRun || sharedSecret != null) {
                "PROBER_SHARED_SECRET is required unless PROBER_DRY_RUN=true"
            }
            require(sharedSecret == null || ('\r' !in sharedSecret && '\n' !in sharedSecret)) {
                "PROBER_SHARED_SECRET must not contain line breaks"
            }

            val configRequestIntervalMs = environment.longInRange(
                key = "PROBER_CONFIG_REQUEST_INTERVAL_MS",
                default = MIN_CONFIG_REQUEST_INTERVAL_MS,
                range = MIN_CONFIG_REQUEST_INTERVAL_MS..600_000L,
            )

            return ProberConfig(
                apiBaseUrl = baseUrl,
                sharedSecret = sharedSecret,
                sweepInterval = environment.longInRange(
                    key = "PROBER_SWEEP_INTERVAL_SECONDS",
                    default = 900,
                    range = 1L..86_400L,
                ).seconds,
                initialJitter = environment.longInRange(
                    key = "PROBER_INITIAL_JITTER_SECONDS",
                    default = 30,
                    range = 0L..3_600L,
                ).seconds,
                concurrency = environment.intInRange("PROBER_CONCURRENCY", 2, 1..4),
                probeTimeout = environment.longInRange(
                    key = "PROBER_PROBE_TIMEOUT_SECONDS",
                    default = 35,
                    range = 1..MAX_PROBE_TIMEOUT_SECONDS,
                ).seconds,
                configRequestInterval = configRequestIntervalMs.milliseconds,
                maxServersPerSweep = environment.intInRange(
                    key = "PROBER_MAX_SERVERS_PER_SWEEP",
                    default = 25,
                    range = 1..200,
                ),
                runOnce = environment.boolean("PROBER_RUN_ONCE", false),
                dryRun = dryRun,
            )
        }

        private fun validateBaseUrl(raw: String) {
            val uri = runCatching { URI(raw) }
                .getOrElse { error("PROBER_API_BASE_URL must be a valid URI") }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "PROBER_API_BASE_URL must use http or https and include a host"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "PROBER_API_BASE_URL must not include credentials, a query, or a fragment"
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") {
                "PROBER_API_BASE_URL must not include a path"
            }
        }
    }
}

private fun Map<String, String>.valueOrDefault(key: String, default: String): String =
    this[key]?.trim()?.takeIf(String::isNotEmpty) ?: default

private fun Map<String, String>.longInRange(key: String, default: Long, range: LongRange): Long {
    val raw = this[key]?.trim()?.takeIf(String::isNotEmpty) ?: return default
    val value = raw.toLongOrNull() ?: error("$key must be an integer")
    require(value in range) { "$key must be between ${range.first} and ${range.last}" }
    return value
}

private fun Map<String, String>.intInRange(key: String, default: Int, range: IntRange): Int {
    val raw = this[key]?.trim()?.takeIf(String::isNotEmpty) ?: return default
    val value = raw.toIntOrNull() ?: error("$key must be an integer")
    require(value in range) { "$key must be between ${range.first} and ${range.last}" }
    return value
}

private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean {
    val raw = this[key]?.trim()?.takeIf(String::isNotEmpty) ?: return default
    return when (raw.lowercase()) {
        "true" -> true
        "false" -> false
        else -> error("$key must be true or false")
    }
}
