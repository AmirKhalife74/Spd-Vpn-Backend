package app.spdvpn.backend.source

import java.util.Locale

internal object SoftEtherTlsServerNameCanonicalizer {
    fun canonicalize(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.removeSuffix(".")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (normalized.isEmpty() || normalized.length > MAX_DNS_NAME_LENGTH) return null
        if (normalizeIpv4(normalized) != null || ':' in normalized) return null

        val labels = normalized.split('.')
        return when {
            labels.size == 1 && labels.single().isValidDnsLabel() ->
                "$normalized.$VPN_GATE_TLS_SUFFIX"

            labels.size == 3 &&
                labels[0].isValidDnsLabel() &&
                labels[1] == "opengw" &&
                labels[2] == "net" -> normalized

            else -> null
        }
    }

    private fun String.isValidDnsLabel(): Boolean =
        length in 1..MAX_DNS_LABEL_LENGTH &&
            first().isAsciiLetterOrDigit() &&
            last().isAsciiLetterOrDigit() &&
            all { it.isAsciiLetterOrDigit() || it == '-' }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private const val VPN_GATE_TLS_SUFFIX = "opengw.net"
    private const val MAX_DNS_LABEL_LENGTH = 63
    private const val MAX_DNS_NAME_LENGTH = 253
}
