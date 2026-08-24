package app.spdvpn.prober.worker

import app.spdvpn.prober.api.ApiInfrastructureException
import app.spdvpn.prober.api.ProbeApi
import app.spdvpn.prober.config.ProberConfig
import app.spdvpn.prober.model.ProbeResultRequest
import app.spdvpn.prober.model.ProbeServer
import app.spdvpn.prober.process.ProbeExecutionResult
import app.spdvpn.prober.process.ProbeRunner
import app.spdvpn.prober.profile.ProfileInspector
import app.spdvpn.prober.profile.ProfileValidationResult
import app.spdvpn.prober.scheduling.ServerSelector
import app.spdvpn.prober.time.JitterSource
import app.spdvpn.prober.time.ProberClock
import app.spdvpn.prober.time.SuspendSleeper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

data class SweepSummary(
    val catalogServers: Int = 0,
    val selected: Int = 0,
    val connected: Int = 0,
    val serverFailures: Int = 0,
    val infrastructureErrors: Int = 0,
    val dryRunAccepted: Int = 0,
    val dryRunRejected: Int = 0,
)

class ProbeWorker(
    private val config: ProberConfig,
    private val api: ProbeApi,
    private val selector: ServerSelector,
    private val profileInspector: ProfileInspector,
    private val probeRunner: ProbeRunner,
    private val clock: ProberClock,
    private val sleeper: SuspendSleeper,
    private val jitterSource: JitterSource,
    private val logger: Logger,
) {
    private val activeServerIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun run() {
        val startupDelay = jitterSource.nextDelayMillis(config.initialJitter.inWholeMilliseconds)
        if (startupDelay > 0) {
            logger.info("Prober startup jitter delayMs={}", startupDelay)
            sleeper.sleep(startupDelay)
        }

        do {
            val summary = runSweep()
            logger.info(
                "Probe sweep completed catalog={} selected={} connected={} failures={} infrastructureErrors={} dryRunAccepted={} dryRunRejected={}",
                summary.catalogServers,
                summary.selected,
                summary.connected,
                summary.serverFailures,
                summary.infrastructureErrors,
                summary.dryRunAccepted,
                summary.dryRunRejected,
            )
            if (config.runOnce) return

            val sweepJitterLimit = minOf(
                config.initialJitter.inWholeMilliseconds,
                config.sweepInterval.inWholeMilliseconds / 10,
            )
            val sweepDelay = config.sweepInterval.inWholeMilliseconds +
                jitterSource.nextDelayMillis(sweepJitterLimit)
            sleeper.sleep(sweepDelay)
        } while (true)
    }

    suspend fun runSweep(): SweepSummary {
        val servers = try {
            api.fetchServers()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ApiInfrastructureException) {
            logger.warn("Catalog fetch skipped reason={}", failure.reasonCode)
            return SweepSummary(infrastructureErrors = 1)
        }

        val selected = selector.select(servers, config.maxServersPerSweep)
        val semaphore = Semaphore(config.concurrency)
        val outcomes = coroutineScope {
            selected.map { server ->
                async {
                    semaphore.withPermit { probeOne(server) }
                }
            }.awaitAll()
        }

        return outcomes.fold(
            initial = SweepSummary(catalogServers = servers.size, selected = selected.size),
        ) { summary, outcome ->
            when (outcome) {
                ServerOutcome.CONNECTED -> summary.copy(connected = summary.connected + 1)
                ServerOutcome.SERVER_FAILURE -> summary.copy(serverFailures = summary.serverFailures + 1)
                ServerOutcome.INFRASTRUCTURE_ERROR ->
                    summary.copy(infrastructureErrors = summary.infrastructureErrors + 1)
                ServerOutcome.DRY_RUN_ACCEPTED -> summary.copy(dryRunAccepted = summary.dryRunAccepted + 1)
                ServerOutcome.DRY_RUN_REJECTED -> summary.copy(dryRunRejected = summary.dryRunRejected + 1)
                ServerOutcome.ALREADY_ACTIVE -> summary
            }
        }
    }

    private suspend fun probeOne(server: ProbeServer): ServerOutcome {
        if (!activeServerIds.add(server.id)) return ServerOutcome.ALREADY_ACTIVE
        val safeServerId = safeIdentifier(server.id)
        try {
            val profile = try {
                api.fetchServerConfig(server.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ApiInfrastructureException) {
                logger.warn("Probe skipped serverId={} reason={}", safeServerId, failure.reasonCode)
                return ServerOutcome.INFRASTRUCTURE_ERROR
            }

            val validation = profileInspector.validate(
                profile = profile,
                allowedRemoteHosts = setOf(server.ip, server.hostName),
                canonicalRemoteIp = server.ip,
            )
            if (config.dryRun) {
                return when (validation) {
                    is ProfileValidationResult.Accepted -> {
                        logger.info("Dry-run validation serverId={} result=ACCEPTED", safeServerId)
                        ServerOutcome.DRY_RUN_ACCEPTED
                    }
                    is ProfileValidationResult.Rejected -> {
                        logger.info(
                            "Dry-run validation serverId={} result=REJECTED reason={}",
                            safeServerId,
                            validation.reasonCode,
                        )
                        ServerOutcome.DRY_RUN_REJECTED
                    }
                }
            }

            val execution = when (validation) {
                is ProfileValidationResult.Accepted ->
                    probeRunner.run(validation.normalizedProfile, config.probeTimeout)
                is ProfileValidationResult.Rejected -> ProbeExecutionResult.ServerFailure(
                    stage = "PROFILE_VALIDATION",
                    reason = validation.reasonCode,
                    durationMs = 0,
                )
            }

            if (execution is ProbeExecutionResult.InfrastructureFailure) {
                logger.warn("Probe skipped serverId={} reason={}", safeServerId, execution.reasonCode)
                return ServerOutcome.INFRASTRUCTURE_ERROR
            }

            val report = execution.toReport(server.id, clock.nowEpochMillis())
            try {
                api.submitProbeResult(report)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ApiInfrastructureException) {
                logger.warn("Probe report not accepted serverId={} reason={}", safeServerId, failure.reasonCode)
                return ServerOutcome.INFRASTRUCTURE_ERROR
            }

            return when (execution) {
                is ProbeExecutionResult.Connected -> {
                    logger.info("Probe connected serverId={} durationMs={}", safeServerId, execution.durationMs)
                    ServerOutcome.CONNECTED
                }
                is ProbeExecutionResult.ServerFailure -> {
                    logger.info(
                        "Probe failed serverId={} stage={} reason={} durationMs={}",
                        safeServerId,
                        execution.stage,
                        execution.reason,
                        execution.durationMs,
                    )
                    ServerOutcome.SERVER_FAILURE
                }
                is ProbeExecutionResult.InfrastructureFailure -> error("handled above")
            }
        } finally {
            activeServerIds.remove(server.id)
        }
    }

    private fun ProbeExecutionResult.toReport(serverId: String, checkedAt: Long): ProbeResultRequest = when (this) {
        is ProbeExecutionResult.Connected -> ProbeResultRequest(
            serverId = serverId,
            success = true,
            stage = "CONNECTED",
            reason = null,
            durationMs = durationMs.coerceIn(0, 600_000),
            checkedAtEpochMillis = checkedAt,
        )
        is ProbeExecutionResult.ServerFailure -> ProbeResultRequest(
            serverId = serverId,
            success = false,
            stage = stage,
            reason = reason,
            durationMs = durationMs.coerceIn(0, 600_000),
            checkedAtEpochMillis = checkedAt,
        )
        is ProbeExecutionResult.InfrastructureFailure -> error("Infrastructure failures must not be reported")
    }

    private fun safeIdentifier(value: String): String = value
        .take(128)
        .map { character -> if (character.isLetterOrDigit() || character in "._-") character else '_' }
        .joinToString("")

    private enum class ServerOutcome {
        CONNECTED,
        SERVER_FAILURE,
        INFRASTRUCTURE_ERROR,
        DRY_RUN_ACCEPTED,
        DRY_RUN_REJECTED,
        ALREADY_ACTIVE,
    }
}
