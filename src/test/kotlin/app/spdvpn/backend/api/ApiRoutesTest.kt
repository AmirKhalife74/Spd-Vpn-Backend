package app.spdvpn.backend.api

import app.spdvpn.backend.applicationJson
import app.spdvpn.backend.cache.InMemoryCatalogCacheStore
import app.spdvpn.backend.config.AppConfig
import app.spdvpn.backend.configureApplication
import app.spdvpn.backend.model.ApiResponse
import app.spdvpn.backend.model.ServerConfigData
import app.spdvpn.backend.model.ServersData
import app.spdvpn.backend.service.CatalogService
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateHtmlSource
import app.spdvpn.backend.source.VpnGateSource
import app.spdvpn.backend.softEtherHtmlFixture
import app.spdvpn.backend.vpnGateCsv
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ApiRoutesTest {
    @Test
    fun `lists summaries and returns config from a separate endpoint`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }

        val listResponse = client.get("/v1/servers?country=JP&protocol=udp")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listJson = listResponse.bodyAsText()
        val list = applicationJson().decodeFromString<ApiResponse<ServersData>>(listJson)
        assertEquals(1, list.data.page.total)
        assertEquals("vpn-udp", list.data.servers.single().hostName)
        assertFalse(listJson.contains("<ca>"))

        val id = list.data.servers.single().id
        val configResponse = client.get("/v1/servers/$id/config")
        val config = applicationJson().decodeFromString<ApiResponse<ServerConfigData>>(configResponse.bodyAsText())
        assertTrue(config.data.openVpnConfig.contains("remote 198.51.100.10 1194"))
    }

    @Test
    fun `validates pagination and supports conditional requests`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/servers?limit=0").status)

        val first = client.get("/v1/countries")
        val etag = first.headers[HttpHeaders.ETag]
        assertNotNull(etag)
        val second = client.get("/v1/countries") {
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
    }

    @Test
    fun `server listing exposes the native SoftEther TCP port`() = testApplication {
        val service = readyService()
        application { configureApplication(service, testConfig()) }

        val response = client.get("/v1/servers?country=DE&protocol=tcp")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val decoded = applicationJson().decodeFromString<ApiResponse<ServersData>>(body)

        assertEquals(995, decoded.data.servers.single().softEtherTcpPort)
        assertTrue(body.contains("\"softEtherTcpPort\":995"))
    }

    private suspend fun readyService(): CatalogService {
        val service = CatalogService(
            source = VpnGateSource { vpnGateCsv() },
            parser = VpnGateCsvParser(),
            cacheStore = InMemoryCatalogCacheStore(),
            refreshInterval = 5.minutes,
            maxStaleAge = 5.minutes,
            htmlSource = VpnGateHtmlSource { softEtherHtmlFixture() },
        )
        service.refresh()
        return service
    }

    private fun testConfig() = AppConfig(
        sourceUrl = "https://example.test/vpngate.csv",
        refreshInterval = 5.minutes,
        fetchTimeout = 5.seconds,
        cachePath = Path.of("unused-test-cache.json"),
        maxStaleAge = 5.minutes,
        rateLimitRequests = 1_000,
        rateLimitWindow = 60.seconds,
        corsAllowedHosts = listOf("*"),
    )
}
