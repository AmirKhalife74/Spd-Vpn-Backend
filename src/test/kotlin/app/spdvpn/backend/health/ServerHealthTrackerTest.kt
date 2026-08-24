package app.spdvpn.backend.health

import app.spdvpn.backend.applicationJson
import app.spdvpn.backend.model.ProbeResult
import app.spdvpn.backend.model.ServerHealthStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerHealthTrackerTest {
    @Test
    fun `missing record is unknown to callers`() {
        val tracker = tracker()

        assertNull(tracker.healthFor(SERVER_ID))
        assertEquals(ServerHealthStatus.UNKNOWN, tracker.statusFor(SERVER_ID))
    }

    @Test
    fun `successful probe produces healthy record`() = runBlocking {
        val tracker = tracker()

        val health = tracker.recordProbeResult(success(at = 1_000L, durationMs = 245L))

        assertEquals(ServerHealthStatus.HEALTHY, health.status)
        assertEquals(1_000L, health.lastCheckedAtEpochMillis)
        assertEquals(1_000L, health.lastSuccessfulAtEpochMillis)
        assertEquals(245L, health.lastProbeDurationMs)
        assertEquals(245L, health.lastSuccessfulConnectionTimeMs)
        assertEquals(0, health.consecutiveFailures)
        assertNull(health.lastFailureStage)
        assertNull(health.lastFailureReason)
    }

    @Test
    fun `first failure produces degraded record`() = runBlocking {
        val health = tracker().recordProbeResult(failure(at = 1_000L))

        assertEquals(ServerHealthStatus.DEGRADED, health.status)
        assertEquals(1, health.consecutiveFailures)
        assertEquals(500L, health.lastProbeDurationMs)
        assertNull(health.lastSuccessfulConnectionTimeMs)
        assertEquals("CONNECT", health.lastFailureStage)
        assertEquals("timeout", health.lastFailureReason)
    }

    @Test
    fun `second failure remains degraded`() = runBlocking {
        val tracker = tracker()

        tracker.recordProbeResult(failure(at = 1_000L))
        val health = tracker.recordProbeResult(failure(at = 2_000L))

        assertEquals(ServerHealthStatus.DEGRADED, health.status)
        assertEquals(2, health.consecutiveFailures)
    }

    @Test
    fun `third failure produces unhealthy record`() = runBlocking {
        val tracker = tracker()

        tracker.recordProbeResult(failure(at = 1_000L))
        tracker.recordProbeResult(failure(at = 2_000L))
        val health = tracker.recordProbeResult(failure(at = 3_000L))

        assertEquals(ServerHealthStatus.UNHEALTHY, health.status)
        assertEquals(3, health.consecutiveFailures)
    }

    @Test
    fun `later success restores healthy and resets failures`() = runBlocking {
        val tracker = tracker()

        tracker.recordProbeResult(failure(at = 1_000L))
        tracker.recordProbeResult(failure(at = 2_000L))
        tracker.recordProbeResult(failure(at = 3_000L))
        val health = tracker.recordProbeResult(success(at = 4_000L, durationMs = 99L))

        assertEquals(ServerHealthStatus.HEALTHY, health.status)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(4_000L, health.lastSuccessfulAtEpochMillis)
        assertEquals(99L, health.lastProbeDurationMs)
        assertEquals(99L, health.lastSuccessfulConnectionTimeMs)
        assertNull(health.lastFailureStage)
        assertNull(health.lastFailureReason)
    }

    @Test
    fun `failure updates probe duration without overwriting successful connection time`() = runBlocking {
        val tracker = tracker()
        tracker.recordProbeResult(success(at = 1_000L, durationMs = 120L))

        val health = tracker.recordProbeResult(failure(at = 2_000L, durationMs = 700L))

        assertEquals(700L, health.lastProbeDurationMs)
        assertEquals(120L, health.lastSuccessfulConnectionTimeMs)
        assertEquals(1_000L, health.lastSuccessfulAtEpochMillis)
    }

    @Test
    fun `file persistence saves and loads health round trip`() = runBlocking {
        val path = createTempDirectory("spdvpn-health-test").resolve("server-health.json")
        val store = FileServerHealthSnapshotStore(path, applicationJson())
        val writer = ServerHealthTracker(store)
        val expected = writer.recordProbeResult(success(at = 5_000L, durationMs = 321L))
        val reader = ServerHealthTracker(store)

        reader.load()

        assertEquals(expected, reader.healthFor(SERVER_ID))
        assertEquals(1, Files.list(path.parent).use { it.count() })
    }

    @Test
    fun `corrupt persistence does not prevent tracker startup`() = runBlocking {
        val path = createTempDirectory("spdvpn-health-corrupt-test").resolve("server-health.json")
        Files.writeString(path, "not-json")
        val tracker = ServerHealthTracker(FileServerHealthSnapshotStore(path, applicationJson()))

        tracker.load()

        assertNull(tracker.healthFor(SERVER_ID))
    }

    @Test
    fun `records absent from the catalog are pruned after 24 hours`() = runBlocking {
        var now = 10_000L
        val tracker = ServerHealthTracker(
            snapshotStore = InMemoryServerHealthSnapshotStore(),
            clock = { now },
        )
        tracker.recordProbeResult(success(at = 1_000L, durationMs = 20L))

        tracker.reconcileCatalog(emptySet())
        now += 24 * 60 * 60 * 1_000L
        tracker.reconcileCatalog(emptySet())

        assertNull(tracker.healthFor(SERVER_ID))
    }

    private fun tracker() = ServerHealthTracker(InMemoryServerHealthSnapshotStore())

    private fun success(at: Long, durationMs: Long) = ProbeResult(
        serverId = SERVER_ID,
        success = true,
        stage = "CONNECTED",
        durationMs = durationMs,
        checkedAtEpochMillis = at,
    )

    private fun failure(at: Long, durationMs: Long = 500L) = ProbeResult(
        serverId = SERVER_ID,
        success = false,
        stage = "CONNECT",
        reason = "timeout",
        durationMs = durationMs,
        checkedAtEpochMillis = at,
    )

    private companion object {
        private const val SERVER_ID = "server-id"
    }
}
