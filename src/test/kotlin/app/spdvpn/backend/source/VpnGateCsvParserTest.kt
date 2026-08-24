package app.spdvpn.backend.source

import app.spdvpn.backend.vpnGateCsv
import app.spdvpn.backend.model.VpnProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VpnGateCsvParserTest {
    private val parser = VpnGateCsvParser(clock = { 123_456L })

    @Test
    fun `parses quoted fields and OpenVPN connection details`() {
        val snapshot = parser.parse(vpnGateCsv())

        assertEquals(2, snapshot.servers.size)
        assertEquals(123_456L, snapshot.fetchedAtEpochMillis)
        assertEquals(0, snapshot.rejectedRows)

        val udp = snapshot.servers.first { it.hostName == "vpn-udp" }
        assertEquals("Operator, One", udp.operator)
        assertEquals(VpnProtocol.UDP, udp.protocol)
        assertEquals(1194, udp.port)
        assertTrue(udp.openVpnConfig.contains("proto udp"))

        val tcp = snapshot.servers.first { it.hostName == "vpn-tcp" }
        assertEquals(VpnProtocol.TCP, tcp.protocol)
        assertEquals(443, tcp.port)
    }

    @Test
    fun `rejects an invalid response instead of creating an empty cache`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse("temporary upstream error")
        }
    }

    @Test
    fun `rfc parser supports escaped quotes and newlines inside fields`() {
        val rows = parseRfc4180("a,\"hello \"\"SPD\"\"\nVPN\"\r\n1,2")

        assertEquals(listOf("a", "hello \"SPD\"\nVPN"), rows[0])
        assertEquals(listOf("1", "2"), rows[1])
    }
}
