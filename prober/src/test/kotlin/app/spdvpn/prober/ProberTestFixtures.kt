package app.spdvpn.prober

import app.spdvpn.prober.api.ApiInfrastructureException
import app.spdvpn.prober.api.ProbeApi
import app.spdvpn.prober.config.ProberConfig
import app.spdvpn.prober.model.ProbeResultRequest
import app.spdvpn.prober.model.ProbeServer
import app.spdvpn.prober.time.ProberClock
import app.spdvpn.prober.time.SuspendSleeper
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeClock(
    initial: Long = 1_786_870_000_000L,
) : ProberClock {
    @Volatile
    private var current = initial

    override fun nowEpochMillis(): Long = current

    @Synchronized
    fun advance(millis: Long) {
        current += millis
    }
}

class AdvancingSleeper(
    private val clock: FakeClock,
) : SuspendSleeper {
    val delays = Collections.synchronizedList(mutableListOf<Long>())

    override suspend fun sleep(millis: Long) {
        delays += millis
        clock.advance(millis)
    }
}

class FakeProbeApi(
    var servers: List<ProbeServer>,
    private val profiles: Map<String, String> = servers.associate { it.id to SAFE_PROFILE },
) : ProbeApi {
    val reports = Collections.synchronizedList(mutableListOf<ProbeResultRequest>())
    val configRequests = Collections.synchronizedList(mutableListOf<String>())
    var listFailure: ApiInfrastructureException? = null
    val configFailures = mutableMapOf<String, ApiInfrastructureException>()
    var reportFailure: ApiInfrastructureException? = null

    override suspend fun fetchServers(): List<ProbeServer> {
        listFailure?.let { throw it }
        return servers
    }

    override suspend fun fetchServerConfig(serverId: String): String {
        configRequests += serverId
        configFailures[serverId]?.let { throw it }
        return profiles.getValue(serverId)
    }

    override suspend fun submitProbeResult(result: ProbeResultRequest) {
        reportFailure?.let { throw it }
        reports += result
    }
}

fun testConfig(
    concurrency: Int = 2,
    dryRun: Boolean = false,
    runOnce: Boolean = true,
    maxServers: Int = 25,
) = ProberConfig(
    apiBaseUrl = "http://api:8080",
    sharedSecret = if (dryRun) null else "test-secret",
    sweepInterval = 900.seconds,
    initialJitter = 0.seconds,
    concurrency = concurrency,
    probeTimeout = 35.seconds,
    configRequestInterval = 1_100.milliseconds,
    maxServersPerSweep = maxServers,
    runOnce = runOnce,
    dryRun = dryRun,
)

const val SAFE_PROFILE = """client
proto udp
remote 203.0.113.8 1194
auth-user-pass supplied-by-profile.txt
<ca>
-----BEGIN CERTIFICATE-----
ZmFrZQ==
-----END CERTIFICATE-----
</ca>
"""
