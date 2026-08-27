package app.spdvpn.backend.service

import app.spdvpn.backend.cache.InMemoryCatalogCacheStore
import app.spdvpn.backend.model.VpnProtocol
import app.spdvpn.backend.source.VpnGateCsvParser
import app.spdvpn.backend.source.VpnGateHtmlSource
import app.spdvpn.backend.source.VpnGateSource
import app.spdvpn.backend.softEtherHtmlFixture
import app.spdvpn.backend.vpnGateCsv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CatalogServiceTest {
    @Test
    fun `failed refresh keeps the previous good catalog`() = runBlocking {
        var response = vpnGateCsv(firstScore = 1_000)
        val service = service(source = { response })

        assertIs<RefreshResult.Success>(service.refresh())
        val original = service.currentSnapshot()
        response = "upstream failure"

        assertIs<RefreshResult.Failure>(service.refresh())
        assertEquals(original, service.currentSnapshot())
        assertEquals("degraded", service.health().status)
    }

    @Test
    fun `filters by country and protocol then sorts by ping`() = runBlocking {
        val service = service(source = { vpnGateCsv() })
        service.refresh()

        val result = service.queryServers(
            ServerQuery(
                countryCode = "JP",
                protocol = VpnProtocol.UDP,
                sort = ServerSort.PING,
                order = SortOrder.ASC,
            ),
        )

        assertEquals(1, result.total)
        assertEquals("vpn-udp", result.servers.single().hostName)
        assertTrue(service.countries().any { it.code == "DE" })
    }

    @Test
    fun `enriches by IP once per refresh without changing OpenVPN fields`() = runBlocking {
        val csv = vpnGateCsv()
        val parser = VpnGateCsvParser()
        val before = parser.parse(csv).servers.single { it.hostName == "vpn-tcp" }
        var htmlFetches = 0
        val service = service(
            source = { csv },
            htmlSource = VpnGateHtmlSource {
                htmlFetches++
                softEtherHtmlFixture()
            },
        )

        assertIs<RefreshResult.Success>(service.refresh())

        val after = service.currentSnapshot().servers.single { it.hostName == "vpn-tcp" }
        assertEquals(1, htmlFetches)
        assertEquals(995, after.softEtherTcpPort)
        assertEquals("vpn-tcp.opengw.net", after.softEtherTlsServerName)
        assertEquals(before.id, after.id)
        assertEquals(before.hostName, after.hostName)
        assertEquals(before.ip, after.ip)
        assertEquals(before.protocol, after.protocol)
        assertEquals(before.port, after.port)
        assertEquals(before.openVpnConfig, after.openVpnConfig)
    }

    @Test
    fun `old cached snapshots gain canonical TLS metadata without losing servers`() = runBlocking {
        val csv = vpnGateCsv(
            includeTcp = false,
            firstHostName = "public-vpn-39",
            firstIp = "219.100.37.3",
        )
        val oldSnapshot = VpnGateCsvParser().parse(csv).let { parsed ->
            parsed.copy(
                schemaVersion = 2,
                servers = parsed.servers.map {
                    it.copy(softEtherTcpPort = 443, softEtherTlsServerName = null)
                },
            )
        }
        val cacheStore = InMemoryCatalogCacheStore(oldSnapshot)
        val service = service(
            source = { error("refresh must not be required") },
            cacheStore = cacheStore,
        )

        service.loadLastKnownGood()

        val loaded = service.currentSnapshot()
        assertEquals(3, loaded.schemaVersion)
        assertEquals(1, loaded.servers.size)
        assertEquals(443, loaded.servers.single().softEtherTcpPort)
        assertEquals("public-vpn-39.opengw.net", loaded.servers.single().softEtherTlsServerName)
        assertEquals(loaded, cacheStore.load())
    }

    @Test
    fun `invalid TLS hostname disables only SoftEther metadata`() = runBlocking {
        val csv = vpnGateCsv(
            includeTcp = false,
            firstHostName = "vpn.example.com",
            firstIp = "198.51.100.10",
        )
        val service = service(
            source = { csv },
            htmlSource = VpnGateHtmlSource { softEtherHtmlFixture() },
        )

        assertIs<RefreshResult.Success>(service.refresh())

        val server = service.currentSnapshot().servers.single()
        assertNull(server.softEtherTcpPort)
        assertNull(server.softEtherTlsServerName)
        assertTrue(server.openVpnConfig.isNotBlank())
    }

    @Test
    fun `uses normalized hostname only when IP does not match`() = runBlocking {
        val csv = vpnGateCsv(
            includeTcp = false,
            firstHostName = "vpn-host-match.opengw.net",
            firstIp = "198.51.100.77",
        )
        val service = service(
            source = { csv },
            htmlSource = VpnGateHtmlSource { softEtherHtmlFixture() },
        )

        assertIs<RefreshResult.Success>(service.refresh())

        assertEquals(1865, service.currentSnapshot().servers.single().softEtherTcpPort)
    }

    @Test
    fun `keeps all CSV servers when SoftEther support is missing`() = runBlocking {
        val csv = vpnGateCsv(
            includeTcp = false,
            firstHostName = "no-softether.example.test",
            firstIp = "192.0.2.30",
        )
        val service = service(
            source = { csv },
            htmlSource = VpnGateHtmlSource { softEtherHtmlFixture() },
        )

        assertIs<RefreshResult.Success>(service.refresh())

        assertEquals(1, service.currentSnapshot().servers.size)
        assertNull(service.currentSnapshot().servers.single().softEtherTcpPort)
    }

    @Test
    fun `HTML failure retains safely matched cached metadata while CSV refresh succeeds`() = runBlocking {
        val csv = vpnGateCsv(
            includeTcp = false,
            firstHostName = "vpn-udp",
        )
        val cached = VpnGateCsvParser().parse(csv).let { snapshot ->
            snapshot.copy(
                servers = snapshot.servers.map { it.copy(softEtherTcpPort = 443) },
            )
        }
        val cacheStore = InMemoryCatalogCacheStore(cached)
        val service = service(
            source = { csv },
            htmlSource = VpnGateHtmlSource { error("HTML unavailable") },
            cacheStore = cacheStore,
        )
        service.loadLastKnownGood()

        assertIs<RefreshResult.Success>(service.refresh())

        assertEquals(443, service.currentSnapshot().servers.single().softEtherTcpPort)
        assertEquals("vpn-udp.opengw.net", service.currentSnapshot().servers.single().softEtherTlsServerName)
        assertEquals("healthy", service.health().status)
    }

    private fun service(
        source: suspend () -> String,
        htmlSource: VpnGateHtmlSource? = null,
        cacheStore: InMemoryCatalogCacheStore = InMemoryCatalogCacheStore(),
    ) = CatalogService(
        source = VpnGateSource(source),
        parser = VpnGateCsvParser(),
        cacheStore = cacheStore,
        refreshInterval = 5.minutes,
        maxStaleAge = 5.minutes,
        htmlSource = htmlSource,
    )
}
