package app.spdvpn.prober.process

import app.spdvpn.prober.time.ProberClock
import app.spdvpn.prober.time.SystemProberClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.time.Duration

sealed interface ProbeExecutionResult {
    val durationMs: Long

    data class Connected(
        override val durationMs: Long,
    ) : ProbeExecutionResult

    data class ServerFailure(
        val stage: String,
        val reason: String,
        override val durationMs: Long,
    ) : ProbeExecutionResult

    data class InfrastructureFailure(
        val reasonCode: String,
        override val durationMs: Long,
    ) : ProbeExecutionResult
}

fun interface ProbeRunner {
    suspend fun run(normalizedProfile: String, timeout: Duration): ProbeExecutionResult
}

fun interface ProcessStarter {
    @Throws(IOException::class)
    fun start(command: List<String>, workingDirectory: Path): ManagedProcess
}

interface ManagedProcess {
    val output: InputStream
    val isAlive: Boolean

    fun waitFor(timeoutMillis: Long): Boolean

    fun exitValue(): Int

    fun destroy()

    fun destroyForcibly()

    fun closeStreams()
}

class JvmProcessStarter : ProcessStarter {
    override fun start(command: List<String>, workingDirectory: Path): ManagedProcess {
        val builder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(
            mapOf(
                "HOME" to workingDirectory.absolutePathString(),
                "LANG" to "C",
                "PATH" to "/usr/sbin:/usr/bin:/sbin:/bin",
                "TMPDIR" to workingDirectory.absolutePathString(),
            ),
        )
        return JvmManagedProcess(builder.start())
    }
}

private class JvmManagedProcess(
    private val process: Process,
) : ManagedProcess {
    override val output: InputStream
        get() = process.inputStream
    override val isAlive: Boolean
        get() = process.isAlive

    override fun waitFor(timeoutMillis: Long): Boolean =
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)

    override fun exitValue(): Int = process.exitValue()

    override fun destroy() {
        process.destroy()
    }

    override fun destroyForcibly() {
        process.destroyForcibly()
    }

    override fun closeStreams() {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}

class ProbeWorkspace private constructor(
    val directory: Path,
    val profilePath: Path,
    val authPath: Path,
) : AutoCloseable {
    override fun close() {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    companion object {
        fun create(root: Path, normalizedProfile: String): ProbeWorkspace {
            Files.createDirectories(root)
            val directory = Files.createTempDirectory(root, "spdvpn-probe-")
            setPermissions(directory, DIRECTORY_PERMISSIONS)
            try {
                val profilePath = directory.resolve("profile.ovpn")
                val authPath = directory.resolve("auth.txt")
                Files.writeString(profilePath, normalizedProfile)
                Files.writeString(authPath, "vpn\nvpn\n")
                setPermissions(profilePath, FILE_PERMISSIONS)
                setPermissions(authPath, FILE_PERMISSIONS)
                return ProbeWorkspace(directory, profilePath, authPath)
            } catch (failure: Throwable) {
                ProbeWorkspace(directory, directory.resolve("profile.ovpn"), directory.resolve("auth.txt")).close()
                throw failure
            }
        }

        private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
            try {
                Files.setPosixFilePermissions(path, permissions)
            } catch (_: UnsupportedOperationException) {
                val file = path.toFile()
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setExecutable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
                if (Files.isDirectory(path)) file.setExecutable(true, true)
            }
        }

        private val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}

class OpenVpnProcessRunner(
    private val executable: String,
    private val temporaryRoot: Path,
    private val processStarter: ProcessStarter = JvmProcessStarter(),
    private val clock: ProberClock = SystemProberClock,
) : ProbeRunner {
    override suspend fun run(normalizedProfile: String, timeout: Duration): ProbeExecutionResult = withContext(Dispatchers.IO) {
        val startedAt = clock.nowEpochMillis()
        val workspace = try {
            ProbeWorkspace.create(temporaryRoot, normalizedProfile)
        } catch (_: Throwable) {
            return@withContext ProbeExecutionResult.InfrastructureFailure(
                reasonCode = "WORKSPACE_CREATE_FAILED",
                durationMs = elapsedSince(startedAt),
            )
        }

        workspace.use {
            val command = commandFor(workspace, timeout)
            val process = try {
                processStarter.start(command, workspace.directory)
            } catch (_: IOException) {
                return@withContext ProbeExecutionResult.InfrastructureFailure(
                    reasonCode = "PROCESS_START_FAILED",
                    durationMs = elapsedSince(startedAt),
                )
            } catch (_: SecurityException) {
                return@withContext ProbeExecutionResult.InfrastructureFailure(
                    reasonCode = "PROCESS_START_DENIED",
                    durationMs = elapsedSince(startedAt),
                )
            }

            val classifier = OpenVpnLogClassifier()
            val readerFinished = AtomicBoolean(false)
            val reader = thread(name = "openvpn-output-reader", isDaemon = true) {
                try {
                    process.output.bufferedReader().useLines { lines ->
                        lines.forEach(classifier::observe)
                    }
                } catch (_: IOException) {
                    // Process termination normally closes the pipe.
                } finally {
                    readerFinished.set(true)
                }
            }

            try {
                val timeoutMillis = timeout.inWholeMilliseconds.coerceAtLeast(1)
                var result: ProbeExecutionResult? = null
                while (result == null) {
                    currentCoroutineContext().ensureActive()
                    if (classifier.connected) {
                        result = if (terminateAndReap(process)) {
                            ProbeExecutionResult.Connected(elapsedSince(startedAt))
                        } else {
                            ProbeExecutionResult.InfrastructureFailure(
                                reasonCode = "PROCESS_TERMINATION_FAILED",
                                durationMs = elapsedSince(startedAt),
                            )
                        }
                        continue
                    }
                    if (process.waitFor(POLL_INTERVAL_MILLIS)) {
                        reader.join(READER_JOIN_MILLIS)
                        result = if (classifier.connected) {
                            ProbeExecutionResult.Connected(elapsedSince(startedAt))
                        } else {
                            val classified = classifier.failure
                            ProbeExecutionResult.ServerFailure(
                                stage = classified?.stage ?: "TRANSPORT_CONNECT",
                                reason = classified?.reason ?: "OPENVPN_EXITED",
                                durationMs = elapsedSince(startedAt),
                            )
                        }
                        continue
                    }
                    if (elapsedSince(startedAt) >= timeoutMillis) {
                        result = if (terminateAndReap(process)) {
                            ProbeExecutionResult.ServerFailure(
                                stage = "TIMEOUT",
                                reason = "PROBE_TIMEOUT",
                                durationMs = timeoutMillis.coerceAtMost(MAX_REPORTED_DURATION_MILLIS),
                            )
                        } else {
                            ProbeExecutionResult.InfrastructureFailure(
                                reasonCode = "PROCESS_TERMINATION_FAILED",
                                durationMs = elapsedSince(startedAt),
                            )
                        }
                    }
                }
                result
            } catch (cancellation: CancellationException) {
                terminateAndReap(process)
                throw cancellation
            } finally {
                if (process.isAlive) terminateAndReap(process)
                process.closeStreams()
                if (!readerFinished.get()) reader.join(READER_JOIN_MILLIS)
            }
        }
    }

    internal fun commandFor(workspace: ProbeWorkspace, timeout: Duration): List<String> {
        val serverPollSeconds = timeout.inWholeSeconds.coerceIn(1, 600).toString()
        return listOf(
            executable,
            "--config",
            workspace.profilePath.absolutePathString(),
            "--auth-user-pass",
            workspace.authPath.absolutePathString(),
            "--client",
            "--nobind",
            "--dev",
            "null",
            "--ifconfig-noexec",
            "--route-noexec",
            "--route-nopull",
            "--script-security",
            "0",
            "--auth-nocache",
            "--auth-retry",
            "nointeract",
            "--connect-retry-max",
            "1",
            "--resolv-retry",
            "0",
            "--server-poll-timeout",
            serverPollSeconds,
            "--single-session",
            "--tls-exit",
            "--verb",
            "3",
        )
    }

    private fun terminateAndReap(process: ManagedProcess): Boolean {
        if (!process.isAlive) return true
        process.destroy()
        if (!process.waitFor(GRACEFUL_SHUTDOWN_MILLIS) && process.isAlive) {
            process.destroyForcibly()
            process.waitFor(FORCED_SHUTDOWN_MILLIS)
        }
        return !process.isAlive
    }

    private fun elapsedSince(startedAt: Long): Long =
        (clock.nowEpochMillis() - startedAt).coerceIn(0, MAX_REPORTED_DURATION_MILLIS)

    companion object {
        private const val POLL_INTERVAL_MILLIS = 100L
        private const val GRACEFUL_SHUTDOWN_MILLIS = 2_000L
        private const val FORCED_SHUTDOWN_MILLIS = 2_000L
        private const val READER_JOIN_MILLIS = 1_000L
        private const val MAX_REPORTED_DURATION_MILLIS = 600_000L
    }
}

data class ClassifiedFailure(
    val stage: String,
    val reason: String,
)

class OpenVpnLogClassifier {
    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var failure: ClassifiedFailure? = null
        private set

    fun observe(rawLine: String) {
        val line = rawLine.take(MAX_INSPECTED_LINE_LENGTH).uppercase(Locale.ROOT)
        if ("INITIALIZATION SEQUENCE COMPLETED" in line) {
            connected = true
            return
        }
        val classified = when {
            "AUTH_FAILED" in line || "AUTH-FAILURE" in line ->
                ClassifiedFailure("AUTH", "AUTHENTICATION_FAILED")
            "VERIFY ERROR" in line || "CERTIFICATE VERIFY FAILED" in line ->
                ClassifiedFailure("TLS_HANDSHAKE", "CERTIFICATE_VERIFY_FAILED")
            "TLS ERROR" in line || "TLS KEY NEGOTIATION FAILED" in line ->
                ClassifiedFailure("TLS_HANDSHAKE", "TLS_NEGOTIATION_FAILED")
            "CANNOT RESOLVE HOST ADDRESS" in line || "GETADDRINFO FAILED" in line ||
                "FAILURE IN NAME RESOLUTION" in line ->
                ClassifiedFailure("DNS_RESOLVE", "DNS_RESOLUTION_FAILED")
            "CONNECTION REFUSED" in line ->
                ClassifiedFailure("TRANSPORT_CONNECT", "CONNECTION_REFUSED")
            "NETWORK IS UNREACHABLE" in line || "NO ROUTE TO HOST" in line ->
                ClassifiedFailure("TRANSPORT_CONNECT", "NETWORK_UNREACHABLE")
            "CONNECTION TIMED OUT" in line || "INACTIVITY TIMEOUT" in line ->
                ClassifiedFailure("TRANSPORT_CONNECT", "CONNECTION_TIMEOUT")
            else -> null
        }
        if (classified != null && classified.priority() >= (failure?.priority() ?: -1)) {
            failure = classified
        }
    }

    companion object {
        private const val MAX_INSPECTED_LINE_LENGTH = 4_096
    }
}

private fun ClassifiedFailure.priority(): Int = when (stage) {
    "AUTH" -> 4
    "TLS_HANDSHAKE" -> 3
    "DNS_RESOLVE" -> 2
    else -> 1
}
