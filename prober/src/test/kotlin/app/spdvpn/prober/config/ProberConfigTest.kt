package app.spdvpn.prober.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProberConfigTest {
    @Test
    fun `defaults are bounded and dry-run can omit the secret`() {
        val config = ProberConfig.fromEnvironment(mapOf("PROBER_DRY_RUN" to "true"))

        assertEquals("http://api:8080", config.apiBaseUrl)
        assertEquals(2, config.concurrency)
        assertEquals(35, config.probeTimeout.inWholeSeconds)
        assertEquals(1_100, config.configRequestInterval.inWholeMilliseconds)
        assertEquals(25, config.maxServersPerSweep)
        assertNull(config.sharedSecret)
    }

    @Test
    fun `normal mode requires a secret`() {
        assertFailsWith<IllegalArgumentException> {
            ProberConfig.fromEnvironment(emptyMap())
        }
    }

    @Test
    fun `concurrency hard max and request interval minimum are enforced`() {
        val base = mapOf("PROBER_SHARED_SECRET" to "secret")

        assertFailsWith<IllegalArgumentException> {
            ProberConfig.fromEnvironment(base + ("PROBER_CONCURRENCY" to "5"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProberConfig.fromEnvironment(base + ("PROBER_CONFIG_REQUEST_INTERVAL_MS" to "1099"))
        }
    }

    @Test
    fun `booleans and base URL are validated strictly`() {
        val base = mapOf("PROBER_SHARED_SECRET" to "secret")

        assertFailsWith<IllegalStateException> {
            ProberConfig.fromEnvironment(base + ("PROBER_RUN_ONCE" to "yes"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProberConfig.fromEnvironment(base + ("PROBER_API_BASE_URL" to "file:///tmp/api"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProberConfig.fromEnvironment(base + ("PROBER_API_BASE_URL" to "http://user:pass@api:8080"))
        }
    }
}
