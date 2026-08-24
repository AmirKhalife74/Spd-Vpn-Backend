package app.spdvpn.prober.scheduling

import app.spdvpn.prober.AdvancingSleeper
import app.spdvpn.prober.FakeClock
import app.spdvpn.prober.model.ProbeServer
import app.spdvpn.prober.model.ServerHealthStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class ProbeSchedulingTest {
    @Test
    fun `selection prefers unknown then degraded and re-probes unhealthy`() {
        val now = 10_000_000L
        val selector = ServerSelector(FakeClock(now), 15.minutes)
        val selected = selector.select(
            listOf(
                server("recent-healthy", ServerHealthStatus.HEALTHY, now - 1_000),
                server("old-healthy", ServerHealthStatus.HEALTHY, now - 2_000_000),
                server("unhealthy", ServerHealthStatus.UNHEALTHY, now - 500),
                server("degraded", ServerHealthStatus.DEGRADED, now - 100),
                server("unknown", ServerHealthStatus.UNKNOWN, null),
            ),
            maximum = 10,
        )

        assertEquals(listOf("unknown", "degraded", "unhealthy", "old-healthy"), selected.map { it.id })
    }

    @Test
    fun `selection is distinct and bounded`() {
        val selector = ServerSelector(FakeClock(), 15.minutes)
        val duplicate = server("same", ServerHealthStatus.UNKNOWN, null)

        assertEquals(1, selector.select(listOf(duplicate, duplicate, server("other")), 1).size)
    }

    @Test
    fun `configuration request gate enforces start spacing`() = runBlocking {
        val clock = FakeClock(1_000)
        val sleeper = AdvancingSleeper(clock)
        val gate = ConfigRequestGate(1_100.milliseconds, clock, sleeper)

        gate.awaitTurn()
        clock.advance(100)
        gate.awaitTurn()
        gate.awaitTurn()

        assertEquals(listOf(0L, 1_000L, 1_100L), sleeper.delays)
        assertEquals(3_200L, clock.nowEpochMillis())
    }

    private fun server(
        id: String,
        health: ServerHealthStatus = ServerHealthStatus.UNKNOWN,
        checkedAt: Long? = null,
    ) = ProbeServer(
        id = id,
        hostName = "vpn.example.com",
        ip = "203.0.113.8",
        score = 100,
        healthStatus = health,
        lastCheckedAtEpochMillis = checkedAt,
    )
}
