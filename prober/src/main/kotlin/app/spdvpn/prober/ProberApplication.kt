package app.spdvpn.prober

import app.spdvpn.prober.api.HttpProbeApi
import app.spdvpn.prober.config.ProberConfig
import app.spdvpn.prober.process.OpenVpnProcessRunner
import app.spdvpn.prober.profile.OpenVpnProfileValidator
import app.spdvpn.prober.scheduling.ConfigRequestGate
import app.spdvpn.prober.scheduling.ServerSelector
import app.spdvpn.prober.time.CoroutineSleeper
import app.spdvpn.prober.time.RandomJitterSource
import app.spdvpn.prober.time.SystemProberClock
import app.spdvpn.prober.worker.ProbeWorker
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess

fun main() {
    val logger = LoggerFactory.getLogger("app.spdvpn.prober")
    val config = try {
        ProberConfig.fromEnvironment()
    } catch (failure: IllegalArgumentException) {
        logger.error("Invalid prober configuration: {}", failure.message)
        exitProcess(2)
    } catch (failure: IllegalStateException) {
        logger.error("Invalid prober configuration: {}", failure.message)
        exitProcess(2)
    }

    runBlocking { runProber(config, logger) }
}

private suspend fun runProber(config: ProberConfig, logger: org.slf4j.Logger) {
    logger.info(
        "Starting isolated VPN prober dryRun={} runOnce={} concurrency={} maxServersPerSweep={}",
        config.dryRun,
        config.runOnce,
        config.concurrency,
        config.maxServersPerSweep,
    )

    val requestGate = ConfigRequestGate(
        interval = config.configRequestInterval,
        clock = SystemProberClock,
        sleeper = CoroutineSleeper,
    )
    val api = HttpProbeApi(
        baseUrl = config.apiBaseUrl,
        sharedSecret = config.sharedSecret,
        configRequestGate = requestGate,
    )
    val worker = ProbeWorker(
        config = config,
        api = api,
        selector = ServerSelector(SystemProberClock, config.sweepInterval),
        profileInspector = OpenVpnProfileValidator(),
        probeRunner = OpenVpnProcessRunner(
            executable = config.openVpnExecutable,
            temporaryRoot = Path.of(config.temporaryDirectory),
        ),
        clock = SystemProberClock,
        sleeper = CoroutineSleeper,
        jitterSource = RandomJitterSource(),
        logger = logger,
    )

    val rootJob = kotlinx.coroutines.currentCoroutineContext().job
    val shutdownHook = Thread(
        { rootJob.cancel("JVM shutdown requested") },
        "prober-shutdown",
    )
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        api.use { worker.run() }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}
