package app.spdvpn.backend.config

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppConfigTest {
    @Test
    fun `health persistence defaults under data and blank secret disables endpoint`() {
        val defaults = AppConfig.fromEnvironment(emptyMap())
        val blankSecret = AppConfig.fromEnvironment(mapOf("PROBER_SHARED_SECRET" to "   "))

        assertEquals(Path.of("data/server-health.json"), defaults.healthSnapshotPath)
        assertEquals("https://www.vpngate.net/en/", defaults.htmlUrl)
        assertNull(defaults.proberSharedSecret)
        assertNull(blankSecret.proberSharedSecret)
    }

    @Test
    fun `health path and prober secret load from environment`() {
        val config = AppConfig.fromEnvironment(
            mapOf(
                "HEALTH_SNAPSHOT_PATH" to "/var/lib/spdvpn/health.json",
                "VPN_GATE_HTML_URL" to "https://mirror.example.test/en/",
                "PROBER_SHARED_SECRET" to "configured-test-secret",
            ),
        )

        assertEquals(Path.of("/var/lib/spdvpn/health.json"), config.healthSnapshotPath)
        assertEquals("https://mirror.example.test/en/", config.htmlUrl)
        assertEquals("configured-test-secret", config.proberSharedSecret)
    }

    @Test
    fun `health and catalog snapshots must use different paths`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(
                mapOf(
                    "VPN_GATE_CACHE_PATH" to "data/shared.json",
                    "HEALTH_SNAPSHOT_PATH" to "data/shared.json",
                ),
            )
        }
    }

    @Test
    fun `HTML metadata URL must use HTTP or HTTPS`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(mapOf("VPN_GATE_HTML_URL" to "file:///tmp/vpngate.html"))
        }
    }
}
