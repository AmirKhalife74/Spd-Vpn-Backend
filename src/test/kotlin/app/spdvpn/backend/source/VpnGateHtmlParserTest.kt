package app.spdvpn.backend.source

import app.spdvpn.backend.softEtherCountryCellFallbackHtmlFixture
import app.spdvpn.backend.softEtherHtmlFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VpnGateHtmlParserTest {
    private val parser = VpnGateHtmlParser()

    @Test
    fun `parses native ports and missing support from every duplicate-id table`() {
        val result = parser.parse(softEtherHtmlFixture())

        assertEquals(8, result.rowsInspected)
        assertEquals(2, result.ignoredNonServerRows)
        assertEquals(2, result.malformedRows)
        assertEquals(443, result.server("198.51.100.10").softEtherTcpPort)
        assertEquals(995, result.server("203.0.113.20").softEtherTcpPort)
        assertEquals(1865, result.server("192.0.2.77").softEtherTcpPort)
        assertEquals("vpn-host-match.opengw.net", result.server("192.0.2.77").hostName)

        val unsupported = result.server("192.0.2.30")
        assertNull(unsupported.softEtherTcpPort)
        assertEquals(SoftEtherPortState.MISSING, unsupported.portState)
    }

    @Test
    fun `rejects malformed and out-of-range ports without inventing a fallback`() {
        val result = parser.parse(softEtherHtmlFixture())

        listOf("192.0.2.40", "192.0.2.41").forEach { ip ->
            val server = result.server(ip)
            assertNull(server.softEtherTcpPort)
            assertEquals(SoftEtherPortState.MALFORMED, server.portState)
        }
    }

    @Test
    fun `classifies headers and non-server rows separately from malformed server rows`() {
        val result = parser.parse(
            """
            <table id="vg_hosts_table_id">
              <tr><th>Country</th><th>DDNS hostname / IP</th><th>Sessions</th><th>Quality</th><th>SSL-VPN</th></tr>
              <tr><td colspan="5">Maintenance notice</td></tr>
              <tr><td>Japan</td><td>not a valid server / 999.999.999.999</td><td>3</td><td>fast</td><td>TCP: 443</td></tr>
              <tr><td>Japan</td><td>short.opengw.net / 192.0.2.9</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals(4, result.rowsInspected)
        assertEquals(2, result.ignoredNonServerRows)
        assertEquals(2, result.malformedRows)
        assertEquals(0, result.servers.size)
    }

    @Test
    fun `requires the VPN Gate hosts table`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse("<html><body><table id=other></table></body></html>")
        }
    }

    @Test
    fun `fallback reads hostname and IP from td one instead of country td zero`() {
        val result = parser.parse(softEtherCountryCellFallbackHtmlFixture())

        val server = result.servers.single()
        assertEquals("fallback-host.opengw.net", server.hostName)
        assertEquals("114.200.11.167", server.ip)
        assertEquals(995, server.softEtherTcpPort)
        assertEquals(2, result.rowsInspected)
        assertEquals(1, result.ignoredNonServerRows)
        assertEquals(0, result.malformedRows)
    }

    private fun VpnGateHtmlParseResult.server(ip: String): VpnGateHtmlServerMetadata =
        servers.single { it.ip == ip }
}
