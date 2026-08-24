package app.spdvpn.backend

import java.util.Base64

fun vpnGateCsv(
    firstScore: Long = 1_000,
    includeTcp: Boolean = true,
    firstHostName: String = "vpn-udp",
    firstIp: String = "198.51.100.10",
    secondHostName: String = "vpn-tcp",
    secondIp: String = "203.0.113.20",
): String {
    val udpConfig = encodeConfig(
        """
        client
        dev tun
        proto udp
        remote $firstIp 1194
        <ca>
        test-ca
        </ca>
        """.trimIndent(),
    )
    val tcpConfig = encodeConfig(
        """
        client
        dev tun
        proto tcp-client
        remote $secondIp 443 tcp-client
        <ca>
        test-ca
        </ca>
        """.trimIndent(),
    )

    return buildString {
        appendLine("*vpn_servers")
        appendLine("#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64")
        appendLine("$firstHostName,$firstIp,$firstScore,20,1000000,Japan,JP,10,500000,100,999999,2weeks,\"Operator, One\",Fast server,$udpConfig")
        if (includeTcp) {
            appendLine("$secondHostName,$secondIp,900,50,500000,Germany,DE,5,250000,50,555555,2weeks,Operator Two,Backup,$tcpConfig")
        }
        append('*')
    }
}

fun softEtherHtmlFixture(): String = checkNotNull(
    TestFixtureAnchor::class.java.getResource("/fixtures/vpngate-softether.html"),
) { "SoftEther HTML fixture was not found" }.readText()

fun softEtherCountryCellFallbackHtmlFixture(): String = checkNotNull(
    TestFixtureAnchor::class.java.getResource("/fixtures/vpngate-softether-country-cell.html"),
) { "SoftEther country-cell fallback fixture was not found" }.readText()

private object TestFixtureAnchor

private fun encodeConfig(config: String): String =
    Base64.getEncoder().encodeToString(config.toByteArray(Charsets.UTF_8))
