package application.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.specmatic.mcp.test.client.McpStdioTestClient
import io.specmatic.mcp.test.client.McpTestClient
import io.specmatic.mcp.test.client.use
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpecmaticMcpAutoTest {
    private val mapper = ObjectMapper()
    private val javaExecutable = ProcessHandle.current().info().command().orElseThrow()
    private val mainClass = "application.SpecmaticApplication"
    private val runtimeClasspath = System.getProperty("specmatic.mcp.runtimeClasspath")
    private val expectedToolResults = mapOf(
        "run_contract_test" to listOf("### Status: PASSED", "Tests run: 1, Successes: 1, Failures: 0, WIP: 0, Errors: 0"),
        "run_resiliency_test" to listOf("### Status: PASSED", "Boundary condition testing is enabled"),
        "manage_mock_server" to listOf("No mock servers are currently running."),
        "backward_compatibility_check" to listOf("### Status: BACKWARD COMPATIBLE", "Loading older specification"),
        "validate_examples" to listOf("### Status: PASSED", "[OK] Example is valid")
    )

    @Test
    fun `Specmatic MCP tools pass functional and generated resiliency tests over stdio`(
        @TempDir(cleanup = CleanupMode.ON_SUCCESS) tempDir: File
    ) {
        val workDir = tempDir.canonicalFile
        val spec = checkNotNull(javaClass.getResource("/mcp/echo.yaml")).readText()
        val specFile = prepareRepository(workDir, spec)
        val invalidApiRequests = AtomicInteger()
        val api = startEchoApi(invalidApiRequests)
        try {
            val apiArguments = mapOf(
                "openApiSpec" to spec,
                "apiBaseUrl" to "http://127.0.0.1:${api.address.port}",
                "specFormat" to "yaml"
            )
            val dictionary = mapOf(
                "run_contract_test" to apiArguments,
                "run_resiliency_test" to apiArguments,
                "manage_mock_server" to mapOf("command" to "list", "openApiSpec" to spec, "port" to 0, "specFormat" to "yaml"),
                "backward_compatibility_check" to mapOf(
                    "repoDir" to specFile.parent, "baseBranch" to "base", "targetPath" to specFile.name
                ),
                "validate_examples" to mapOf("contractFile" to specFile.path, "examplesDir" to specFile.parent)
            )

            val functional = runAutoTest(workDir.resolve("functional"), dictionary)
            assertFunctionalResults(functional)
            assertThat(invalidApiRequests.get()).describedAs("Invalid requests sent by run_resiliency_test").isPositive()

            val resiliency = runAutoTest(workDir.resolve("resiliency"), dictionary, "--enable-resiliency-tests")
            assertThat(resiliency.filter { it.path("verdict").asText() != "PASSED" }.map {
                "${it.path("name").asText()}: ${it.path("error").asText()}"
            }).describedAs("Failed generated scenarios").isEmpty()

            val toolsWithNegativeTests = resiliency.filter { it.path("negative").asBoolean() }.map { it.path("toolName").asText() }
            assertThat(toolsWithNegativeTests.toSet()).containsExactlyInAnyOrderElementsOf(expectedToolResults.keys)
        } finally {
            api.stop(0)
        }
    }

    @Test
    fun `mock server serves the contract and releases its port after stopping over MCP`(
        @TempDir(cleanup = CleanupMode.ON_SUCCESS) tempDir: File
    ) {
        val workDir = tempDir.canonicalFile
        val spec = checkNotNull(javaClass.getResource("/mcp/echo.yaml")).readText()
        val address = InetSocketAddress("127.0.0.1", 0)
        val port = ServerSocket().use { socket ->
            socket.bind(address)
            socket.localPort
        }
        val client = McpStdioTestClient(
            command = javaExecutable,
            args = serverArgs(workDir, writeLauncherArgs(workDir)),
            workingDirectory = workDir
        )

        runBlocking {
            client.use { session ->
                val started = session.manageMock("start", mapOf("openApiSpec" to spec, "port" to port))
                assertThat(started).contains("Mock server started successfully", "Port: $port")
                assertThat(session.manageMock("list")).contains("http://localhost:$port")

                val connection = URI("http://127.0.0.1:$port/echo?value=42").toURL()
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    assertThat(connection.responseCode).isEqualTo(200)
                    val response = connection.inputStream.use { mapper.readTree(it) }
                    assertThat(response).isEqualTo(mapper.readTree("""{"value":42}"""))
                } finally {
                    connection.disconnect()
                }

                val stopped = session.manageMock("stop", mapOf("port" to port))
                assertThat(stopped).contains("Mock server stopped successfully", "Port: $port")
                assertThat(session.manageMock("list")).contains("No mock servers are currently running.")
                assertThatThrownBy {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 5_000) }
                }.isInstanceOf(ConnectException::class.java)
                Unit
            }
        }
    }

    private suspend fun McpTestClient.manageMock(command: String, args: Map<String, Any> = emptyMap()): String {
        val response = toolCall("manage_mock_server", args + ("command" to command))
        assertThat(response.error).describedAs("manage_mock_server $command JSON-RPC error").isNull()
        val result = checkNotNull(response.result) { "manage_mock_server $command returned no result" }
        assertThat(result.path("isError").asBoolean()).describedAs("manage_mock_server $command result: $result").isFalse()
        return result.path("content").joinToString("\n") { it.path("text").asText() }
    }

    private fun startEchoApi(invalidRequests: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/echo") { exchange ->
                exchange.use {
                    val value = exchange.requestURI.rawQuery.orEmpty()
                        .split('&')
                        .firstOrNull { it.startsWith("value=") }
                        ?.substringAfter('=')
                        ?.let { URLDecoder.decode(it, Charsets.UTF_8).toBigIntegerOrNull() }
                    val payload = if (value != null) {
                        mapper.writeValueAsBytes(mapOf("value" to value))
                    } else {
                        invalidRequests.incrementAndGet()
                        mapper.writeValueAsBytes(mapOf("message" to "value must be an integer"))
                    }
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(if (value == null) 400 else 200, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
            }
            start()
        }

    private fun prepareRepository(workDir: File, spec: String): File {
        val repo = workDir.resolve("repository").apply { mkdir() }
        val specFile = repo.resolve("echo.yaml").apply { writeText(spec) }

        fun git(vararg args: String) = runProcess(repo, workDir.resolve("git.log"), listOf("git", *args), timeoutSeconds = 15)

        git("init", "-q", "-b", "base")
        git("add", specFile.name)
        git(
            "-c", "user.name=MCP smoke test", "-c", "user.email=mcp-smoke@example.invalid",
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=${workDir.path}",
            "commit", "-qm", "Base contract"
        )
        git("checkout", "-qb", "candidate")
        specFile.writeText(spec.replace("title: MCP smoke test API", "title: Updated MCP smoke test API"))
        return specFile
    }

    private fun runAutoTest(
        workDir: File,
        dictionary: Map<String, Map<String, Any>>,
        vararg flags: String
    ): JsonNode {
        workDir.mkdir()
        val dictionaryFile = workDir.resolve("dictionary.json")
        mapper.writeValue(dictionaryFile, dictionary)
        workDir.resolve("specmatic.yaml").writeText("version: 3\n")
        val launcherArgs = writeLauncherArgs(workDir)
        runProcess(
            workDir,
            workDir.resolve("auto-test.log"),
            listOf(
                javaExecutable, "@${launcherArgs.path}",
                "mcp", "test", "--transport-kind", "STDIO", "--command", javaExecutable,
                *serverArgs(workDir, launcherArgs).map { "--arg=$it" }.toTypedArray(),
                "--dictionary-file", dictionaryFile.path, *flags
            ),
            timeoutSeconds = 180
        )

        val reportDir = workDir.resolve("build/reports/specmatic/mcp")
        val tools = mapper.readTree(reportDir.resolve("tools_schema.json")).map { it.path("name").asText() }
        assertThat(tools).containsExactlyInAnyOrderElementsOf(expectedToolResults.keys)
        return mapper.readTree(reportDir.resolve("mcp_test_report.json"))
    }

    private fun writeLauncherArgs(workDir: File): File {
        val classpath = checkNotNull(runtimeClasspath) { "Run this test through :specmatic-executable:test" }
        // The classpath can exceed the Windows command-line limit.
        return workDir.resolve("java.args").apply {
            val quotedClasspath = classpath.replace("\\", "\\\\").replace("\"", "\\\"")
            writeText("-cp\n\"$quotedClasspath\"\n$mainClass\n")
        }
    }

    private fun serverArgs(workDir: File, launcherArgs: File): List<String> {
        val launcher = System.getProperty("specmatic.mcp.serverJar")?.let { listOf("-jar", it) }
            ?: listOf("@${launcherArgs.path}")
        return listOf("-Djava.io.tmpdir=${workDir.path}") + launcher + listOf("mcp", "server")
    }

    private fun assertFunctionalResults(results: JsonNode) {
        assertThat(results.map { it.path("toolName").asText() }).containsExactlyInAnyOrderElementsOf(expectedToolResults.keys)
        for (result in results) {
            val tool = result.path("toolName").asText()
            val response = result.path("response").path("result")
            val content = response.path("content").joinToString("\n") { it.path("text").asText() }
            assertThat(result.path("verdict").asText()).describedAs("$tool verdict: $result").isEqualTo("PASSED")
            assertThat(response.path("isError").asBoolean()).describedAs("$tool response: $content").isFalse()
            assertThat(content).describedAs("$tool response").contains(*expectedToolResults.getValue(tool).toTypedArray())
        }
    }

    private fun runProcess(directory: File, log: File, command: List<String>, timeoutSeconds: Long) {
        val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertThat(process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
                .withFailMessage { "Process timed out after ${timeoutSeconds}s. See $log:\n${log.readText()}" }.isTrue()
            assertThat(process.exitValue())
                .withFailMessage { "Process exited with status ${process.exitValue()}. See $log:\n${log.readText()}" }.isZero()
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }
}
