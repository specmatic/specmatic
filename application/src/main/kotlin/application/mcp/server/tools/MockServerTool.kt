package application.mcp.server.tools

import application.StubCommand
import io.specmatic.core.config.Switch
import io.specmatic.stub.waitUntilConnectable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    internal fun manageMockServer(args: ManageMockServerArgs): String {
        return when (args.command) {
            "start" -> {
                val spec = args.openApiSpec ?: throw IllegalArgumentException("openApiSpec is required for 'start' command")
                startMockServer(spec, args.port, args.specFormat)
            }
            "stop" -> stopMockServer(args.port)
            "list" -> listMockServers()
            else -> throw IllegalArgumentException("command must be one of: start, stop, list")
        }
    }

    private fun startMockServer(openApiSpec: String, port: Int, specFormat: String): String {
        if (runningMocks.containsKey(port)) {
            return mockServerMessage(
                "Failed to start mock server",
                listOf("Port $port is already in use by a mock server running in this process.")
            )
        }

        val records = loadMockRegistry().filterAlive()
        if (records.any { it.port == port }) {
            return mockServerMessage(
                "Failed to start mock server",
                listOf("Port $port is already in use by another mock server started by Specmatic MCP.")
            )
        }

        val tempDir = createTempDirectory("specmatic-mock-").toFile()
        val specFile = tempDir.resolve("spec.$specFormat").apply { writeText(openApiSpec) }

        return try {
            val command = StubCommand().apply {
                contractPaths = listOf(specFile.canonicalPath)
                this.port = port
                host = "127.0.0.1"
                noConsoleLog = true
                hotReload = Switch.disabled
                registerShutdownHook = false
            }

            thread(name = "specmatic-mock-port-$port") {
                try {
                    System.err.println("[MockServerTool] Starting StubCommand on ${command.host}:${command.port} in background thread...")
                    command.call()
                } catch (e: Throwable) {
                    System.err.println("[MockServerTool] Error in StubCommand background thread: ${e.message}")
                    e.printStackTrace(System.err)
                } finally {
                    System.err.println("[MockServerTool] StubCommand background thread for port $port exiting.")
                }
            }

            System.err.println("[MockServerTool] Waiting for mock server to become reachable on $port...")
            val ready = runBlocking {
                waitUntilConnectable("127.0.0.1", port, 10.seconds)
            }

            if (!ready) {
                System.err.println("[MockServerTool] Timeout waiting for mock server on port $port. Command state: host=${command.host}, port=${command.port}")
                command.close()
                tempDir.deleteRecursively()
                return mockServerMessage(
                    "Failed to start mock server",
                    listOf("The mock server did not become reachable on port $port within 10 seconds.")
                )
            }

            runningMocks[port] = command

            val record = PersistedMockServerRecord(
                port = port,
                processId = -1, // In-process
                tempDir = tempDir.canonicalPath,
                workDir = File(System.getProperty("user.dir")).canonicalPath,
                url = "http://localhost:$port"
            )
            saveMockRegistry(records + record)

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
            tempDir.deleteRecursively()
            mockServerMessage(
                "Failed to start mock server",
                listOf(e.message ?: "Unknown error")
            )
        }
    }

    private fun stopMockServer(port: Int): String {
        val inProcessCommand = runningMocks.remove(port)
        if (inProcessCommand != null) {
            inProcessCommand.close()
            val records = loadMockRegistry().filterNot { it.port == port }
            saveMockRegistry(records)
            return mockServerMessage("Mock server stopped successfully", listOf("Port: $port (In-process)"))
        }

        val records = loadMockRegistry().filterAlive()
        val server = records.firstOrNull { it.port == port }
            ?: return mockServerMessage("Failed to stop mock server", listOf("No mock server is running on port $port."))

        return try {
            if (server.processId > 0) {
                stopProcess(server.processId)
            }
            saveMockRegistry(records.filterNot { it.port == port })
            File(server.tempDir).deleteRecursively()
            mockServerMessage("Mock server stopped successfully", listOf("Port: $port"))
        } catch (e: Throwable) {
            mockServerMessage("Failed to stop mock server", listOf(e.message ?: "Unknown error"))
        }
    }

    private fun listMockServers(): String {
        val runningMockServers = loadMockRegistry().filterAlive().sortedBy { it.port }
        saveMockRegistry(runningMockServers)
        return buildString {
            append("# Specmatic Mock Server Management\n\n")
            append("Running mock servers: ${runningMockServers.size}\n\n")

            if (runningMockServers.isEmpty()) {
                append("No mock servers are currently running.")
            } else {
                runningMockServers.forEach { server ->
                    append("- ${server.url}")
                    if (server.processId > 0) {
                        append(" (pid: ${server.processId})")
                    } else {
                        append(" (in-process)")
                    }
                    append('\n')
                }
            }
        }
    }

    private fun mockServerMessage(title: String, lines: List<String>): String {
        return buildString {
            append("# Specmatic Mock Server Management\n\n")
            append(title)
            append("\n\n")
            lines.forEach { line -> append("- $line\n") }
        }.trimEnd()
    }

    private fun loadMockRegistry(): List<PersistedMockServerRecord> {
        if (!mockRegistryFile.isFile) return emptyList()

        return runCatching {
            Json.decodeFromString<List<PersistedMockServerRecord>>(mockRegistryFile.readText())
        }.getOrElse { emptyList() }
    }

    private fun saveMockRegistry(records: List<PersistedMockServerRecord>) {
        mockRegistryFile.parentFile.mkdirs()
        mockRegistryFile.writeText(Json.encodeToString(records))
    }

    private fun List<PersistedMockServerRecord>.filterAlive(): List<PersistedMockServerRecord> {
        return filter { 
            if (it.processId > 0) {
                ProcessHandle.of(it.processId).map(ProcessHandle::isAlive).orElse(false)
            } else {
                runningMocks.containsKey(it.port)
            }
        }
    }

    private fun stopProcess(processId: Long) {
        val handle = ProcessHandle.of(processId).orElseThrow {
            IllegalStateException("No process found for pid $processId")
        }

        handle.destroy()
        repeat(20) {
            if (!handle.isAlive) return
            Thread.sleep(100)
        }

        handle.destroyForcibly()
        repeat(20) {
            if (!handle.isAlive) return
            Thread.sleep(100)
        }

        if (handle.isAlive) {
            error("Failed to stop process $processId")
        }
    }
}

@Serializable
private data class PersistedMockServerRecord(
    val port: Int,
    val processId: Long,
    val tempDir: String,
    val workDir: String,
    val url: String
)
