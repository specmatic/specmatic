package application

import application.validate.OpenApiValidator
import application.validate.ValidateCommand
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.StubServerWatcher
import io.specmatic.mock.ScenarioStub
import io.specmatic.test.SpecmaticJUnitSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class FormUrlEncodedExampleFlowTest {
    @AfterEach
    fun cleanup() {
        SpecmaticJUnitSupport.settingsStaging.remove()
        System.clearProperty(Flags.CONFIG_FILE_PATH)
    }

    @Test
    fun `stub command loads form-urlencoded external examples and reports missing form fields as missing properties`(@TempDir tempDir: File) {
        val fixture = formUrlEncodedFixture(tempDir)
        val port = randomFreePort()
        val watchMaker = mockk<WatchMaker>()
        val watcher = mockk<StubServerWatcher>(relaxUnitFun = true)
        every { watchMaker.make(any()) } returns watcher
        val command = StubCommand(watchMaker = watchMaker).also { it.registerShutdownHook = false }

        try {
            val exitCode = CommandLine(command).execute(
                "--config", fixture.configFile.canonicalPath,
                "--examples", fixture.examplesDir.canonicalPath,
                "--host", "localhost",
                "--port", port.toString(),
                fixture.specFile.canonicalPath
            )

            assertThat(exitCode).isZero()

            val matchingExampleResponse = command.httpStub!!.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/token",
                    headers = mapOf(CONTENT_TYPE to FORM_URLENCODED),
                    formFields = VALID_FORM_FIELDS
                )
            )
            assertThat(matchingExampleResponse.status).isEqualTo(200)
            assertThat(matchingExampleResponse.body.toStringLiteral()).contains("example-token")

            val missingFieldResponse = command.httpStub!!.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/token",
                    headers = mapOf(CONTENT_TYPE to FORM_URLENCODED),
                    formFields = VALID_FORM_FIELDS.minus("client_id")
                )
            )
            assertThat(missingFieldResponse.status).isEqualTo(400)
            assertThat(missingFieldResponse.body.toStringLiteral()).contains("R2001: Missing required property")
            assertThat(missingFieldResponse.body.toStringLiteral()).contains("REQUEST.FORM-FIELDS.client_id")
            assertThat(missingFieldResponse.body.toStringLiteral()).doesNotContain("R2003: Unknown property")
        } finally {
            command.httpStub?.close()
        }
    }

    @Test
    fun `test command uses form-urlencoded external examples`(@TempDir tempDir: File) {
        val fixture = formUrlEncodedFixture(tempDir)

        TokenServer().use { server ->
            val exitCode = CommandLine(TestCommand()).execute(
                fixture.specFile.canonicalPath,
                "--config", fixture.configFile.canonicalPath,
                "--examples", fixture.examplesDir.canonicalPath,
                "--testBaseURL", server.baseUrl,
                "--strict"
            )

            assertThat(server.tokenRequests).containsExactly(VALID_FORM_FIELDS)
            assertThat(exitCode).isZero()
        }
    }

    @Test
    fun `validate command validates form-urlencoded external examples`(@TempDir tempDir: File) {
        val fixture = formUrlEncodedFixture(tempDir)

        val (output, exitCode) = Flags.using(Flags.CONFIG_FILE_PATH to fixture.configFile.canonicalPath) {
            captureStandardOutput(redirectStdErrToStdout = true) {
                CommandLine(validateCommand(tempDir)).execute("--spec-file", fixture.specFile.name)
            }
        }

        assertThat(exitCode).isZero()
        assertThat(output).contains(fixture.validExampleFile.name)
    }

    @Test
    fun `validate command reports missing form fields in external examples as missing properties`(@TempDir tempDir: File) {
        val fixture = formUrlEncodedFixture(tempDir, formFields = VALID_FORM_FIELDS.minus("client_id"))

        val (output, exitCode) = Flags.using(Flags.CONFIG_FILE_PATH to fixture.configFile.canonicalPath) {
            captureStandardOutput(redirectStdErrToStdout = true) {
                CommandLine(validateCommand(tempDir)).execute("--spec-file", fixture.specFile.name)
            }
        }

        assertThat(exitCode).isEqualTo(1)
        assertThat(output).contains(fixture.validExampleFile.name)
        assertThat(output).contains("R2001: Missing required property")
        assertThat(output).contains("REQUEST.FORM-FIELDS.client_id")
        assertThat(output).doesNotContain("R2003: Unknown property")
    }

    private fun validateCommand(tempDir: File): ValidateCommand {
        return ValidateCommand(
            validator = OpenApiValidator(),
            specmaticConfig = io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault(tempDir.resolve("specmatic.yaml").canonicalPath),
            currentDirectoryProvider = { tempDir.canonicalFile }
        )
    }

    private fun formUrlEncodedFixture(
        tempDir: File,
        formFields: Map<String, String> = VALID_FORM_FIELDS
    ): FormUrlEncodedFixture {
        val configFile = tempDir.resolve("specmatic.yaml").also {
            it.writeText("version: 2\n")
        }
        val specFile = tempDir.resolve("token.yaml").also {
            it.writeText(tokenSpec())
        }
        val examplesDir = tempDir.resolve("token_examples").also { it.mkdirs() }
        val exampleFile = examplesDir.resolve("valid-token-example.json").also {
            it.writeText(
                ScenarioStub(
                    request = HttpRequest(
                        method = "POST",
                        path = "/token",
                        headers = mapOf(CONTENT_TYPE to FORM_URLENCODED),
                        formFields = formFields
                    ),
                    response = HttpResponse(
                        status = 200,
                        headers = mapOf(CONTENT_TYPE to "application/json"),
                        body = io.specmatic.core.pattern.parsedJSONObject("""{"access_token":"example-token"}""")
                    )
                ).toJSON().toStringLiteral()
            )
        }

        return FormUrlEncodedFixture(configFile, specFile, examplesDir, exampleFile)
    }

    private fun tokenSpec(): String {
        return """
            openapi: 3.0.3
            info:
              title: Token API
              version: '1.0'
            paths:
              /token:
                post:
                  requestBody:
                    required: true
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          required:
                            - client_id
                            - client_secret
                            - grant_type
                            - scope
                          properties:
                            client_id:
                              type: string
                            client_secret:
                              type: string
                            grant_type:
                              type: string
                            scope:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - access_token
                            properties:
                              access_token:
                                type: string
                    '400':
                      description: Bad Request
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - error
                            properties:
                              error:
                                type: string
        """.trimIndent()
    }

    private fun randomFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private data class FormUrlEncodedFixture(
        val configFile: File,
        val specFile: File,
        val examplesDir: File,
        val validExampleFile: File
    )

    private class TokenServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(randomFreePort()), 0)
        val tokenRequests: MutableList<Map<String, String>> = CopyOnWriteArrayList()
        val baseUrl: String = "http://localhost:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                if (exchange.requestMethod == "POST" && exchange.requestURI.path == "/token") {
                    handleToken(exchange)
                } else {
                    exchange.send(404, """{"error":"not found"}""")
                }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }

        private fun handleToken(exchange: HttpExchange) {
            val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val formFields = requestBody.parseFormFields()
            tokenRequests.add(formFields)

            if (formFields == VALID_FORM_FIELDS) {
                exchange.responseHeaders.set(CONTENT_TYPE, "application/json")
                exchange.send(200, """{"access_token":"example-token"}""")
            } else {
                exchange.responseHeaders.set(CONTENT_TYPE, "application/json")
                exchange.send(400, """{"error":"unexpected form fields"}""")
            }
        }

        private fun HttpExchange.send(status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun String.parseFormFields(): Map<String, String> {
            if (isBlank()) return emptyMap()

            return split("&").associate { pair ->
                val parts = pair.split("=", limit = 2)
                parts[0].decodeFormComponent() to parts.getOrElse(1) { "" }.decodeFormComponent()
            }
        }

        private fun String.decodeFormComponent(): String {
            return URLDecoder.decode(this, StandardCharsets.UTF_8.name())
        }

        private fun randomFreePort(): Int {
            return ServerSocket(0).use { it.localPort }
        }
    }

    private companion object {
        const val FORM_URLENCODED = "application/x-www-form-urlencoded"
        val VALID_FORM_FIELDS = mapOf(
            "client_id" to "client-123",
            "client_secret" to "secret-456",
            "grant_type" to "client_credentials",
            "scope" to "dummy/.default"
        )
    }
}
