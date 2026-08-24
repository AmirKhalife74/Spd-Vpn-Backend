package app.spdvpn.backend.api

import app.spdvpn.backend.applicationJson
import app.spdvpn.backend.cache.InMemoryCatalogCacheStore
import app.spdvpn.backend.config.AppConfig
import app.spdvpn.backend.configureApplication
import app.spdvpn.backend.health.InMemoryServerHealthSnapshotStore
import app.spdvpn.backend.health.ServerHealthTracker
import app.spdvpn.backend.model.ApiResponse
import app.spdvpn.backend.model.ProbeResult
import app.spdvpn.backend.model.ServerHealthStatus
import app.spdvpn.backend.model.ServersData
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateSource
import app.spdvpn.backend.vpnGateCsv
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProbeResultsApiTest {
    @Test
    fun `missing and invalid internal tokens are rejected`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }
        val serverId = service.currentSnapshot().servers.first().id
        val body = probeJson(success(serverId))

        val missing = client.post(PROBE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val invalid = client.post(PROBE_PATH) {
            contentType(ContentType.Application.Json)
            header(PROBER_TOKEN_HEADER, "wrong-secret")
            setBody(body)
        }

        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertFalse(missing.bodyAsText().contains(SHARED_SECRET))
        assertFalse(invalid.bodyAsText().contains(SHARED_SECRET))
    }

    @Test
    fun `disabled internal endpoint returns not found`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig(secret = null)) }

        val response = client.post(PROBE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(probeJson(success(service.currentSnapshot().servers.first().id)))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `unknown server id is rejected`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }

        val response = client.postProbe(probeJson(success("unknown-server")))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `valid result updates the public server response`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }
        val serverId = service.currentSnapshot().servers.first { it.hostName == "vpn-udp" }.id
        val before = client.get("/v1/servers?q=vpn-udp")
        val previousEtag = before.headers[HttpHeaders.ETag]

        val report = client.postProbe(probeJson(success(serverId, durationMs = 2_450L)))
        val publicResponse = client.get("/v1/servers?q=vpn-udp") {
            header(HttpHeaders.IfNoneMatch, previousEtag.orEmpty())
        }
        val servers = decodeServers(publicResponse.bodyAsText())
        val server = servers.data.servers.single()

        assertEquals(HttpStatusCode.OK, report.status)
        assertEquals(HttpStatusCode.OK, publicResponse.status)
        assertFalse(report.bodyAsText().contains("<ca>"))
        assertEquals(ServerHealthStatus.HEALTHY, server.healthStatus)
        assertEquals(NOW, server.lastCheckedAtEpochMillis)
        assertEquals(NOW, server.lastSuccessfulAtEpochMillis)
        assertEquals(2_450L, server.lastProbeDurationMs)
        assertEquals(2_450L, server.lastSuccessfulConnectionTimeMs)
        assertEquals(0, server.consecutiveFailures)
        assertFalse(publicResponse.bodyAsText().contains("probeLatencyMs"))
        assertFalse(publicResponse.bodyAsText().contains("latencyMs"))
    }

    @Test
    fun `health filtering works and invalid values use bad request response`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }
        val healthyId = service.currentSnapshot().servers.first { it.hostName == "vpn-udp" }.id
        client.postProbe(probeJson(success(healthyId)))

        val healthy = decodeServers(client.get("/v1/servers?health=healthy").bodyAsText())
        val unknown = decodeServers(client.get("/v1/servers?health=unknown").bodyAsText())
        val invalid = client.get("/v1/servers?health=broken")

        assertEquals(listOf("vpn-udp"), healthy.data.servers.map { it.hostName })
        assertEquals(listOf("vpn-tcp"), unknown.data.servers.map { it.hostName })
        assertEquals(ServerHealthStatus.UNKNOWN, unknown.data.servers.single().healthStatus)
        assertNull(unknown.data.servers.single().lastCheckedAtEpochMillis)
        assertNull(unknown.data.servers.single().lastSuccessfulAtEpochMillis)
        assertNull(unknown.data.servers.single().lastProbeDurationMs)
        assertNull(unknown.data.servers.single().lastSuccessfulConnectionTimeMs)
        assertEquals(0, unknown.data.servers.single().consecutiveFailures)
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertTrue(invalid.bodyAsText().contains("BAD_REQUEST"))
    }

    @Test
    fun `default health ranking preserves the requested secondary sort`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }
        val udp = service.currentSnapshot().servers.first { it.hostName == "vpn-udp" }
        val tcp = service.currentSnapshot().servers.first { it.hostName == "vpn-tcp" }

        repeat(3) { index ->
            client.postProbe(probeJson(failure(udp.id, checkedAt = NOW - 3_000L + index * 1_000L)))
        }
        val ranked = decodeServers(client.get("/v1/servers?sort=score&order=desc").bodyAsText())
        assertEquals(listOf("vpn-tcp", "vpn-udp"), ranked.data.servers.map { it.hostName })

        client.postProbe(probeJson(success(udp.id, checkedAt = NOW - 500L)))
        client.postProbe(probeJson(success(tcp.id, checkedAt = NOW)))
        val secondary = decodeServers(client.get("/v1/servers?sort=ping&order=asc").bodyAsText())
        assertEquals(listOf("vpn-udp", "vpn-tcp"), secondary.data.servers.map { it.hostName })
    }

    @Test
    fun `failed duration does not overwrite successful connection time in public response`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }
        val serverId = service.currentSnapshot().servers.first { it.hostName == "vpn-udp" }.id
        client.postProbe(probeJson(success(serverId, checkedAt = NOW - 1_000L, durationMs = 180L)))
        client.postProbe(probeJson(failure(serverId, checkedAt = NOW, durationMs = 1_500L)))

        val publicResponse = decodeServers(client.get("/v1/servers?q=vpn-udp").bodyAsText())
        val server = publicResponse.data.servers.single()

        assertEquals(ServerHealthStatus.DEGRADED, server.healthStatus)
        assertEquals(1_500L, server.lastProbeDurationMs)
        assertEquals(180L, server.lastSuccessfulConnectionTimeMs)
        assertEquals(NOW - 1_000L, server.lastSuccessfulAtEpochMillis)
    }

    @Test
    fun `oversized and invalid probe bodies are rejected`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }
        val serverId = service.currentSnapshot().servers.first().id
        val oversized = client.post(PROBE_PATH) {
            contentType(ContentType.Application.Json)
            header(PROBER_TOKEN_HEADER, SHARED_SECRET)
            setBody("x".repeat(4_097))
        }
        val invalidStage = client.postProbe(
            probeJson(success(serverId).copy(stage = "connected")),
        )
        val excessiveStage = client.postProbe(
            probeJson(failure(serverId, checkedAt = NOW).copy(stage = "A".repeat(65))),
        )
        val nonConnectedSuccess = client.postProbe(
            probeJson(success(serverId).copy(stage = "HANDSHAKE")),
        )
        val invalidDuration = client.postProbe(
            probeJson(success(serverId).copy(durationMs = -1L)),
        )
        val excessiveDuration = client.postProbe(
            probeJson(success(serverId).copy(durationMs = 600_001L)),
        )
        val excessiveReason = client.postProbe(
            probeJson(failure(serverId, checkedAt = NOW).copy(reason = "x".repeat(513))),
        )
        val invalidTimestamp = client.postProbe(
            probeJson(success(serverId).copy(checkedAtEpochMillis = NOW + 10 * 60 * 1_000L)),
        )

        assertEquals(HttpStatusCode.BadRequest, oversized.status)
        assertEquals(HttpStatusCode.BadRequest, invalidStage.status)
        assertEquals(HttpStatusCode.BadRequest, excessiveStage.status)
        assertEquals(HttpStatusCode.BadRequest, nonConnectedSuccess.status)
        assertEquals(HttpStatusCode.BadRequest, invalidDuration.status)
        assertEquals(HttpStatusCode.BadRequest, excessiveDuration.status)
        assertEquals(HttpStatusCode.BadRequest, excessiveReason.status)
        assertEquals(HttpStatusCode.BadRequest, invalidTimestamp.status)
    }

    private suspend fun readyService(): CatalogService {
        val tracker = ServerHealthTracker(
            snapshotStore = InMemoryServerHealthSnapshotStore(),
            clock = { NOW },
        )
        val service = CatalogService(
            source = VpnGateSource { vpnGateCsv() },
            parser = VpnGateCsvParser(clock = { NOW }),
            cacheStore = InMemoryCatalogCacheStore(),
            refreshInterval = 5.minutes,
            maxStaleAge = 5.minutes,
            clock = { NOW },
            healthTracker = tracker,
        )
        service.refresh()
        return service
    }

    private fun testConfig(secret: String? = SHARED_SECRET) = AppConfig(
        sourceUrl = "https://example.test/vpngate.csv",
        refreshInterval = 5.minutes,
        fetchTimeout = 5.seconds,
        cachePath = Path.of("unused-test-cache.json"),
        maxStaleAge = 5.minutes,
        rateLimitRequests = 1_000,
        rateLimitWindow = 60.seconds,
        corsAllowedHosts = listOf("*"),
        healthSnapshotPath = Path.of("unused-health-test-cache.json"),
        proberSharedSecret = secret,
    )

    private fun success(
        serverId: String,
        checkedAt: Long = NOW,
        durationMs: Long = 200L,
    ) = ProbeResult(
        serverId = serverId,
        success = true,
        stage = "CONNECTED",
        durationMs = durationMs,
        checkedAtEpochMillis = checkedAt,
    )

    private fun failure(
        serverId: String,
        checkedAt: Long,
        durationMs: Long = 1_000L,
    ) = ProbeResult(
        serverId = serverId,
        success = false,
        stage = "CONNECT",
        reason = "timeout",
        durationMs = durationMs,
        checkedAtEpochMillis = checkedAt,
    )

    private fun probeJson(result: ProbeResult): String = applicationJson().encodeToString(result)

    private fun decodeServers(body: String): ApiResponse<ServersData> = applicationJson().decodeFromString(body)

    private suspend fun HttpClient.postProbe(body: String): HttpResponse = post(PROBE_PATH) {
        contentType(ContentType.Application.Json)
        header(PROBER_TOKEN_HEADER, SHARED_SECRET)
        setBody(body)
    }

    private companion object {
        private const val PROBE_PATH = "/internal/v1/probe-results"
        private const val PROBER_TOKEN_HEADER = "X-SpdVpn-Prober-Token"
        private const val SHARED_SECRET = "test-prober-secret"
        private const val NOW = 1_786_870_000_000L
    }
}
