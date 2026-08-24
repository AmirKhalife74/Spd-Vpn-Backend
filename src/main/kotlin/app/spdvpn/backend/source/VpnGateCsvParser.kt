package app.spdvpn.backend.source

import app.spdvpn.backend.model.CatalogSnapshot
import app.spdvpn.backend.model.VpnProtocol
import app.spdvpn.backend.model.VpnServerRecord
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

class VpnGateCsvParser(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun parse(csv: String): CatalogSnapshot {
        val rows = parseRfc4180(csv)
        val headerIndex = rows.indexOfFirst { row ->
            row.firstOrNull()?.removePrefix("#") == "HostName"
        }
        require(headerIndex >= 0) { "VPN Gate CSV header was not found" }

        val header = rows[headerIndex].mapIndexed { index, value ->
            if (index == 0) value.removePrefix("#") else value
        }
        val columns = header.withIndex().associate { it.value to it.index }
        REQUIRED_COLUMNS.forEach { column ->
            require(columns.containsKey(column)) { "VPN Gate CSV column is missing: $column" }
        }

        var sourceRows = 0
        var rejectedRows = 0
        val servers = buildList {
            for (row in rows.drop(headerIndex + 1)) {
                val first = row.firstOrNull()?.trim().orEmpty()
                if (first.isEmpty() || first.startsWith('*')) continue
                sourceRows++

                val parsed = runCatching { parseServer(row, columns) }.getOrNull()
                if (parsed == null) {
                    rejectedRows++
                } else {
                    add(parsed)
                }
            }
        }
            .groupBy(VpnServerRecord::id)
            .map { (_, duplicates) -> duplicates.maxBy(VpnServerRecord::score) }
            .sortedByDescending(VpnServerRecord::score)

        require(servers.isNotEmpty()) { "VPN Gate CSV contained no usable OpenVPN servers" }

        return CatalogSnapshot(
            version = sha256(csv).take(32),
            fetchedAtEpochMillis = clock(),
            sourceRows = sourceRows,
            rejectedRows = rejectedRows,
            servers = servers,
        )
    }

    private fun parseServer(row: List<String>, columns: Map<String, Int>): VpnServerRecord {
        fun value(name: String): String = row.getOrNull(columns.getValue(name))?.trim().orEmpty()

        val hostName = value("HostName").required("HostName")
        val ip = value("IP").required("IP")
        val encodedConfig = value("OpenVPN_ConfigData_Base64").required("OpenVPN config")
        val config = decodeConfig(encodedConfig)
        val connection = parseConnection(config)

        return VpnServerRecord(
            id = sha256("$hostName|$ip|${connection.protocol}|${connection.port}").take(24),
            hostName = hostName,
            ip = ip,
            score = value("Score").toLongOrNull() ?: 0,
            pingMs = value("Ping").toIntOrNull()?.takeIf { it >= 0 },
            speedBps = value("Speed").toLongOrNull() ?: 0,
            countryName = value("CountryLong"),
            countryCode = value("CountryShort").uppercase(Locale.ROOT),
            activeSessions = value("NumVpnSessions").toIntOrNull() ?: 0,
            uptimeMillis = value("Uptime").toLongOrNull() ?: 0,
            totalUsers = value("TotalUsers").toLongOrNull() ?: 0,
            totalTrafficBytes = value("TotalTraffic").toLongOrNull() ?: 0,
            logType = value("LogType"),
            operator = value("Operator"),
            message = value("Message"),
            protocol = connection.protocol,
            port = connection.port,
            openVpnConfig = config,
        )
    }

    private fun decodeConfig(encoded: String): String {
        val bytes = Base64.getMimeDecoder().decode(encoded)
        return bytes.toString(Charsets.UTF_8).takeIf(String::isNotBlank)
            ?: error("OpenVPN config was empty")
    }

    private fun parseConnection(config: String): OpenVpnConnection {
        var declaredProtocol: VpnProtocol? = null
        var remoteProtocol: VpnProtocol? = null
        var port: Int? = null
        var hasRemote = false

        config.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) return@forEach

            val parts = line.split(WHITESPACE).filter(String::isNotBlank)
            when (parts.firstOrNull()?.lowercase(Locale.ROOT)) {
                "proto" -> declaredProtocol = parts.getOrNull(1).toVpnProtocol()
                "remote" -> {
                    hasRemote = !parts.getOrNull(1).isNullOrBlank()
                    port = port ?: parts.getOrNull(2)?.toIntOrNull()
                    remoteProtocol = remoteProtocol ?: parts.getOrNull(3).toVpnProtocol()
                }
            }
        }

        require(hasRemote) { "OpenVPN config had no remote directive" }

        return OpenVpnConnection(
            protocol = remoteProtocol ?: declaredProtocol ?: VpnProtocol.UNKNOWN,
            port = port,
        )
    }

    private data class OpenVpnConnection(
        val protocol: VpnProtocol,
        val port: Int?,
    )

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val REQUIRED_COLUMNS = setOf(
            "HostName",
            "IP",
            "Score",
            "Ping",
            "Speed",
            "CountryLong",
            "CountryShort",
            "NumVpnSessions",
            "Uptime",
            "TotalUsers",
            "TotalTraffic",
            "LogType",
            "Operator",
            "Message",
            "OpenVPN_ConfigData_Base64",
        )
    }
}

private fun String?.toVpnProtocol(): VpnProtocol? = when (this?.lowercase(Locale.ROOT)) {
    "udp", "udp4", "udp6" -> VpnProtocol.UDP
    "tcp", "tcp4", "tcp6", "tcp-client", "tcp4-client", "tcp6-client" -> VpnProtocol.TCP
    null -> null
    else -> null
}

private fun String.required(label: String): String =
    takeIf(String::isNotBlank) ?: error("$label was blank")

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun parseRfc4180(input: String): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0

    fun finishField() {
        row += field.toString()
        field.setLength(0)
    }

    fun finishRow() {
        finishField()
        rows += row
        row = mutableListOf()
    }

    while (index < input.length) {
        val char = input[index]
        when {
            char == '"' && inQuotes && input.getOrNull(index + 1) == '"' -> {
                field.append('"')
                index++
            }

            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> finishField()
            (char == '\n' || char == '\r') && !inQuotes -> {
                if (char == '\r' && input.getOrNull(index + 1) == '\n') index++
                finishRow()
            }

            else -> field.append(char)
        }
        index++
    }

    if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
    require(!inQuotes) { "CSV ended inside a quoted field" }
    return rows
}
