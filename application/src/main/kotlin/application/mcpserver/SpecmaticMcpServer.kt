package application.mcpserver

import application.SpecmaticApplication
import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import application.StubLoaderEngine
import application.updateNamesInJUnitXML
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.stub.waitUntilConnectable
import io.specmatic.test.ContractTestSettings
import io.specmatic.test.SpecmaticJUnitSupport
import kotlinx.coroutines.runBlocking
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

private const val JSON_RPC_VERSION = "2.0"
private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val INVALID_PARAMS = -32602
private const val METHOD_NOT_FOUND = -32601
private const val INTERNAL_ERROR = -32603

class StdioMcpServer(
    private val server: SpecmaticMcpServer,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val input: java.io.InputStream = System.`in`,
    private val output: java.io.OutputStream = System.out,
) {
    fun run(): Int {
        while (true) {
            val request = readMessage() ?: break
            val response = server.handle(request)
            if (response != null) {
                writeMessage(response)
            }
        }

        server.close()
        return 0
    }

    private fun readMessage(): JsonNode? {
        val headers = linkedMapOf<String, String>()

        while (true) {
            val line = readHeaderLine() ?: return if (headers.isEmpty()) null else error("Unexpected end of input while reading MCP headers")
            if (line.isBlank()) {
                if (headers.isEmpty()) continue
                break
            }

            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }

        val contentLength = headers["content-length"]?.toIntOrNull()
            ?: error("Missing Content-Length header in MCP request")

        val payload = input.readNBytes(contentLength)
        require(payload.size == contentLength) { "Expected $contentLength bytes but received ${payload.size}" }
        return mapper.readTree(payload)
    }

    private fun readHeaderLine(): String? {
        val buffer = ByteArrayOutputStream()

        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8)
            }

            if (next == '\n'.code) {
                break
            }

            if (next != '\r'.code) {
                buffer.write(next)
            }
        }

        return buffer.toString(Charsets.UTF_8)
    }

    private fun writeMessage(message: JsonNode) {
        val payload = mapper.writeValueAsBytes(message)
        val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
        output.write(header)
        output.write(payload)
        output.flush()
    }
}

class SpecmaticMcpServer(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val toolExecutor: SpecmaticMcpToolExecutor = DefaultSpecmaticMcpToolExecutor()
) : AutoCloseable {
    private val tools: List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "run_contract_test",
            description = "Run Specmatic contract tests against an API using OpenAPI specification",
            inputSchema = schema {
                put("type", "object")
                set<ObjectNode>("properties", schema {
                    set<ObjectNode>("openApiSpec", schemaProperty("string", "The OpenAPI specification content (YAML or JSON)"))
                    set<ObjectNode>("apiBaseUrl", schemaProperty("string", "The base URL of the API to test against"))
                    set<ObjectNode>("specFormat", schema {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec")
                        putArray("enum").apply {
                            add("yaml")
                            add("json")
                        }
                        put("default", "yaml")
                    })
                })
                putArray("required").apply {
                    add("openApiSpec")
                    add("apiBaseUrl")
                }
            }
        ),
        McpToolDefinition(
            name = "run_resiliency_test",
            description = "Run Specmatic resiliency tests with boundary condition testing against an API using OpenAPI specification",
            inputSchema = schema {
                put("type", "object")
                set<ObjectNode>("properties", schema {
                    set<ObjectNode>("openApiSpec", schemaProperty("string", "The OpenAPI specification content (YAML or JSON)"))
                    set<ObjectNode>("apiBaseUrl", schemaProperty("string", "The base URL of the API to test against"))
                    set<ObjectNode>("specFormat", schema {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec")
                        putArray("enum").apply {
                            add("yaml")
                            add("json")
                        }
                        put("default", "yaml")
                    })
                })
                putArray("required").apply {
                    add("openApiSpec")
                    add("apiBaseUrl")
                }
            }
        ),
        McpToolDefinition(
            name = "manage_mock_server",
            description = "Manage Specmatic mock servers: start, stop, or list running servers",
            inputSchema = schema {
                put("type", "object")
                set<ObjectNode>("properties", schema {
                    set<ObjectNode>("command", schema {
                        put("type", "string")
                        put("description", "The action to perform on mock servers")
                        putArray("enum").apply {
                            add("start")
                            add("stop")
                            add("list")
                        }
                    })
                    set<ObjectNode>("openApiSpec", schemaProperty("string", "The OpenAPI specification content (YAML or JSON) - required for 'start' command"))
                    set<ObjectNode>("port", schema {
                        put("type", "number")
                        put("description", "Port number for the mock server - required for 'start' and 'stop' commands")
                        put("default", 9000)
                    })
                    set<ObjectNode>("specFormat", schema {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec - used with 'start' command")
                        putArray("enum").apply {
                            add("yaml")
                            add("json")
                        }
                        put("default", "yaml")
                    })
                })
                putArray("required").apply {
                    add("command")
                }
            }
        ),
        McpToolDefinition(
            name = "backward_compatibility_check",
            description = "Check for breaking changes in OpenAPI specifications using Specmatic's git-based analysis",
            inputSchema = schema {
                put("type", "object")
                set<ObjectNode>("properties", schema {
                    set<ObjectNode>("targetPath", schemaProperty("string", "File or folder path to analyze for backward compatibility"))
                    set<ObjectNode>("baseBranch", schemaProperty("string", "Git branch to compare against"))
                    set<ObjectNode>("repoDir", schemaProperty("string", "Repository directory"))
                })
            }
        )
    )

    fun handle(request: JsonNode): JsonNode? {
        val id = request.get("id")
        val method = request.path("method").asText(null) ?: return errorResponse(id, INVALID_PARAMS, "Missing method")

        return try {
            when (method) {
                "initialize" -> successResponse(id, schema {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    set<ObjectNode>("capabilities", schema {
                        set<ObjectNode>("tools", schema { })
                    })
                    set<ObjectNode>("serverInfo", schema {
                        put("name", "specmatic-mcp-server")
                        put("version", "1.0.0")
                    })
                })

                "notifications/initialized" -> null

                "tools/list" -> successResponse(id, schema {
                    set<ArrayNode>("tools", mapper.createArrayNode().apply {
                        tools.forEach { tool ->
                            add(schema {
                                put("name", tool.name)
                                put("description", tool.description)
                                set<ObjectNode>("inputSchema", tool.inputSchema.deepCopy())
                            })
                        }
                    })
                })

                "tools/call" -> {
                    val params = request.path("params")
                    val name = params.path("name").asText(null)
                        ?: throw McpInvalidParams("Tool name is required")
                    val arguments = params.path("arguments").takeIf { !it.isMissingNode } ?: mapper.createObjectNode()

                    val resultText = when (name) {
                        "run_contract_test" -> toolExecutor.runContractTest(arguments, resiliency = false)
                        "run_resiliency_test" -> toolExecutor.runContractTest(arguments, resiliency = true)
                        "manage_mock_server" -> toolExecutor.manageMockServer(arguments)
                        "backward_compatibility_check" -> toolExecutor.runBackwardCompatibilityCheck(arguments)
                        else -> throw McpMethodNotFound("Unknown tool: $name")
                    }

                    successResponse(id, schema {
                        set<ArrayNode>("content", mapper.createArrayNode().apply {
                            add(schema {
                                put("type", "text")
                                put("text", resultText)
                            })
                        })
                    })
                }

                else -> throw McpMethodNotFound("Unknown method: $method")
            }
        } catch (e: McpMethodNotFound) {
            if (id == null || id.isMissingNode) null else errorResponse(id, METHOD_NOT_FOUND, e.message ?: "Method not found")
        } catch (e: McpInvalidParams) {
            if (id == null || id.isMissingNode) null else errorResponse(id, INVALID_PARAMS, e.message ?: "Invalid params")
        } catch (e: Throwable) {
            if (id == null || id.isMissingNode) null else errorResponse(id, INTERNAL_ERROR, e.message ?: "Internal error")
        }
    }

    private fun successResponse(id: JsonNode?, result: ObjectNode): ObjectNode {
        return schema {
            put("jsonrpc", JSON_RPC_VERSION)
            set<JsonNode>("id", id ?: mapper.nullNode())
            set<ObjectNode>("result", result)
        }
    }

    private fun errorResponse(id: JsonNode?, code: Int, message: String): ObjectNode {
        return schema {
            put("jsonrpc", JSON_RPC_VERSION)
            set<JsonNode>("id", id ?: mapper.nullNode())
            set<ObjectNode>("error", schema {
                put("code", code)
                put("message", message)
            })
        }
    }

    private fun schema(block: ObjectNode.() -> Unit): ObjectNode =
        mapper.createObjectNode().apply(block)

    private fun schemaProperty(type: String, description: String): ObjectNode =
        schema {
            put("type", type)
            put("description", description)
        }

    override fun close() {
        toolExecutor.close()
    }
}

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ObjectNode
)

class McpInvalidParams(message: String) : RuntimeException(message)
class McpMethodNotFound(message: String) : RuntimeException(message)

interface SpecmaticMcpToolExecutor : AutoCloseable {
    fun runContractTest(arguments: JsonNode, resiliency: Boolean): String
    fun manageMockServer(arguments: JsonNode): String
    fun runBackwardCompatibilityCheck(arguments: JsonNode): String
}

class DefaultSpecmaticMcpToolExecutor(
    private val stubLoaderEngine: StubLoaderEngine = StubLoaderEngine(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
) : SpecmaticMcpToolExecutor {
    private val systemIoLock = Any()
    private val mcpQuietModeProperty = "specmatic.mcp.quiet"
    private val mockRegistryFile = File(System.getProperty("java.io.tmpdir")).resolve("specmatic-mcp").resolve("mock-servers.json")

    override fun runContractTest(arguments: JsonNode, resiliency: Boolean): String {
        val openApiSpec = arguments.requiredText("openApiSpec")
        val apiBaseUrl = arguments.requiredText("apiBaseUrl")
        val specFormat = arguments.textOrDefault("specFormat", "yaml")

        val tempDir = createTempDirectory(if (resiliency) "specmatic-resiliency-" else "specmatic-contract-").toFile()
        val specFile = tempDir.resolve("spec.$specFormat").apply { writeText(openApiSpec) }
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
                        testBaseURL = apiBaseUrl,
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

    override fun manageMockServer(arguments: JsonNode): String {
        val command = arguments.requiredText("command")

        return when (command) {
            "start" -> {
                val openApiSpec = arguments.requiredText("openApiSpec")
                val port = arguments.intOrDefault("port", 9000)
                val specFormat = arguments.textOrDefault("specFormat", "yaml")
                startMockServer(openApiSpec, port, specFormat)
            }

            "stop" -> {
                val port = arguments.intOrDefault("port", 9000)
                stopMockServer(port)
            }

            "list" -> listMockServers()
            else -> throw McpInvalidParams("command must be one of: start, stop, list")
        }
    }

    override fun runBackwardCompatibilityCheck(arguments: JsonNode): String {
        val targetPath = arguments.optionalText("targetPath")
        val baseBranch = arguments.optionalText("baseBranch")
        val repoDir = arguments.optionalText("repoDir")

        val command = BackwardCompatibilityCheckCommandV2().apply {
            options.targetPath = targetPath
            options.baseBranch = baseBranch
            options.repoDir = repoDir
        }

        val (exitCode, stdout, stderr) = captureStandardStreams {
            command.call()
        }

        return buildString {
            append("# Specmatic Backward Compatibility Check\n\n")
            if (!targetPath.isNullOrBlank()) {
                append("File: `$targetPath`\n\n")
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

        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
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
    }

    private fun resolveCurrentJarPath(): String {
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

    private fun loadMockRegistry(): List<PersistedMockServerRecord> {
        if (!mockRegistryFile.isFile) return emptyList()
        val listType = objectMapper.typeFactory.constructCollectionType(
            List::class.java,
            PersistedMockServerRecord::class.java
        )

        return runCatching<List<PersistedMockServerRecord>> {
            objectMapper.readValue(mockRegistryFile, listType)
        }.getOrElse { emptyList<PersistedMockServerRecord>() }
    }

    private fun saveMockRegistry(records: List<PersistedMockServerRecord>) {
        mockRegistryFile.parentFile.mkdirs()
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(mockRegistryFile, records)
    }

    private fun List<PersistedMockServerRecord>.filterAlive(): List<PersistedMockServerRecord> {
        return filter { record ->
            record.processId > 0 &&
                ProcessHandle.of(record.processId)
                    .map { handle -> handle.isAlive }
                    .orElse(false)
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

private fun JsonNode.requiredText(name: String): String =
    optionalText(name) ?: throw McpInvalidParams("$name is required")

private fun JsonNode.optionalText(name: String): String? =
    get(name)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

private fun JsonNode.textOrDefault(name: String, defaultValue: String): String =
    optionalText(name) ?: defaultValue

private fun JsonNode.intOrDefault(name: String, defaultValue: Int): Int =
    get(name)?.takeUnless { it.isNull }?.asInt() ?: defaultValue
