package io.specmatic.test

import io.specmatic.core.ApplicationApiSource
import io.specmatic.core.HttpResponse
import io.specmatic.core.log.InfoLogger
import io.specmatic.core.log.withLogger
import io.specmatic.core.pattern.parsedJsonValue
import io.specmatic.test.reports.coverage.OpenApiCoverage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ConnectException

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
        val sourceUrl = "http://unavailable.example"
        val coverage = coverage()
        val discovery = discovery { throw ConnectException("Connection refused") }

        val output = captureStdout {
            assertDoesNotThrow {
                discovery.discover(listOf(sourceCase.source(sourceUrl)), coverage)
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
        val sourceUrl = "http://unavailable.example"
        val discovery = discovery { HttpResponse(status = 503) }

        val output = captureStdout {
            discovery.discover(listOf(sourceCase.source(sourceUrl)), coverage())
        }

        assertThat(output).contains(
            "ERROR",
            "explicitly configured ${sourceCase.displayName}",
            sourceUrl,
            "Received HTTP status 503",
        )
    }

    @Test
    fun `should report malformed Swagger documents from an explicit URL`() {
        val sourceUrl = "http://swagger.example"
        val discovery = discovery {
            HttpResponse(status = 200, body = parsedJsonValue("""{"not":"openapi"}"""))
        }

        val output = captureStdout {
            discovery.discover(listOf(ApplicationApiSource.Swagger(sourceUrl)), coverage())
        }

        assertThat(output).contains("ERROR", "explicitly configured Swagger URL", sourceUrl)
    }

    @Test
    fun `should report malformed actuator responses from an explicit URL`() {
        val sourceUrl = "http://actuator.example"
        val discovery = discovery {
            HttpResponse(status = 200, body = parsedJsonValue("""{"not":"actuator"}"""))
        }

        val output = captureStdout {
            discovery.discover(listOf(ApplicationApiSource.Actuator(sourceUrl)), coverage())
        }

        assertThat(output).contains("ERROR", "explicitly configured actuator URL", sourceUrl)
    }

    @Test
    fun `should not report connectivity errors for an inferred application api source`() {
        val sourceUrl = "http://inferred.example"
        val discovery = discovery { throw ConnectException("Connection refused") }

        val output = captureStdout {
            discovery.discover(
                listOf(ApplicationApiSource.Swagger(sourceUrl, isExplicitlyConfigured = false)),
                coverage(),
            )
        }

        assertThat(output).doesNotContain("ERROR", "explicitly configured")
    }

    @Test
    fun `should continue to successful source after an explicit source is unreachable`() {
        val unavailableUrl = "http://unavailable.example/openapi.yaml"
        val successfulUrl = "http://successful.example/openapi.yaml"
        val requestedUrls = mutableListOf<String>()
        val sourceClient = ApplicationApiSourceClient { applicationApiSourceUrl ->
            requestedUrls.add(applicationApiSourceUrl)
            when (applicationApiSourceUrl) {
                unavailableUrl -> throw ConnectException("Connection refused")
                successfulUrl -> HttpResponse(
                    status = 200,
                    body = parsedJsonValue(openApiJson("customers-app", "/customers/internal")),
                )
                else -> error("Unexpected application API source URL $applicationApiSourceUrl")
            }
        }
        val coverage = coverage()

        val output = captureStdout {
            ApplicationApiDiscovery(sourceClient).discover(
                listOf(
                    ApplicationApiSource.Swagger(unavailableUrl),
                    ApplicationApiSource.Swagger(successfulUrl),
                ),
                coverage,
            )
        }

        assertThat(requestedUrls).containsExactly(unavailableUrl, successfulUrl)
        assertThat(output).contains("ERROR", unavailableUrl)
        assertThat(coverage.getApplicationAPIs()).contains(API("GET", "/customers/internal"))
        assertThat(coverage.isEndpointsApiSet()).isTrue()
    }

    @ParameterizedTest
    @MethodSource("emptyApplicationApiSourceCases")
    fun `should accept valid application api responses containing no APIs`(
        sourceCase: ApplicationApiSourceCase,
        responseBody: String,
    ) {
        val discovery = discovery {
            HttpResponse(status = 200, body = parsedJsonValue(responseBody))
        }
        val coverage = coverage()

        val output = captureStdout {
            discovery.discover(listOf(sourceCase.source("http://application.example")), coverage)
        }

        assertThat(output).doesNotContain("ERROR")
        assertThat(coverage.isEndpointsApiSet()).isTrue()
        assertThat(coverage.getApplicationAPIs()).isEmpty()
    }

    private fun discovery(sourceClient: ApplicationApiSourceClient): ApplicationApiDiscovery {
        return ApplicationApiDiscovery(sourceClient)
    }

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

enum class ApplicationApiSourceCase(val displayName: String) {
    ACTUATOR("actuator URL"),
    SWAGGER("Swagger URL"),
    SWAGGER_UI("Swagger UI base URL");

    fun source(url: String): ApplicationApiSource {
        return when (this) {
            ACTUATOR -> ApplicationApiSource.Actuator(url)
            SWAGGER -> ApplicationApiSource.Swagger(url)
            SWAGGER_UI -> ApplicationApiSource.SwaggerUi(url)
        }
    }
}
