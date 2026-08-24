package app.spdvpn.backend.source

import org.jsoup.Jsoup
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class VpnGateHtmlParser {
    fun parse(html: String): VpnGateHtmlParseResult {
        val document = Jsoup.parse(html)
        val tables = document.getElementsByAttributeValue("id", HOSTS_TABLE_ID)
            .filter { it.tagName().equals("table", ignoreCase = true) }
        require(tables.isNotEmpty()) { "VPN Gate server tables were not found" }

        var rowsInspected = 0
        var ignoredNonServerRows = 0
        var malformedRows = 0
        val servers = buildList {
            tables.forEach { table ->
                table.getElementsByTag("tr").forEach rowLoop@{ row ->
                    rowsInspected++
                    val cells = row.children().filter { it.tagName().equals("td", ignoreCase = true) }
                    val openVpnLink = row.selectFirst(OPENVPN_LINK_SELECTOR)
                    val queryParameters = openVpnLink
                        ?.attr("href")
                        ?.let(::parseQueryParameters)
                        .orEmpty()
                    val identityCellText = cells.getOrNull(IDENTITY_CELL_INDEX)?.text().orEmpty()
                    val hasIdentityHint = hasIdentityHint(queryParameters, identityCellText)

                    if (cells.size <= SOFTETHER_CELL_INDEX) {
                        if (hasIdentityHint) {
                            malformedRows++
                        } else {
                            ignoredNonServerRows++
                        }
                        return@rowLoop
                    }

                    val softEtherText = cells[SOFTETHER_CELL_INDEX].text()
                    val looksLikeServerRow = hasIdentityHint ||
                        openVpnLink != null ||
                        TCP_LABEL_PATTERN.containsMatchIn(softEtherText)
                    val hostName = normalizeVpnGateHostName(queryParameters["fqdn"])
                        ?: extractHostName(identityCellText)
                    val ip = normalizeIpv4(queryParameters["ip"])
                        ?: extractIpv4(identityCellText)

                    if (hostName == null || ip == null) {
                        if (looksLikeServerRow) {
                            malformedRows++
                        } else {
                            ignoredNonServerRows++
                        }
                        return@rowLoop
                    }

                    val portMatch = TCP_PORT_PATTERN.find(softEtherText)
                    val parsedPort = portMatch?.groupValues?.get(1)?.toIntOrNull()
                    val portState = when {
                        portMatch == null && !TCP_LABEL_PATTERN.containsMatchIn(softEtherText) -> {
                            SoftEtherPortState.MISSING
                        }

                        parsedPort != null && parsedPort in VALID_PORT_RANGE -> SoftEtherPortState.KNOWN
                        else -> {
                            malformedRows++
                            SoftEtherPortState.MALFORMED
                        }
                    }

                    add(
                        VpnGateHtmlServerMetadata(
                            hostName = hostName,
                            ip = ip,
                            softEtherTcpPort = parsedPort?.takeIf { it in VALID_PORT_RANGE },
                            portState = portState,
                        ),
                    )
                }
            }
        }

        return VpnGateHtmlParseResult(
            rowsInspected = rowsInspected,
            ignoredNonServerRows = ignoredNonServerRows,
            malformedRows = malformedRows,
            servers = servers,
        )
    }

    private fun hasIdentityHint(queryParameters: Map<String, String>, identityCellText: String): Boolean =
        queryParameters["fqdn"].isNullOrBlank().not() ||
            queryParameters["ip"].isNullOrBlank().not() ||
            IPV4_CANDIDATE_PATTERN.containsMatchIn(identityCellText) ||
            HOST_NAME_PATTERN.containsMatchIn(identityCellText) ||
            identityCellText.contains(".opengw.net", ignoreCase = true)

    private fun extractHostName(text: String): String? = HOST_NAME_PATTERN.findAll(text)
        .mapNotNull { normalizeVpnGateHostName(it.value) }
        .toList()
        .let { candidates ->
            candidates.firstOrNull { it.endsWith(".opengw.net") } ?: candidates.firstOrNull()
        }

    private fun extractIpv4(text: String): String? = IPV4_CANDIDATE_PATTERN.findAll(text)
        .mapNotNull { normalizeIpv4(it.value) }
        .firstOrNull()

    private fun parseQueryParameters(href: String): Map<String, String> = href
        .substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .mapNotNull { parameter ->
            val separator = parameter.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = decodeQueryValue(parameter.substring(0, separator)).lowercase(Locale.ROOT)
            val value = decodeQueryValue(parameter.substring(separator + 1))
            key to value
        }
        .toMap()

    private fun decodeQueryValue(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)

    private companion object {
        const val HOSTS_TABLE_ID = "vg_hosts_table_id"
        const val IDENTITY_CELL_INDEX = 1
        const val SOFTETHER_CELL_INDEX = 4
        const val OPENVPN_LINK_SELECTOR = "a[href^=\"do_openvpn.aspx?\"]"
        val VALID_PORT_RANGE = 1..65_535
        val TCP_PORT_PATTERN = Regex("TCP:\\s*(\\d+)")
        val TCP_LABEL_PATTERN = Regex("TCP:\\s*")
        val IPV4_CANDIDATE_PATTERN = Regex("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)")
        val HOST_NAME_PATTERN = Regex(
            "(?i)(?<![a-z0-9-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}\\.?(?![a-z0-9-])",
        )
    }
}

data class VpnGateHtmlParseResult(
    val rowsInspected: Int,
    val ignoredNonServerRows: Int,
    val malformedRows: Int,
    val servers: List<VpnGateHtmlServerMetadata>,
)

data class VpnGateHtmlServerMetadata(
    val hostName: String?,
    val ip: String?,
    val softEtherTcpPort: Int?,
    val portState: SoftEtherPortState,
)

enum class SoftEtherPortState {
    KNOWN,
    MISSING,
    MALFORMED,
}

internal fun normalizeVpnGateHostName(value: String?): String? {
    val normalized = value?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT).orEmpty()
    if (normalized.isEmpty() || normalized.length > 253) return null
    val labels = normalized.split('.')
    if (labels.size < 2) return null
    if (labels.any { label ->
            label.isEmpty() ||
                label.length > 63 ||
                label.first() == '-' ||
                label.last() == '-' ||
                label.any { !it.isLetterOrDigit() && it != '-' }
        }
    ) {
        return null
    }
    return normalized
}

internal fun normalizeIpv4(value: String?): String? {
    val parts = value?.trim()?.split('.') ?: return null
    if (parts.size != 4) return null
    val octets = parts.map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    return octets.joinToString(".")
}
