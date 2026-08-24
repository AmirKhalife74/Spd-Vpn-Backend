package app.spdvpn.prober.profile

import java.net.InetAddress
import java.util.Locale

sealed interface ProfileValidationResult {
    data class Accepted(val normalizedProfile: String) : ProfileValidationResult

    data class Rejected(val reasonCode: String) : ProfileValidationResult
}

fun interface ProfileInspector {
    fun validate(
        profile: String,
        allowedRemoteHosts: Set<String>,
        canonicalRemoteIp: String?,
    ): ProfileValidationResult
}

class OpenVpnProfileValidator(
    private val maximumBytes: Int = 512 * 1024,
) : ProfileInspector {
    fun validate(profile: String): ProfileValidationResult = validate(profile, emptySet(), null)

    fun validate(profile: String, allowedRemoteHosts: Set<String>): ProfileValidationResult = validate(
        profile = profile,
        allowedRemoteHosts = allowedRemoteHosts,
        canonicalRemoteIp = allowedRemoteHosts.firstOrNull { IPV4.matches(it) || ':' in it },
    )

    override fun validate(
        profile: String,
        allowedRemoteHosts: Set<String>,
        canonicalRemoteIp: String?,
    ): ProfileValidationResult {
        if (profile.toByteArray(Charsets.UTF_8).size > maximumBytes) {
            return rejected("PROFILE_TOO_LARGE")
        }
        if ('\u0000' in profile) return rejected("PROFILE_CONTAINS_NUL")

        val output = StringBuilder(profile.length)
        var inlineBlock: String? = null
        var discardInlineBlock = false
        var remoteCount = 0
        val normalizedAllowedHosts = allowedRemoteHosts.map { it.lowercase(Locale.ROOT) }.toSet()
        val normalizedCanonicalIp = canonicalRemoteIp?.lowercase(Locale.ROOT)
        if (normalizedCanonicalIp != null && !remoteHostAllowed(
                normalizedCanonicalIp,
                setOf(normalizedCanonicalIp),
            )
        ) {
            return rejected("PROFILE_REMOTE_NOT_ALLOWED")
        }

        profile.lineSequence().forEach { originalLine ->
            val trimmed = originalLine.trim()
            val currentBlock = inlineBlock
            if (currentBlock != null) {
                val tag = INLINE_TAG.matchEntire(trimmed)
                if (tag != null) {
                    val closing = tag.groupValues[1] == "/"
                    val name = tag.groupValues[2].lowercase(Locale.ROOT)
                    if (!closing || name != currentBlock) {
                        return rejected("PROFILE_NESTED_OR_MISMATCHED_INLINE_BLOCK")
                    }
                    if (!discardInlineBlock) output.appendLine(originalLine)
                    inlineBlock = null
                    discardInlineBlock = false
                } else if (!discardInlineBlock) {
                    output.appendLine(originalLine)
                }
                return@forEach
            }

            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) {
                output.appendLine(originalLine)
                return@forEach
            }

            val tag = INLINE_TAG.matchEntire(trimmed)
            if (tag != null) {
                if (tag.groupValues[1] == "/") {
                    return rejected("PROFILE_UNEXPECTED_INLINE_BLOCK_END")
                }
                val name = tag.groupValues[2].lowercase(Locale.ROOT)
                if (name == "auth-user-pass") {
                    inlineBlock = name
                    discardInlineBlock = true
                    return@forEach
                }
                if (name !in ALLOWED_INLINE_BLOCKS) {
                    return rejected("PROFILE_INLINE_BLOCK_NOT_ALLOWED")
                }
                inlineBlock = name
                output.appendLine(originalLine)
                return@forEach
            }

            val directive = extractDirective(trimmed)
                ?: return rejected("PROFILE_DIRECTIVE_INVALID")
            var normalizedLine = originalLine
            if (directive == "auth-user-pass") {
                return@forEach
            }
            if (isForbiddenDirective(directive)) {
                return rejected("PROFILE_DIRECTIVE_NOT_ALLOWED")
            }
            if (directive == "proto" && !usesClientProtocol(trimmed)) {
                return rejected("PROFILE_PROTOCOL_NOT_ALLOWED")
            }
            if (directive == "remote") {
                val arguments = directiveArguments(trimmed, directive)
                val remoteHost = arguments.firstOrNull()
                    ?: return rejected("PROFILE_REMOTE_INVALID")
                if (arguments.size > 3) return rejected("PROFILE_REMOTE_INVALID")
                if (arguments.getOrNull(1)?.toIntOrNull()?.let { it in 1..65_535 } == false) {
                    return rejected("PROFILE_REMOTE_INVALID")
                }
                val remoteProtocol = arguments.getOrNull(2)?.lowercase(Locale.ROOT)
                if (remoteProtocol != null && remoteProtocol !in CLIENT_PROTOCOLS) {
                    return rejected("PROFILE_PROTOCOL_NOT_ALLOWED")
                }
                if (!remoteHostAllowed(remoteHost, normalizedAllowedHosts)) {
                    return rejected("PROFILE_REMOTE_NOT_ALLOWED")
                }
                if (normalizedCanonicalIp != null) {
                    normalizedLine = buildString {
                        append("remote ")
                        append(normalizedCanonicalIp)
                        arguments.drop(1).forEach { argument ->
                            append(' ')
                            append(argument)
                        }
                    }
                }
                remoteCount++
                if (remoteCount > MAX_REMOTE_DIRECTIVES) {
                    return rejected("PROFILE_TOO_MANY_REMOTES")
                }
            }
            if (directive in INLINE_CAPABLE_FILE_DIRECTIVES && !usesInlineValue(trimmed, directive)) {
                return rejected("PROFILE_EXTERNAL_FILE_NOT_ALLOWED")
            }
            if (directive in ALWAYS_EXTERNAL_FILE_DIRECTIVES) {
                return rejected("PROFILE_EXTERNAL_FILE_NOT_ALLOWED")
            }
            output.appendLine(normalizedLine)
        }

        if (inlineBlock != null) return rejected("PROFILE_UNCLOSED_INLINE_BLOCK")
        if (remoteCount == 0) return rejected("PROFILE_REMOTE_REQUIRED")
        if (output.isBlank()) return rejected("PROFILE_EMPTY")
        return ProfileValidationResult.Accepted(output.toString())
    }

    private fun usesClientProtocol(line: String): Boolean {
        val protocol = directiveArguments(line, "proto").firstOrNull()?.lowercase(Locale.ROOT)
        return protocol in CLIENT_PROTOCOLS
    }

    private fun remoteHostAllowed(host: String, allowedHosts: Set<String>): Boolean {
        val normalized = host.trim('"', '\'').lowercase(Locale.ROOT)
        if (!REMOTE_HOST.matches(normalized)) return false
        if (allowedHosts.isNotEmpty() && normalized !in allowedHosts) return false
        if (normalized == "localhost" || BLOCKED_HOST_SUFFIXES.any(normalized::endsWith)) return false

        val address = when {
            IPV4.matches(normalized) -> runCatching { InetAddress.getByName(normalized) }.getOrNull()
                ?: return false
            ':' in normalized -> runCatching { InetAddress.getByName(normalized) }.getOrNull()
                ?: return false
            else -> null
        }
        if (address == null) return '.' in normalized
        val bytes = address.address
        val uniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        val carrierGradeNat = bytes.size == 4 &&
            (bytes[0].toInt() and 0xff) == 100 &&
            (bytes[1].toInt() and 0xc0) == 64
        return !(
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                uniqueLocalIpv6 ||
                carrierGradeNat
            )
    }

    private fun directiveArguments(line: String, directive: String): List<String> {
        val withoutPrefix = line.removePrefix("--")
        return withoutPrefix.drop(directive.length)
            .substringBefore('#')
            .substringBefore(';')
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
    }

    private fun usesInlineValue(line: String, directive: String): Boolean {
        val withoutPrefix = line.removePrefix("--")
        val remainder = withoutPrefix.drop(directive.length).trimStart()
        if (!remainder.startsWith("[inline]", ignoreCase = true)) return false
        val trailing = remainder.drop("[inline]".length).trim()
        return when (directive) {
            "tls-auth" -> trailing.isEmpty() || trailing == "0" || trailing == "1"
            else -> trailing.isEmpty()
        }
    }

    private fun extractDirective(line: String): String? {
        val withoutPrefix = line.removePrefix("--")
        val token = withoutPrefix.takeWhile { !it.isWhitespace() }
        if (!DIRECTIVE_NAME.matches(token)) return null
        return token.lowercase(Locale.ROOT)
    }

    private fun isForbiddenDirective(directive: String): Boolean =
        directive.startsWith("management") ||
            directive.startsWith("pkcs11") ||
            directive.startsWith("show-") ||
            directive in FORBIDDEN_DIRECTIVES

    private fun rejected(code: String) = ProfileValidationResult.Rejected(code)

    companion object {
        private val INLINE_TAG = Regex("^<(/?)([A-Za-z0-9-]+)>$")
        private val DIRECTIVE_NAME = Regex("^[A-Za-z][A-Za-z0-9-]*$")
        private val REMOTE_HOST = Regex("^[A-Za-z0-9.:%_-]{1,253}$")
        private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
        private val BLOCKED_HOST_SUFFIXES = setOf(".localhost", ".local", ".internal", ".home", ".lan")
        private val CLIENT_PROTOCOLS = setOf(
            "udp",
            "udp4",
            "udp6",
            "tcp",
            "tcp4",
            "tcp6",
            "tcp-client",
            "tcp4-client",
            "tcp6-client",
        )
        private const val MAX_REMOTE_DIRECTIVES = 8

        private val ALLOWED_INLINE_BLOCKS = setOf(
            "ca",
            "cert",
            "key",
            "extra-certs",
            "tls-auth",
            "tls-crypt",
            "tls-crypt-v2",
        )

        private val INLINE_CAPABLE_FILE_DIRECTIVES = setOf(
            "ca",
            "cert",
            "key",
            "tls-auth",
            "tls-crypt",
            "tls-crypt-v2",
            "extra-certs",
        )

        private val ALWAYS_EXTERNAL_FILE_DIRECTIVES = setOf(
            "pkcs12",
            "secret",
            "crl-verify",
            "dh",
            "http-proxy-user-pass",
            "auth-gen-token-secret",
        )

        private val FORBIDDEN_DIRECTIVES = setOf(
            "config",
            "plugin",
            "engine",
            "providers",
            "script-security",
            "up",
            "down",
            "route-up",
            "route-pre-down",
            "ipchange",
            "learn-address",
            "tls-verify",
            "auth-user-pass-verify",
            "client-connect",
            "client-disconnect",
            "client-crresponse",
            "daemon",
            "log",
            "log-append",
            "status",
            "status-version",
            "writepid",
            "syslog",
            "chroot",
            "cd",
            "tmp-dir",
            "askpass",
            "dev-type",
            "dev-node",
            "iproute",
            "user",
            "group",
            "inetd",
            "port-share",
            "memstats",
            "replay-persist",
            "ifconfig-pool-persist",
            "client-config-dir",
            "tls-export-cert",
            "tls-crypt-v2-verify",
            "mktun",
            "rmtun",
            "genkey",
            "test-crypto",
            "echo",
            "nice",
            "mode",
            "server",
            "server-ipv6",
            "server-bridge",
            "push",
            "http-proxy",
            "http-proxy-option",
            "socks-proxy",
            "socks-proxy-retry",
            "auth-token",
            "auth-token-user",
            "static-challenge",
        )
    }
}

object SafeLogRedactor {
    private val inlineSecretBlock = Regex(
        "(?is)<(ca|cert|key|extra-certs|pkcs12|tls-auth|tls-crypt|tls-crypt-v2)>.*?</\\1>",
    )
    private val privateKeyBlock = Regex(
        "(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
    )

    fun redact(value: String, secrets: Collection<String> = emptyList()): String {
        var redacted = inlineSecretBlock.replace(value, "[REDACTED_INLINE_BLOCK]")
        redacted = privateKeyBlock.replace(redacted, "[REDACTED_PRIVATE_KEY]")
        secrets.filter(String::isNotEmpty).forEach { secret ->
            redacted = redacted.replace(secret, "[REDACTED]")
        }
        return redacted
    }
}
