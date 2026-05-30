package application

import application.validate.OpenApiValidator
import application.validate.ValidateCommand
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.StubServerWatcher
import io.specmatic.test.SpecmaticJUnitSupport
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ObjectQueryParameterExamplesCommandTest {
    @AfterEach
    fun cleanup() {
        System.clearProperty(CONFIG_FILE_PATH)
        System.clearProperty(Flags.SPECMATIC_BASE_URL)
        SpecmaticJUnitSupport.settingsStaging.remove()
        SpecmaticJUnitSupport.partialSuccesses.clear()
    }

    @Test
    fun `StubCommand serves external object query example using serialized property keys`(@TempDir tempDir: File) {
        val specFile = writeObjectQuerySpec(tempDir, includeInlineExample = true)
        val examplesDir = writeExternalExample(tempDir)
        val configFile = writeSpecmaticYaml(tempDir)
        val port = randomFreePort()
        val watcher = mockk<StubServerWatcher>(relaxUnitFun = true)
        val watchMaker = mockk<WatchMaker>()
        every { watchMaker.make(any()) } returns watcher
        every { watcher.watchForChanges(any()) } just Runs
        val command = StubCommand(watchMaker = watchMaker).also {
            it.registerShutdownHook = false
        }

        try {
            val exitCode = CommandLine(command).execute(
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--strict",
                "--config", configFile.canonicalPath,
                "--examples", examplesDir.canonicalPath,
                specFile.canonicalPath,
            )
            assertThat(exitCode).isZero()
            runBlocking { command.checkReadiness() }

            val connection = URL("http://127.0.0.1:$port/data?customerId=external&segment=live").openConnection() as HttpURLConnection

            assertThat(connection.responseCode).isEqualTo(200)
            assertThat(parsedJSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                .isEqualTo(parsedJSONObject("""{"source":"external"}"""))
        } finally {
            command.close()
        }
    }

    @Test
    fun `TestCommand sends external object query example using serialized property keys`(@TempDir tempDir: File) {
        val specFile = writeObjectQuerySpec(tempDir, includeInlineExample = false)
        val examplesDir = writeExternalExample(tempDir)
        val configFile = writeSpecmaticYaml(tempDir)

        RecordingServer().use { server ->
            val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
                CommandLine(TestCommand()).execute(
                    "--config", configFile.canonicalPath,
                    "--testBaseURL", server.baseUrl,
                    "--strict",
                    "--examples", examplesDir.canonicalPath,
                    specFile.canonicalPath,
                )
            }

            assertThat(exitCode).withFailMessage(output).isZero()
            assertThat(server.receivedQueryParams).anySatisfy { queryParams ->
                assertThat(queryParams).containsEntry("customerId", "external")
                assertThat(queryParams).containsEntry("segment", "live")
                assertThat(queryParams).doesNotContainKey("info")
            }
        }
    }

    @Test
    fun `ValidateCommand validates inline and external object query examples using serialized property keys`(@TempDir tempDir: File) {
        val specFile = writeObjectQuerySpec(tempDir, includeInlineExample = true)
        writeExternalExample(tempDir)
        val specmaticConfig = loadedConfig(tempDir)

        val command = ValidateCommand(
            validator = OpenApiValidator(),
            specmaticConfig = specmaticConfig,
            currentDirectoryProvider = { tempDir.canonicalFile },
        )

        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(command).execute("--spec-file", specFile.canonicalPath)
        }

        assertThat(exitCode).withFailMessage(output).isZero()
        assertThat(output).contains("Inline examples (Total: 1)")
        assertThat(output).contains("Specification examples (Total: 1)")
        assertThat(output).contains("Examples       | Passed:     2 | Failed:     0 | Total:     2")
        assertThat(output).contains("OVERALL RESULT: PASSED")
    }

    private fun writeObjectQuerySpec(baseDir: File, includeInlineExample: Boolean): File {
        val spec = if (includeInlineExample) {
            """
            openapi: 3.0.0
            info:
              title: Object Query Command Flow
              version: 1.0.0
            paths:
              /data:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      style: form
                      explode: true
                      schema:
                        type: object
                        required:
                          - customerId
                        properties:
                          customerId:
                            type: string
                          segment:
                            type: string
                      examples:
                        INLINE_OK:
                          value:
                            customerId: inline
                            segment: preview
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - source
                            properties:
                              source:
                                type: string
                          examples:
                            INLINE_OK:
                              value:
                                source: inline
            """.trimIndent()
        } else {
            """
            openapi: 3.0.0
            info:
              title: Object Query Command Flow
              version: 1.0.0
            paths:
              /data:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      style: form
                      explode: true
                      schema:
                        type: object
                        required:
                          - customerId
                        properties:
                          customerId:
                            type: string
                          segment:
                            type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - source
                            properties:
                              source:
                                type: string
            """.trimIndent()
        }

        return baseDir.resolve("object-query.yaml").also { specFile ->
            specFile.writeText(spec)
        }
    }

    private fun writeExternalExample(baseDir: File): File {
        val examplesDir = baseDir.resolve("object-query_examples").also { it.mkdirs() }
        examplesDir.resolve("external-success.json").writeText(
            """
            {
              "http-request": {
                "method": "GET",
                "path": "/data",
                "query": {
                  "customerId": "external",
                  "segment": "live"
                }
              },
              "http-response": {
                "status": 200,
                "headers": {
                  "Content-Type": "application/json"
                },
                "body": {
                  "source": "external"
                }
              }
            }
            """.trimIndent()
        )
        return examplesDir
    }

    private fun writeSpecmaticYaml(baseDir: File): File {
        return baseDir.resolve("specmatic.yaml").also { configFile ->
            configFile.writeText(
                """
                version: 2
                reportDirPath: ${baseDir.resolve("reports").canonicalPath}
                """.trimIndent()
            )
        }
    }

    private fun loadedConfig(baseDir: File): SpecmaticConfig {
        val configFile = writeSpecmaticYaml(baseDir)
        System.setProperty(CONFIG_FILE_PATH, configFile.canonicalPath)
        return loadSpecmaticConfigIfAvailableElseDefault(configFile.canonicalPath)
    }

    private fun randomFreePort(): Int = ServerSocket(0).use { it.localPort }

    private class RecordingServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val receivedQueryParams: MutableList<Map<String, String>> = CopyOnWriteArrayList()
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange ->
                val queryParams = queryParamsFrom(exchange.requestURI.rawQuery)
                receivedQueryParams.add(queryParams)
                val matchesExternalExample = exchange.requestMethod == "GET" &&
                    exchange.requestURI.path == "/data" &&
                    queryParams["customerId"] == "external" &&
                    !queryParams.containsKey("info")

                val status = if (matchesExternalExample) 200 else 404
                val response = if (matchesExternalExample) """{"source":"external"}""" else """{"error":"unexpected query"}"""
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }

        private fun queryParamsFrom(rawQuery: String?): Map<String, String> {
            return rawQuery.orEmpty()
                .split("&")
                .filter(String::isNotBlank)
                .associate { parameter ->
                    val parts = parameter.split("=", limit = 2)
                    decode(parts[0]) to decode(parts.getOrElse(1) { "" })
                }
        }

        private fun decode(value: String): String {
            return URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
    }
}
