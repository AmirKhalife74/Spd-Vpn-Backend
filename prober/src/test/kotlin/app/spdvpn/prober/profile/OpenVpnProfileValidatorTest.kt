package app.spdvpn.prober.profile

import app.spdvpn.prober.SAFE_PROFILE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenVpnProfileValidatorTest {
    private val validator = OpenVpnProfileValidator()

    @Test
    fun `valid inline profile is accepted and profile credentials are removed`() {
        val result = assertIs<ProfileValidationResult.Accepted>(validator.validate(SAFE_PROFILE))

        assertTrue("<ca>" in result.normalizedProfile)
        assertTrue("remote 203.0.113.8 1194" in result.normalizedProfile)
        assertFalse("auth-user-pass" in result.normalizedProfile)
        assertFalse("supplied-by-profile.txt" in result.normalizedProfile)
    }

    @Test
    fun `inline auth credentials are discarded`() {
        val profile = """client
remote 203.0.113.1 1194
<auth-user-pass>
profile-user
profile-password
</auth-user-pass>
<ca>
certificate-data
</ca>
"""

        val result = assertIs<ProfileValidationResult.Accepted>(validator.validate(profile))

        assertFalse("profile-user" in result.normalizedProfile)
        assertFalse("profile-password" in result.normalizedProfile)
        assertTrue("certificate-data" in result.normalizedProfile)
    }

    @Test
    fun `dangerous process and script directives are rejected`() {
        val maliciousDirectives = listOf(
            "config /tmp/second.conf",
            "plugin /tmp/plugin.so",
            "management 0.0.0.0 7505",
            "management-client",
            "script-security 3",
            "up /tmp/run-me",
            "down /tmp/run-me",
            "route-up /tmp/run-me",
            "ipchange /tmp/run-me",
            "learn-address /tmp/run-me",
            "tls-verify /tmp/run-me",
            "auth-user-pass-verify /tmp/run-me via-file",
            "client-connect /tmp/run-me",
            "client-disconnect /tmp/run-me",
            "daemon",
            "log /tmp/openvpn.log",
            "log-append /tmp/openvpn.log",
            "status /tmp/status.log",
            "writepid /tmp/openvpn.pid",
            "chroot /tmp/root",
            "cd /tmp",
            "tmp-dir /tmp/other",
            "askpass /tmp/password",
        )

        maliciousDirectives.forEach { directive ->
            val result = assertIs<ProfileValidationResult.Rejected>(
                validator.validate("client\n$directive\nremote 203.0.113.1 1194\n"),
                directive,
            )
            assertEquals("PROFILE_DIRECTIVE_NOT_ALLOWED", result.reasonCode, directive)
        }
    }

    @Test
    fun `external key and certificate paths are rejected but inline markers are allowed`() {
        listOf(
            "ca /etc/passwd",
            "cert relative.crt",
            "key relative.key",
            "pkcs12 identity.p12",
            "tls-auth ta.key 1",
            "tls-crypt tc.key",
        ).forEach { directive ->
            val result = assertIs<ProfileValidationResult.Rejected>(validator.validate("client\n$directive\n"))
            assertEquals("PROFILE_EXTERNAL_FILE_NOT_ALLOWED", result.reasonCode)
        }

        val inline = validator.validate(
            """client
remote 203.0.113.8 1194
ca [inline]
tls-auth [inline] 1
<ca>
certificate
</ca>
<tls-auth>
key-material
</tls-auth>
""",
        )
        assertIs<ProfileValidationResult.Accepted>(inline)
    }

    @Test
    fun `remote endpoint is bound to catalog identity and local targets are rejected`() {
        val matching = validator.validate(
            "client\nproto udp\nremote vpn.example.com 1194\n",
            setOf("203.0.113.8", "vpn.example.com"),
        )
        val mismatch = validator.validate(
            "client\nproto udp\nremote 198.51.100.7 1194\n",
            setOf("203.0.113.8", "vpn.example.com"),
        )
        val loopback = validator.validate(
            "client\nproto udp\nremote 127.0.0.1 1194\n",
            setOf("127.0.0.1"),
        )

        assertTrue(
            "remote 203.0.113.8 1194" in
                assertIs<ProfileValidationResult.Accepted>(matching).normalizedProfile,
        )
        assertEquals(
            "PROFILE_REMOTE_NOT_ALLOWED",
            assertIs<ProfileValidationResult.Rejected>(mismatch).reasonCode,
        )
        assertEquals(
            "PROFILE_REMOTE_NOT_ALLOWED",
            assertIs<ProfileValidationResult.Rejected>(loopback).reasonCode,
        )
    }

    @Test
    fun `server transport modes are rejected`() {
        val serverProto = validator.validate("client\nproto tcp-server\nremote 203.0.113.8 1194\n")
        val remoteServerProto = validator.validate("client\nremote 203.0.113.8 1194 tcp-server\n")
        val serverMode = validator.validate("mode server\nproto udp\nremote 203.0.113.8 1194\n")

        assertEquals(
            "PROFILE_PROTOCOL_NOT_ALLOWED",
            assertIs<ProfileValidationResult.Rejected>(serverProto).reasonCode,
        )
        assertEquals(
            "PROFILE_PROTOCOL_NOT_ALLOWED",
            assertIs<ProfileValidationResult.Rejected>(remoteServerProto).reasonCode,
        )
        assertEquals(
            "PROFILE_DIRECTIVE_NOT_ALLOWED",
            assertIs<ProfileValidationResult.Rejected>(serverMode).reasonCode,
        )
    }

    @Test
    fun `nested unknown and unclosed inline blocks are rejected`() {
        assertEquals(
            "PROFILE_INLINE_BLOCK_NOT_ALLOWED",
            assertIs<ProfileValidationResult.Rejected>(validator.validate("<connection>\nremote x 1\n</connection>"))
                .reasonCode,
        )
        assertEquals(
            "PROFILE_UNCLOSED_INLINE_BLOCK",
            assertIs<ProfileValidationResult.Rejected>(validator.validate("client\n<ca>\ndata"))
                .reasonCode,
        )
    }

    @Test
    fun `safe log redaction removes secrets and private material`() {
        val secret = "shared-secret-value"
        val input = "token=$secret <key>PRIVATE-DATA</key> -----BEGIN PRIVATE KEY-----abc-----END PRIVATE KEY-----"
        val redacted = SafeLogRedactor.redact(input, listOf(secret))

        assertFalse(secret in redacted)
        assertFalse("PRIVATE-DATA" in redacted)
        assertFalse("-----BEGIN PRIVATE KEY-----" in redacted)
        assertTrue("[REDACTED]" in redacted)
    }
}
