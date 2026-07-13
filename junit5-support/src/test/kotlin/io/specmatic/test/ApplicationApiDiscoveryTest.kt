package io.specmatic.test

import io.specmatic.core.ApplicationApiSource
import io.specmatic.core.HttpResponse
import io.specmatic.core.log.InfoLogger
import io.specmatic.core.log.withLogger
import io.specmatic.core.pattern.parsedJsonValue
import io.specmatic.test.reports.coverage.OpenApiCoverage
import io.specmatic.test.utils.MockHttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket

class ApplicationApiDiscoveryTest {
    companion object {
        @JvmStatic
        fun explicitApplicationApiSourceCases(): List<Arguments> =
            ApplicationApiSourceCase.entries.map { Arguments.of(it) }

        @JvmStatic
        fun emptyApplicationApiSourceCases(): List<Arguments> =
            listOf(
                Arguments.of(ApplicationApiSourceCase.ACTUATOR, """{"contexts":{}}"""),
                Arguments.of(
                    ApplicationApiSourceCase.SWAGGER,
                    """{"openapi":"3.0.0","info":{"title":"empty","version":"1.0.0"},"paths":{}}""",
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("explicitApplicationApiSourceCases")
    fun `should report connectivity errors for explicit application api sources`(sourceCase: ApplicationApiSourceCase) {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val sourceUrl = "http://localhost:$unusedPort"
        val coverage = coverage()

        val output = captureStdout {
            assertDoesNotThrow {
                discovery().discover(listOf(sourceCase.source(sourceUrl)), coverage)
            }
        }

        assertThat(output)
            .contains("ERROR", "explicitly configured ${sourceCase.displayName}", sourceUrl)
            .containsIgnoringCase("connect")
        assertThat(coverage.isEndpointsApiSet()).isFalse()
    }

    @ParameterizedTest
    @MethodSource("explicitApplicationApiSourceCases")
    fun `should report non-200 responses for explicit application api sources`(sourceCase: ApplicationApiSourceCase) {
        MockHttpServer().use { server ->
            server.on(sourceCase.requestPath, "GET") { respond(503) }

            val output = captureStdout {
                discovery().discover(listOf(sourceCase.source(server.baseUrl)), coverage())
            }

            assertThat(output).contains(
                "ERROR",
                "explicitly configured ${sourceCase.displayName}",
                server.baseUrl,
                "Received HTTP status 503",
            )
        }
    }

    @Test
    fun `should report malformed Swagger documents from an explicit URL`() {
        MockHttpServer().use { server ->
            server.on("/", "GET") {
                respond(HttpResponse(status = 200, body = parsedJsonValue("""{"not":"openapi"}""")))
            }

            val output = captureStdout {
                discovery().discover(listOf(ApplicationApiSource.Swagger(server.baseUrl)), coverage())
            }

            assertThat(output).contains("ERROR", "explicitly configured Swagger URL", server.baseUrl)
        }
    }

    @Test
    fun `should report malformed actuator responses from an explicit URL`() {
        MockHttpServer().use { server ->
            server.on("/", "GET") {
                respond(HttpResponse(status = 200, body = parsedJsonValue("""{"not":"actuator"}""")))
            }

            val output = captureStdout {
                discovery().discover(listOf(ApplicationApiSource.Actuator(server.baseUrl)), coverage())
            }

            assertThat(output).contains("ERROR", "explicitly configured actuator URL", server.baseUrl)
        }
    }

    @Test
    fun `should not report connectivity errors for an inferred application api source`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val sourceUrl = "http://localhost:$unusedPort"

        val output = captureStdout {
            discovery().discover(
                listOf(ApplicationApiSource.Swagger(sourceUrl, isExplicitlyConfigured = false)),
                coverage(),
            )
        }

        assertThat(output).doesNotContain("ERROR", "explicitly configured")
    }

    @Test
    fun `should retain APIs from successful sources when another explicit source is unreachable`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        MockHttpServer().use { server ->
            server.on("/", "GET") {
                respond(HttpResponse(status = 200, body = parsedJsonValue(openApiJson("customers-app", "/customers/internal"))))
            }
            val unreachableUrl = "http://localhost:$unusedPort"
            val coverage = coverage()

            val output = captureStdout {
                discovery().discover(
                    listOf(
                        ApplicationApiSource.Swagger(unreachableUrl),
                        ApplicationApiSource.Swagger(server.baseUrl),
                    ),
                    coverage,
                )
            }

            assertThat(output).contains("ERROR", unreachableUrl)
            assertThat(coverage.getApplicationAPIs()).contains(API("GET", "/customers/internal"))
            assertThat(coverage.isEndpointsApiSet()).isTrue()
        }
    }

    @ParameterizedTest
    @MethodSource("emptyApplicationApiSourceCases")
    fun `should accept valid application api responses containing no APIs`(
        sourceCase: ApplicationApiSourceCase,
        responseBody: String,
    ) {
        MockHttpServer().use { server ->
            server.on(sourceCase.requestPath, "GET") {
                respond(HttpResponse(status = 200, body = parsedJsonValue(responseBody)))
            }
            val coverage = coverage()

            val output = captureStdout {
                discovery().discover(listOf(sourceCase.source(server.baseUrl)), coverage)
            }

            assertThat(output).doesNotContain("ERROR")
            assertThat(coverage.isEndpointsApiSet()).isTrue()
            assertThat(coverage.getApplicationAPIs()).isEmpty()
        }
    }

    private fun discovery() = ApplicationApiDiscovery(prettyPrint = true, keyDataFor = { null })

    private fun coverage() = OpenApiCoverage(configFilePath = "")

    private fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val outputStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outputStream))
        return try {
            withLogger(InfoLogger, block)
            outputStream.toString()
        } finally {
            System.out.flush()
            System.setOut(originalOut)
        }
    }

    private fun openApiJson(title: String, vararg paths: String): String {
        val pathsJson = paths.joinToString(",") { path ->
            """"$path":{"get":{"responses":{"200":{"description":"OK"}}}}"""
        }
        return """{"openapi":"3.0.0","info":{"title":"$title","version":"1.0.0"},"paths":{$pathsJson}}"""
    }
}

enum class ApplicationApiSourceCase(
    val displayName: String,
    val requestPath: String,
) {
    ACTUATOR("actuator URL", "/"),
    SWAGGER("Swagger URL", "/"),
    SWAGGER_UI("Swagger UI base URL", "/swagger/v1/swagger.yaml");

    fun source(url: String): ApplicationApiSource {
        return when (this) {
            ACTUATOR -> ApplicationApiSource.Actuator(url)
            SWAGGER -> ApplicationApiSource.Swagger(url)
            SWAGGER_UI -> ApplicationApiSource.SwaggerUi(url)
        }
    }
}
