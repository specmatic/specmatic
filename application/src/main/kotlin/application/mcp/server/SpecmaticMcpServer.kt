package application.mcp.server

import application.SpecmaticApplication
import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import application.StubLoaderEngine
import application.updateNamesInJUnitXML
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.specmatic.core.*
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.stub.waitUntilConnectable
import io.specmatic.test.ContractTestSettings
import io.specmatic.test.SpecmaticJUnitSupport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.io.*
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener
import org.w3c.dom.Element
import java.io.*
import java.net.URI
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

@Serializable
data class RunTestArgs(
    val openApiSpec: String,
    val apiBaseUrl: String,
    val specFormat: String = "yaml"
)

@Serializable
data class ManageMockServerArgs(
    val command: String,
    val openApiSpec: String? = null,
    val port: Int = 9000,
    val specFormat: String = "yaml"
)

@Serializable
data class BackwardCompatArgs(
    val targetPath: String? = null,
    val baseBranch: String? = null,
    val repoDir: String? = null
)

class SpecmaticMcpServer : AutoCloseable {
    private val stubLoaderEngine = StubLoaderEngine()
    private val systemIoLock = Any()
    private val mcpQuietModeProperty = "specmatic.mcp.quiet"
    private val mockRegistryFile = File(System.getProperty("java.io.tmpdir"))
        .resolve("specmatic-mcp")
        .resolve("mock-servers.json")

    private val server = Server(
        Implementation(
            name = "specmatic-mcp-server",
            version = "1.0.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    init {
        registerTools()
    }

    private fun registerTools() {
        server.addTool(
            name = "run_contract_test",
            description = "Run Specmatic contract tests against an API using OpenAPI specification",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("openApiSpec") {
                        put("type", "string")
                        put("description", "The OpenAPI specification content (YAML or JSON)")
                    }
                    putJsonObject("apiBaseUrl") {
                        put("type", "string")
                        put("description", "The base URL of the API to test against")
                    }
                    putJsonObject("specFormat") {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec")
                        putJsonArray("enum") { add("yaml"); add("json") }
                        put("default", "yaml")
                    }
                },
                required = listOf("openApiSpec", "apiBaseUrl")
            )
        ) { request ->
            safeToolCall {
                val args = McpJson.decodeFromJsonElement<RunTestArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                runContractTest(args, resiliency = false)
            }
        }

        server.addTool(
            name = "run_resiliency_test",
            description = "Run Specmatic resiliency tests with boundary condition testing against an API using OpenAPI specification",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("openApiSpec") {
                        put("type", "string")
                        put("description", "The OpenAPI specification content (YAML or JSON)")
                    }
                    putJsonObject("apiBaseUrl") {
                        put("type", "string")
                        put("description", "The base URL of the API to test against")
                    }
                    putJsonObject("specFormat") {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec")
                        putJsonArray("enum") { add("yaml"); add("json") }
                        put("default", "yaml")
                    }
                },
                required = listOf("openApiSpec", "apiBaseUrl")
            )
        ) { request ->
            safeToolCall {
                val args = McpJson.decodeFromJsonElement<RunTestArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                runContractTest(args, resiliency = true)
            }
        }

        server.addTool(
            name = "manage_mock_server",
            description = "Manage Specmatic mock servers: start, stop, or list running servers",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "The action to perform on mock servers")
                        putJsonArray("enum") { add("start"); add("stop"); add("list") }
                    }
                    putJsonObject("openApiSpec") {
                        put("type", "string")
                        put("description", "The OpenAPI specification content (YAML or JSON) - required for 'start' command")
                    }
                    putJsonObject("port") {
                        put("type", "number")
                        put("description", "Port number for the mock server - required for 'start' and 'stop' commands")
                        put("default", 9000)
                    }
                    putJsonObject("specFormat") {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec - used with 'start' command")
                        putJsonArray("enum") { add("yaml"); add("json") }
                        put("default", "yaml")
                    }
                },
                required = listOf("command")
            )
        ) { request ->
            safeToolCall {
                val args = McpJson.decodeFromJsonElement<ManageMockServerArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                manageMockServer(args)
            }
        }

        server.addTool(
            name = "backward_compatibility_check",
            description = "Check for breaking changes in OpenAPI specifications using Specmatic's git-based analysis",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("targetPath") {
                        put("type", "string")
                        put("description", "File or folder path to analyze for backward compatibility")
                    }
                    putJsonObject("baseBranch") {
                        put("type", "string")
                        put("description", "Git branch to compare against")
                    }
                    putJsonObject("repoDir") {
                        put("type", "string")
                        put("description", "Repository directory")
                    }
                }
            )
        ) { request ->
            safeToolCall {
                val args = McpJson.decodeFromJsonElement<BackwardCompatArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                runBackwardCompatibilityCheck(args)
            }
        }
    }

    suspend fun run() {
        val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.`out`.asSink().buffered())
        server.createSession(transport)
    }

    internal fun runContractTest(args: RunTestArgs, resiliency: Boolean): String {
        val tempDir = createTempDirectory(if (resiliency) "specmatic-resiliency-" else "specmatic-contract-").toFile()
        val specFile = tempDir.resolve("spec.${args.specFormat}").apply { writeText(args.openApiSpec) }
        val reportDir = tempDir.resolve("reports").apply { mkdirs() }

        return try {
            val (_, stdout, stderr) = captureStandardStreams {
                val launcher = LauncherFactory.create()
                val request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClass(SpecmaticJUnitSupport::class.java))
                    .build()

                SpecmaticJUnitSupport.settingsStaging.set(
                    ContractTestSettings(
                        contractPaths = specFile.canonicalPath,
                        testBaseURL = args.apiBaseUrl,
                        reportBaseDirectory = reportDir.canonicalPath,
                        generative = resiliency
                    )
                )

                launcher.discover(request)
                val reportListener = LegacyXmlReportGeneratingListener(Paths.get(reportDir.canonicalPath), PrintWriter(System.out, true))
                launcher.registerTestExecutionListeners(reportListener)
                launcher.execute(request)

                val junitReport = reportDir.resolve("TEST-junit-jupiter.xml")
                if (junitReport.isFile) {
                    junitReport.writeText(updateNamesInJUnitXML(junitReport.readText()))
                }
            }

            val reportFile = reportDir.resolve("TEST-junit-jupiter.xml")
            val exitCode = if (reportFile.isFile) extractExitCode(reportFile) else if (stderr.isBlank()) 0 else 1
            val summary = if (reportFile.isFile) parseJUnitSummary(reportFile) else null

            formatTestResult(
                title = if (resiliency) "Resiliency" else "Contract",
                success = exitCode == 0,
                summary = summary,
                consoleOutput = stdout,
                errors = stderr,
                reportPath = reportFile.takeIf(File::isFile)?.canonicalPath,
                extraIntro = if (resiliency) "Boundary condition testing is enabled, so Specmatic also generates contract-invalid requests.\n\n" else ""
            )
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
            tempDir.deleteRecursively()
        }
    }

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

    internal fun runBackwardCompatibilityCheck(args: BackwardCompatArgs): String {
        val command = BackwardCompatibilityCheckCommandV2().apply {
            options.targetPath = args.targetPath
            options.baseBranch = args.baseBranch
            options.repoDir = args.repoDir
        }

        val (exitCode, stdout, stderr) = captureStandardStreams {
            command.call()
        }

        return buildString {
            append("# Specmatic Backward Compatibility Check\n\n")
            if (!args.targetPath.isNullOrBlank()) {
                append("File: `${args.targetPath}`\n\n")
            }

            if (exitCode == 0) {
                append("Status: BACKWARD COMPATIBLE\n\n")
            } else {
                append("Status: BREAKING CHANGES DETECTED OR CHECK FAILED\n\n")
            }

            if (stdout.isNotBlank()) {
                append("Detailed analysis:\n")
                append("```\n")
                append(stdout.trimEnd())
                append("\n```\n\n")
            }

            if (stderr.isNotBlank()) {
                append("Errors:\n")
                append("```\n")
                append(stderr.trimEnd())
                append("\n```\n")
            }
        }
    }

    private fun startMockServer(openApiSpec: String, port: Int, specFormat: String): String {
        val records = loadMockRegistry().filterAlive()
        if (records.any { it.port == port }) {
            return mockServerMessage(
                "Failed to start mock server",
                listOf("Port $port is already in use by another mock server started by Specmatic MCP.")
            )
        }

        val tempDir = createTempDirectory("specmatic-mock-").toFile()
        val specFile = tempDir.resolve("spec.$specFormat").apply { writeText(openApiSpec) }
        val stdoutLog = tempDir.resolve("mock.stdout.log")
        val stderrLog = tempDir.resolve("mock.stderr.log")

        return try {
            stubLoaderEngine.loadStubs(
                contractPathDataList = listOf(ContractPathData("", specFile.canonicalPath)),
                dataDirs = emptyList(),
                strictMode = false
            )

            val process = ProcessBuilder(
                resolveJavaExecutable(),
                "-jar",
                resolveCurrentJarPath(),
                "mock",
                specFile.canonicalPath,
                "--port=$port",
                "--host=127.0.0.1",
                "--noConsoleLog",
                "--hot-reload=disabled"
            )
                .directory(tempDir)
                .redirectOutput(stdoutLog)
                .redirectError(stderrLog)
                .start()

            val ready = runBlocking {
                waitUntilConnectable("127.0.0.1", port, 10.seconds)
            }

            if (!ready) {
                process.destroy()
                tempDir.deleteRecursively()
                return mockServerMessage(
                    "Failed to start mock server",
                    listOf(
                        "The mock server did not become reachable on port $port within 10 seconds.",
                        stderrLog.takeIf(File::isFile)?.let { "See log: ${it.canonicalPath}" } ?: "No stderr log available."
                    )
                )
            }

            val processId = runCatching { process.pid() }.getOrNull()
            val record = PersistedMockServerRecord(
                port = port,
                processId = processId ?: -1,
                tempDir = tempDir.canonicalPath,
                workDir = File(System.getProperty("user.dir")).canonicalPath,
                url = "http://localhost:$port"
            )
            saveMockRegistry(records + record)

            mockServerMessage(
                "Mock server started successfully",
                listOf(
                    "Server URL: http://localhost:$port",
                    "Port: $port"
                ) + listOfNotNull(
                    processId?.let { "Process ID: $it" },
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
        val records = loadMockRegistry().filterAlive()
        val server = records.firstOrNull { it.port == port }
            ?: return mockServerMessage("Failed to stop mock server", listOf("No mock server is running on port $port."))

        return try {
            stopProcess(server.processId)
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
                    if (server.processId > 0) append(" (pid: ${server.processId})")
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

    private fun extractExitCode(reportFile: File): Int {
        val summary = parseJUnitSummary(reportFile) ?: return 1
        return if (summary.failed == 0) 0 else 1
    }

    private fun parseJUnitSummary(reportFile: File): JUnitSummary? {
        if (!reportFile.isFile) return null

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = builder.parse(reportFile)
        val suite = document.documentElement ?: return null
        val tests = suite.getAttribute("tests").toIntOrNull() ?: 0
        val failures = suite.getAttribute("failures").toIntOrNull() ?: 0
        val errors = suite.getAttribute("errors").toIntOrNull() ?: 0
        val failed = failures + errors
        val passed = (tests - failed).coerceAtLeast(0)

        val failedTests = mutableListOf<FailedJUnitTest>()
        val testcases = suite.getElementsByTagName("testcase")
        for (index in 0 until testcases.length) {
            val testcase = testcases.item(index) as? Element ?: continue
            val failureNode = testcase.getElementsByTagName("failure").item(0) as? Element
            val errorNode = testcase.getElementsByTagName("error").item(0) as? Element
            val node = failureNode ?: errorNode ?: continue

            failedTests += FailedJUnitTest(
                scenario = testcase.getAttribute("name").ifBlank {
                    listOf(testcase.getAttribute("classname"), testcase.getAttribute("name"))
                        .filter { it.isNotBlank() }
                        .joinToString(".")
                },
                message = node.getAttribute("message").ifBlank { node.textContent.orEmpty().trim() }
            )
        }

        return JUnitSummary(
            total = tests,
            passed = passed,
            failed = failed,
            failedTests = failedTests
        )
    }

    private fun formatTestResult(
        title: String,
        success: Boolean,
        summary: JUnitSummary?,
        consoleOutput: String,
        errors: String,
        reportPath: String?,
        extraIntro: String = ""
    ): String {
        return buildString {
            append("# Specmatic $title Test Results\n\n")
            append(extraIntro)
            append("Status: ")
            append(if (success) "PASSED" else "FAILED")
            append("\n\n")

            summary?.let {
                append("Summary:\n")
                append("- Total tests: ${it.total}\n")
                append("- Passed: ${it.passed}\n")
                append("- Failed: ${it.failed}\n\n")

                if (reportPath != null) {
                    append("JUnit report: `$reportPath`\n\n")
                }

                if (it.failedTests.isNotEmpty()) {
                    append("Failed tests:\n")
                    it.failedTests.forEach { failedTest ->
                        append("- ${failedTest.scenario}")
                        if (failedTest.message.isNotBlank()) {
                            append(": ${failedTest.message}")
                        }
                        append('\n')
                    }
                    append('\n')
                }
            }

            if (consoleOutput.isNotBlank()) {
                append("Console output:\n")
                append("```\n")
                append(consoleOutput.trimEnd().take(4000))
                if (consoleOutput.length > 4000) {
                    append("\n... [truncated ${consoleOutput.length - 4000} characters]")
                }
                append("\n```\n\n")
            }

            if (errors.isNotBlank()) {
                append("Errors:\n")
                append("```\n")
                append(errors.trimEnd())
                append("\n```\n")
            }
        }
    }

    private fun <T> captureStandardStreams(block: () -> T): Triple<T, String, String> {
        synchronized(systemIoLock) {
            val originalOut = System.out
            val originalErr = System.err
            val originalQuietMode = System.getProperty(mcpQuietModeProperty)
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            System.setProperty(mcpQuietModeProperty, "true")

            return try {
                val result = block()
                Triple(result, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
                if (originalQuietMode == null) {
                    System.clearProperty(mcpQuietModeProperty)
                } else {
                    System.setProperty(mcpQuietModeProperty, originalQuietMode)
                }
            }
        }
    }

    override fun close() {
        runBlocking {
            server.close()
        }
    }

    private fun safeToolCall(block: () -> String): CallToolResult {
        return try {
            CallToolResult(
                content = listOf(TextContent(text = block())),
                isError = false
            )
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            CallToolResult(
                content = listOf(
                    TextContent(
                        text = buildString {
                            append("# Specmatic MCP Tool Error\n\n")
                            append("- ")
                            append(t.message ?: (t::class.simpleName ?: "Unknown error"))
                        }
                    )
                ),
                isError = true
            )
        }
    }

    private fun resolveCurrentJarPath(): String {
        System.getenv("SPECMATIC_MCP_JAR")
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it).canonicalPath }

        val codeSource = SpecmaticApplication::class.java.protectionDomain.codeSource?.location
            ?: error("Could not resolve Specmatic executable jar path")
        return File(URI(codeSource.toString())).canonicalPath
    }

    private fun resolveJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome).resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        return javaBin.canonicalPath
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun resolveMockWorkingDirectory(): File {
        val override = System.getenv("SPECMATIC_MCP_WORKDIR")?.takeIf { it.isNotBlank() }
        return File(override ?: System.getProperty("user.dir")).canonicalFile
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
        return filter { it.processId > 0 && ProcessHandle.of(it.processId).map(ProcessHandle::isAlive).orElse(false) }
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

private data class JUnitSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val failedTests: List<FailedJUnitTest>
)

private data class FailedJUnitTest(
    val scenario: String,
    val message: String
)
