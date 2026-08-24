package app.spdvpn.prober.worker

import app.spdvpn.prober.AdvancingSleeper
import app.spdvpn.prober.FakeClock
import app.spdvpn.prober.FakeProbeApi
import app.spdvpn.prober.SAFE_PROFILE
import app.spdvpn.prober.api.ApiInfrastructureException
import app.spdvpn.prober.model.ProbeServer
import app.spdvpn.prober.model.ServerHealthStatus
import app.spdvpn.prober.process.ProbeExecutionResult
import app.spdvpn.prober.process.ProbeRunner
import app.spdvpn.prober.profile.OpenVpnProfileValidator
import app.spdvpn.prober.profile.ProfileInspector
import app.spdvpn.prober.profile.ProfileValidationResult
import app.spdvpn.prober.scheduling.ServerSelector
import app.spdvpn.prober.testConfig
import app.spdvpn.prober.time.JitterSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProbeWorkerTest {
    @Test
    fun `valid connected probe submits the exact Phase 1 success semantics`() = runBlocking {
        val clock = FakeClock()
        val api = FakeProbeApi(listOf(server("server-1")))
        val worker = worker(
            api = api,
            clock = clock,
            runner = ProbeRunner { _, _ -> ProbeExecutionResult.Connected(2_450) },
        )

        val summary = worker.runSweep()

        assertEquals(1, summary.connected)
        val report = api.reports.single()
        assertEquals("server-1", report.serverId)
        assertTrue(report.success)
        assertEquals("CONNECTED", report.stage)
        assertEquals(null, report.reason)
        assertEquals(2_450, report.durationMs)
        assertEquals(clock.nowEpochMillis(), report.checkedAtEpochMillis)
    }

    @Test
    fun `profile rejection reports only a fixed failure code`() = runBlocking {
        val api = FakeProbeApi(
            servers = listOf(server("server-1")),
            profiles = mapOf("server-1" to "client\nplugin /tmp/evil.so\n"),
        )
        var runnerCalled = false
        val worker = worker(
            api = api,
            runner = ProbeRunner { _, _ ->
                runnerCalled = true
                ProbeExecutionResult.Connected(1)
            },
        )

        val summary = worker.runSweep()

        assertFalse(runnerCalled)
        assertEquals(1, summary.serverFailures)
        assertEquals("PROFILE_VALIDATION", api.reports.single().stage)
        assertEquals("PROFILE_DIRECTIVE_NOT_ALLOWED", api.reports.single().reason)
    }

    @Test
    fun `config 429 and process infrastructure errors never submit server failures`() = runBlocking {
        val configApi = FakeProbeApi(listOf(server("config-error")))
        configApi.configFailures["config-error"] = ApiInfrastructureException("API_RATE_LIMITED")
        val processApi = FakeProbeApi(listOf(server("process-error")))

        val first = worker(configApi).runSweep()
        val second = worker(
            api = processApi,
            runner = ProbeRunner { _, _ ->
                ProbeExecutionResult.InfrastructureFailure("PROCESS_START_FAILED", 0)
            },
        ).runSweep()

        assertEquals(1, first.infrastructureErrors)
        assertEquals(1, second.infrastructureErrors)
        assertTrue(configApi.reports.isEmpty())
        assertTrue(processApi.reports.isEmpty())
    }

    @Test
    fun `catalog 5xx aborts the sweep without config or report calls`() = runBlocking {
        val api = FakeProbeApi(listOf(server("server-1")))
        api.listFailure = ApiInfrastructureException("API_UNAVAILABLE")

        val summary = worker(api).runSweep()

        assertEquals(1, summary.infrastructureErrors)
        assertTrue(api.configRequests.isEmpty())
        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `report 5xx is infrastructure error and is not converted to a failure report`() = runBlocking {
        val api = FakeProbeApi(listOf(server("server-1")))
        api.reportFailure = ApiInfrastructureException("API_UNAVAILABLE")

        val summary = worker(api).runSweep()

        assertEquals(1, summary.infrastructureErrors)
        assertEquals(0, summary.serverFailures)
        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `worker cancellation propagates and never submits a report`() = runBlocking {
        val api = FakeProbeApi(listOf(server("server-1")))
        val worker = worker(
            api = api,
            runner = ProbeRunner { _, _ -> throw CancellationException("shutdown") },
        )

        assertFailsWith<CancellationException> { worker.runSweep() }
        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `dry-run fetches and validates but never starts OpenVPN or reports`() = runBlocking {
        val api = FakeProbeApi(listOf(server("server-1")))
        var runnerCalls = 0
        val worker = worker(
            api = api,
            config = testConfig(dryRun = true),
            runner = ProbeRunner { _, _ ->
                runnerCalls++
                ProbeExecutionResult.Connected(1)
            },
        )

        val summary = worker.runSweep()

        assertEquals(1, summary.dryRunAccepted)
        assertEquals(0, runnerCalls)
        assertEquals(listOf("server-1"), api.configRequests)
        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `concurrency is bounded and duplicate server IDs are not probed twice`() = runBlocking {
        val servers = (1..4).map { server("server-$it") } + server("server-1")
        val api = FakeProbeApi(servers)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val runner = ProbeRunner { _, _ ->
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> max(previous, current) }
            if (entered.incrementAndGet() == 2) release.complete(Unit)
            release.await()
            active.decrementAndGet()
            ProbeExecutionResult.Connected(50)
        }

        val summary = worker(
            api = api,
            config = testConfig(concurrency = 2),
            runner = runner,
        ).runSweep()

        assertEquals(4, summary.selected)
        assertEquals(4, summary.connected)
        assertEquals(2, maximumActive.get())
        assertEquals(4, api.reports.size)
        assertEquals(4, api.reports.map { it.serverId }.distinct().size)
    }

    @Test
    fun `server failure mapping keeps duration and bounded stage reason`() = runBlocking {
        val api = FakeProbeApi(listOf(server("server-1")))
        val worker = worker(
            api = api,
            runner = ProbeRunner { _, _ ->
                ProbeExecutionResult.ServerFailure(
                    stage = "TLS_HANDSHAKE",
                    reason = "TLS_NEGOTIATION_FAILED",
                    durationMs = 7_500,
                )
            },
        )

        worker.runSweep()

        val report = api.reports.single()
        assertFalse(report.success)
        assertEquals("TLS_HANDSHAKE", report.stage)
        assertEquals("TLS_NEGOTIATION_FAILED", report.reason)
        assertEquals(7_500, report.durationMs)
    }

    private fun worker(
        api: FakeProbeApi,
        config: app.spdvpn.prober.config.ProberConfig = testConfig(),
        clock: FakeClock = FakeClock(),
        inspector: ProfileInspector = OpenVpnProfileValidator(),
        runner: ProbeRunner = ProbeRunner { _, _ -> ProbeExecutionResult.Connected(100) },
    ): ProbeWorker = ProbeWorker(
        config = config,
        api = api,
        selector = ServerSelector(clock, config.sweepInterval),
        profileInspector = inspector,
        probeRunner = runner,
        clock = clock,
        sleeper = AdvancingSleeper(clock),
        jitterSource = JitterSource { 0 },
        logger = LoggerFactory.getLogger("ProbeWorkerTest"),
    )

    private fun server(id: String) = ProbeServer(
        id = id,
        hostName = "vpn.example.com",
        ip = "203.0.113.8",
        score = 100,
        healthStatus = ServerHealthStatus.UNKNOWN,
    )
}
