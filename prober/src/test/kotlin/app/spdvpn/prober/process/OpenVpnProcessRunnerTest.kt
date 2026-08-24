package app.spdvpn.prober.process

import app.spdvpn.prober.FakeClock
import app.spdvpn.prober.SAFE_PROFILE
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OpenVpnProcessRunnerTest {
    @Test
    fun `connected log is detected and process is gracefully reaped`() = runBlocking {
        val root = Files.createTempDirectory("prober-runner-test-")
        val clock = FakeClock(1_000)
        val process = FakeManagedProcess(
            outputText = "Peer Connection Initiated\nInitialization Sequence Completed\n",
            clock = clock,
            exitImmediately = false,
            gracefulDestroyStops = true,
        )
        val commands = mutableListOf<List<String>>()
        val runner = OpenVpnProcessRunner(
            executable = "/usr/sbin/openvpn",
            temporaryRoot = root,
            processStarter = ProcessStarter { command, _ ->
                commands += command
                process
            },
            clock = clock,
        )

        val result = runner.run(SAFE_PROFILE, 5.seconds)

        assertIs<ProbeExecutionResult.Connected>(result)
        assertTrue(process.destroyCalled)
        assertFalse(process.forceDestroyCalled)
        assertFalse(process.isAlive)
        assertSafeCommand(commands.single())
        assertDirectoryEmpty(root)
        deleteTree(root)
    }

    @Test
    fun `timeout force kills and reaps a resistant child`() = runBlocking {
        val root = Files.createTempDirectory("prober-timeout-test-")
        val clock = FakeClock(5_000)
        val process = FakeManagedProcess(
            outputText = "TCP connection established\n",
            clock = clock,
            exitImmediately = false,
            gracefulDestroyStops = false,
        )
        val runner = OpenVpnProcessRunner(
            executable = "/usr/sbin/openvpn",
            temporaryRoot = root,
            processStarter = ProcessStarter { _, _ -> process },
            clock = clock,
        )

        val result = assertIs<ProbeExecutionResult.ServerFailure>(runner.run(SAFE_PROFILE, 200.milliseconds))

        assertEquals("TIMEOUT", result.stage)
        assertEquals("PROBE_TIMEOUT", result.reason)
        assertEquals(200, result.durationMs)
        assertTrue(process.destroyCalled)
        assertTrue(process.forceDestroyCalled)
        assertTrue(process.waitCalls >= 3)
        assertFalse(process.isAlive)
        assertDirectoryEmpty(root)
        deleteTree(root)
    }

    @Test
    fun `process start failure is infrastructure error and temporary files are removed`() = runBlocking {
        val root = Files.createTempDirectory("prober-start-failure-test-")
        val runner = OpenVpnProcessRunner(
            executable = "/missing/openvpn",
            temporaryRoot = root,
            processStarter = ProcessStarter { _, _ -> throw java.io.IOException("missing") },
            clock = FakeClock(),
        )

        val result = assertIs<ProbeExecutionResult.InfrastructureFailure>(runner.run(SAFE_PROFILE, 1.seconds))

        assertEquals("PROCESS_START_FAILED", result.reasonCode)
        assertDirectoryEmpty(root)
        deleteTree(root)
    }

    @Test
    fun `cancellation terminates the child and cleans temporary files without an outcome`() = runBlocking {
        val root = Files.createTempDirectory("prober-cancellation-test-")
        val started = CompletableDeferred<Unit>()
        val process = BlockingManagedProcess()
        val runner = OpenVpnProcessRunner(
            executable = "/usr/sbin/openvpn",
            temporaryRoot = root,
            processStarter = ProcessStarter { _, _ ->
                started.complete(Unit)
                process
            },
            clock = FakeClock(),
        )

        val job = launch { runner.run(SAFE_PROFILE, 60.seconds) }
        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(process.destroyCalled)
        assertFalse(process.isAlive)
        assertDirectoryEmpty(root)
        deleteTree(root)
    }

    @Test
    fun `workspace writes owner-only credentials and removes the entire probe directory`() {
        val root = Files.createTempDirectory("prober-workspace-test-")
        val workspace = ProbeWorkspace.create(root, SAFE_PROFILE)
        val directory = workspace.directory

        assertEquals("vpn\nvpn\n", Files.readString(workspace.authPath))
        if (Files.getFileStore(workspace.authPath).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(workspace.authPath),
            )
        }

        workspace.close()

        assertFalse(Files.exists(directory))
        assertDirectoryEmpty(root)
        deleteTree(root)
    }

    @Test
    fun `log classifier maps only bounded safe codes`() {
        val cases = listOf(
            "AUTH_FAILED" to ClassifiedFailure("AUTH", "AUTHENTICATION_FAILED"),
            "TLS Error: TLS key negotiation failed" to ClassifiedFailure("TLS_HANDSHAKE", "TLS_NEGOTIATION_FAILED"),
            "VERIFY ERROR: certificate verify failed" to ClassifiedFailure("TLS_HANDSHAKE", "CERTIFICATE_VERIFY_FAILED"),
            "RESOLVE: Cannot resolve host address" to ClassifiedFailure("DNS_RESOLVE", "DNS_RESOLUTION_FAILED"),
            "TCP: connect failed: Connection refused" to ClassifiedFailure("TRANSPORT_CONNECT", "CONNECTION_REFUSED"),
            "Network is unreachable" to ClassifiedFailure("TRANSPORT_CONNECT", "NETWORK_UNREACHABLE"),
        )

        cases.forEach { (line, expected) ->
            val classifier = OpenVpnLogClassifier()
            classifier.observe(line)
            assertEquals(expected, classifier.failure)
        }

        val connected = OpenVpnLogClassifier()
        connected.observe("Initialization Sequence Completed")
        assertTrue(connected.connected)
    }

    private fun assertSafeCommand(command: List<String>) {
        assertEquals("/usr/sbin/openvpn", command.first())
        assertFalse(command.any { it == "sh" || it == "bash" || it == "-c" })
        val configIndex = command.indexOf("--config")
        val safetyOptions = listOf(
            "--auth-user-pass",
            "--client",
            "--nobind",
            "--dev",
            "--ifconfig-noexec",
            "--route-noexec",
            "--route-nopull",
            "--script-security",
            "--auth-nocache",
            "--connect-retry-max",
            "--resolv-retry",
            "--server-poll-timeout",
            "--single-session",
            "--tls-exit",
        )
        safetyOptions.forEach { option ->
            assertTrue(command.indexOf(option) > configIndex, option)
        }
        assertEquals("null", command[command.indexOf("--dev") + 1])
        assertEquals("0", command[command.indexOf("--script-security") + 1])
        assertEquals("1", command[command.indexOf("--connect-retry-max") + 1])
    }

    private fun assertDirectoryEmpty(directory: Path) {
        Files.list(directory).use { entries -> assertEquals(0L, entries.count()) }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private class FakeManagedProcess(
    outputText: String,
    private val clock: FakeClock,
    private val exitImmediately: Boolean,
    private val gracefulDestroyStops: Boolean,
) : ManagedProcess {
    private val outputConsumed = CountDownLatch(1)
    override val output: InputStream = object : ByteArrayInputStream(outputText.toByteArray()) {
        override fun read(): Int = super.read().also { if (it == -1) outputConsumed.countDown() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it == -1) outputConsumed.countDown() }
    }
    private var alive = true
    override val isAlive: Boolean
        get() = alive
    var destroyCalled = false
    var forceDestroyCalled = false
    var waitCalls = 0

    override fun waitFor(timeoutMillis: Long): Boolean {
        waitCalls++
        if (!alive) return true
        if (exitImmediately) {
            alive = false
            return true
        }
        outputConsumed.await(1, TimeUnit.SECONDS)
        clock.advance(minOf(timeoutMillis, 100))
        Thread.yield()
        return false
    }

    override fun exitValue(): Int = if (alive) error("still alive") else 1

    override fun destroy() {
        destroyCalled = true
        if (gracefulDestroyStops) alive = false
    }

    override fun destroyForcibly() {
        forceDestroyCalled = true
        alive = false
    }

    override fun closeStreams() {
        output.close()
    }
}

private class BlockingManagedProcess : ManagedProcess {
    override val output: InputStream = ByteArrayInputStream(ByteArray(0))
    private var alive = true
    override val isAlive: Boolean
        get() = alive
    var destroyCalled = false

    override fun waitFor(timeoutMillis: Long): Boolean {
        if (!alive) return true
        Thread.sleep(minOf(timeoutMillis, 10))
        return false
    }

    override fun exitValue(): Int = if (alive) error("still alive") else 0

    override fun destroy() {
        destroyCalled = true
        alive = false
    }

    override fun destroyForcibly() {
        alive = false
    }

    override fun closeStreams() {
        output.close()
    }
}
