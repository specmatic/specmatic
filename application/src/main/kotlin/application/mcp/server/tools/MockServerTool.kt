package application.mcp.server.tools

import application.StubCommand
import io.specmatic.core.config.Switch
import io.specmatic.stub.waitUntilConnectable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import picocli.CommandLine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ManageMockServerArgs(
    val command: String,
    val specFilePath: String? = null,
    val port: Int = 9000,
    val specFormat: String = "yaml"
)

class MockServerTool {
    private val runningMocks = ConcurrentHashMap<Int, StubCommand>()

    internal fun manageMockServer(args: ManageMockServerArgs): String = when (args.command) {
        "start" -> startMockServer(
            specFilePath = args.specFilePath
                ?: throw IllegalArgumentException("specFilePath is required for 'start' command"),
            port = args.port,
            specFormat = args.specFormat
        )
        "stop" -> stopMockServer(args.port)
        "list" -> listMockServers()
        else -> throw IllegalArgumentException("command must be one of: start, stop, list")
    }

    private fun startMockServer(specFilePath: String, port: Int, specFormat: String): String {
        validatePortAvailability(port)?.let { return it }

        return try {
            val command = StubCommand()
            val argsList = stubCommandArgs(port, File(specFilePath))

            command.registerShutdownHook = false

            startInBackground(command, port, argsList)

            System.err.println("[MockServerTool] Waiting for mock server to become reachable on $port...")
            val ready = runBlocking {
                waitUntilConnectable("127.0.0.1", port, 10.seconds)
            }

            if (!ready) {
                System.err.println("[MockServerTool] Timeout waiting for mock server on port $port.")
                cleanupFailedStart(command)
                return startFailure("The mock server did not become reachable on port $port within 10 seconds.")
            }

            runningMocks[port] = command


            mockServerMessage(
                "Mock server started successfully",
                listOf(
                "Server URL: http://localhost:$port",
                "Port: $port",
                "Status: Running in-process",
                "Spec file: ${File(specFilePath).canonicalPath}"
            )
            )
        } catch (e: Throwable) {
            cleanupFailedStart()
            startFailure(e.message ?: "Unknown error")
        }
    }

    private fun stopMockServer(port: Int): String {
        if (!runningMocks.containsKey(port)) {
            return stopFailure("No mock server is running on port $port.")
        }

        return try {
            stopServer(port)
            mockServerMessage("Mock server stopped successfully", listOf(stopMessage(port)))
        } catch (e: Throwable) {
            stopFailure(e.message ?: "Unknown error")
        }
    }

    private fun listMockServers(): String {
        val runningMockServers = runningMocks.keys.sorted()

        return buildString {
            append("## Specmatic Mock Server Management\n\n")
            append("### Running Mock Servers: ${runningMockServers.size}\n\n")

            if (runningMockServers.isEmpty()) {
                append("> No mock servers are currently running.\n")
            } else {
                runningMockServers.forEach { port ->
                    append("- **http://localhost:$port** (in-process)\n")
                }
            }
        }
    }

    private fun startInBackground(command: StubCommand, port: Int, args: List<String>) {
        thread(name = "specmatic-mock-port-$port") {
            try {
                System.err.println("[MockServerTool] Starting StubCommand on port $port in background thread...")
                CommandLine(command).execute(*args.toTypedArray())
            } catch (e: Throwable) {
                System.err.println("[MockServerTool] Error in StubCommand background thread: ${e.message}")
                e.printStackTrace(System.err)
            } finally {
                System.err.println("[MockServerTool] StubCommand background thread for port $port exiting.")
            }
        }
    }

    private fun validatePortAvailability(port: Int): String? {
        if (runningMocks.containsKey(port)) {
            return startFailure("Port $port is already in use by a mock server running in this process.")
        }

        return null
    }

    private fun stubCommandArgs(port: Int, specFile: File): List<String> {
        return listOf(
            "--port",
            port.toString(),
            "--host",
            "127.0.0.1",
            "--noConsoleLog",
            "--hot-reload",
            "disabled",
            specFile.canonicalPath
        )
    }

    private fun cleanupFailedStart(command: StubCommand? = null) {
        command?.close()
    }

    private fun stopServer(port: Int) {
        runningMocks.remove(port)?.close()
    }

    private fun stopMessage(port: Int): String {
        return "Port: $port (In-process)"
    }

    private fun startFailure(message: String): String {
        return mockServerMessage("Failed to start mock server", listOf(message))
    }

    private fun stopFailure(message: String): String {
        return mockServerMessage("Failed to stop mock server", listOf(message))
    }

    private fun mockServerMessage(title: String, lines: List<String>): String {
        return buildString {
            append("## Specmatic Mock Server Management\n\n")
            append("### $title\n\n")

            lines.forEach { line -> append("- $line\n") }
        }.trimEnd()
    }
}
