package application.mcp.server.tools

import application.StubCommand
import io.specmatic.core.config.Switch
import io.specmatic.stub.waitUntilConnectable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import picocli.CommandLine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ManageMockServerArgs(
    val command: String,
    val openApiSpec: String? = null,
    val port: Int = 9000,
    val specFormat: String = "yaml"
)

class MockServerTool {
    private val runningMocks = ConcurrentHashMap<Int, StubCommand>()
    private val mockRegistryFile = File(System.getProperty("java.io.tmpdir"))
        .resolve("specmatic-mcp")
        .resolve("mock-servers.json")
    private val json = Json { ignoreUnknownKeys = true }

    internal fun manageMockServer(args: ManageMockServerArgs): String = when (args.command) {
        "start" -> startMockServer(
            openApiSpec = args.openApiSpec
                ?: throw IllegalArgumentException("openApiSpec is required for 'start' command"),
            port = args.port,
            specFormat = args.specFormat
        )
        "stop" -> stopMockServer(args.port)
        "list" -> listMockServers()
        else -> throw IllegalArgumentException("command must be one of: start, stop, list")
    }

    private fun startMockServer(openApiSpec: String, port: Int, specFormat: String): String {
        val activeServers = refreshRegistry()
        validatePortAvailability(port, activeServers)?.let { return it }

        val tempDir = createTempDirectory("specmatic-mock-").toFile()
        val specFile = tempDir.resolve("spec.$specFormat").apply { writeText(openApiSpec) }

        return try {
            val command = StubCommand()
            val argsList = mutableListOf<String>()
            argsList.add("--port")
            argsList.add(port.toString())
            argsList.add("--host")
            argsList.add("127.0.0.1")
            argsList.add("--noConsoleLog")
            argsList.add("--hot-reload")
            argsList.add("disabled")
            argsList.add(specFile.canonicalPath)

            command.registerShutdownHook = false

            startInBackground(command, port, argsList)

            System.err.println("[MockServerTool] Waiting for mock server to become reachable on $port...")
            val ready = runBlocking {
                waitUntilConnectable("127.0.0.1", port, 10.seconds)
            }

            if (!ready) {
                System.err.println("[MockServerTool] Timeout waiting for mock server on port $port.")
                cleanupFailedStart(command, tempDir)
                return startFailure("The mock server did not become reachable on port $port within 10 seconds.")
            }

            runningMocks[port] = command
            saveMockRegistry(activeServers + createRegistryRecord(port, tempDir))

            mockServerMessage(
                "Mock server started successfully",
                listOf(
                    "Server URL: http://localhost:$port",
                    "Port: $port",
                    "Status: Running in-process",
                    "Log directory: ${tempDir.canonicalPath}"
                )
            )
        } catch (e: Throwable) {
            cleanupFailedStart(tempDir = tempDir)
            startFailure(e.message ?: "Unknown error")
        }
    }

    private fun stopMockServer(port: Int): String {
        val activeServers = refreshRegistry()
        val server = activeServers.firstOrNull { it.port == port }
            ?: return stopFailure("No mock server is running on port $port.")

        return try {
            stopServer(server)
            saveMockRegistry(activeServers.filterNot { it.port == port })
            mockServerMessage("Mock server stopped successfully", listOf(stopMessage(port)))
        } catch (e: Throwable) {
            stopFailure(e.message ?: "Unknown error")
        }
    }

    private fun listMockServers(): String {
        val runningMockServers = refreshRegistry().sortedBy(PersistedMockServerRecord::port)

        return buildString {
            append("## Specmatic Mock Server Management\n\n")
            append("### Running Mock Servers: ${runningMockServers.size}\n\n")

            if (runningMockServers.isEmpty()) {
                append("> No mock servers are currently running.\n")
            } else {
                runningMockServers.forEach { server ->
                    append("- **${server.url}** (in-process)\n")
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

    private fun validatePortAvailability(port: Int, activeServers: List<PersistedMockServerRecord>): String? {
        if (runningMocks.containsKey(port)) {
            return startFailure("Port $port is already in use by a mock server running in this process.")
        }

        if (activeServers.any { it.port == port }) {
            return startFailure("Port $port is already in use by another mock server started by Specmatic MCP.")
        }

        return null
    }

    private fun createRegistryRecord(port: Int, tempDir: File): PersistedMockServerRecord {
        return PersistedMockServerRecord(
            port = port,
            tempDir = tempDir.canonicalPath,
            url = "http://localhost:$port"
        )
    }

    private fun cleanupFailedStart(command: StubCommand? = null, tempDir: File) {
        command?.close()
        tempDir.deleteRecursively()
    }

    private fun refreshRegistry(): List<PersistedMockServerRecord> {
        val activeServers = loadMockRegistry().filterAlive()
        saveMockRegistry(activeServers)
        return activeServers
    }

    private fun stopServer(server: PersistedMockServerRecord) {
        runningMocks.remove(server.port)?.close()
        File(server.tempDir).deleteRecursively()
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

    private fun loadMockRegistry(): List<PersistedMockServerRecord> {
        if (!mockRegistryFile.isFile) return emptyList()

        return runCatching {
            json.decodeFromString<List<PersistedMockServerRecord>>(mockRegistryFile.readText())
        }.getOrElse { emptyList() }
    }

    private fun saveMockRegistry(records: List<PersistedMockServerRecord>) {
        mockRegistryFile.parentFile.mkdirs()
        mockRegistryFile.writeText(json.encodeToString(records))
    }

    private fun List<PersistedMockServerRecord>.filterAlive(): List<PersistedMockServerRecord> {
        return filter { server -> runningMocks.containsKey(server.port) }
    }
}

@Serializable
private data class PersistedMockServerRecord(
    val port: Int,
    val tempDir: String,
    val url: String
)
