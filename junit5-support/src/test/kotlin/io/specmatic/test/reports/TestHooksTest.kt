package io.specmatic.test.reports

import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.reports.ReportingResponseHandlerProvider.Companion.HANDLED_RESPONSE_PATH
import io.specmatic.test.reports.ReportingResponseHandlerProvider.Companion.TRANSIENT_RESPONSE_STATUS
import io.specmatic.test.utils.ContractTestScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestHooksTest {
    @Test
    fun `onTestResult should report updated negative scenario for generative tests`(@TempDir tempDir: File) {
        val specYaml = """
        openapi: 3.0.0
        info:
          title: TestHooks generative reporting
          version: 1.0.0
        paths:
          /products:
            get:
              parameters:
                - in: header
                  name: X-Header
                  required: true
                  schema:
                    type: integer
              responses:
                '200':
                  description: OK
                '400':
                  description: Bad request
        """.trimIndent()

        ContractTestScope.from(specYaml, tempDir).execute(v3Config(tempDir).enableResiliencyTests()) { server ->
            server.on("/products", "GET") {
                header("X-Header", "(number)")
                respond(200); otherwise(400)
            }
        }.verify { listener ->
            assertThat(listener.testResults).hasSize(listener.dynamicTests.size)
            val negativeResults = listener.testResults.filter { it.scenario.generativePrefix.contains("-ve") }
            assertThat(negativeResults).isNotEmpty
            assertThat(negativeResults).allSatisfy { testExecutionResult ->
                assertThat(testExecutionResult.testRecord.result).isEqualTo(TestResult.Success)
                assertThat(testExecutionResult.scenario.status).isEqualTo(400)
                assertThat(testExecutionResult.result.scenario).isNotNull
                val scenario = testExecutionResult.result.scenario as Scenario
                assertThat(scenario.status).isEqualTo(400)
            }
        }
    }

    @Test
    fun `onTestResult reports a handled transient response before the terminal response`(@TempDir tempDir: File) {
        ContractTestScope.from(handledResponseSpecYaml(), tempDir).execute(v3Config(tempDir)) { server ->
            server.on(HANDLED_RESPONSE_PATH, "GET") {
                respond(HttpResponse(status = TRANSIENT_RESPONSE_STATUS))
                times(1)
            }
            server.on(HANDLED_RESPONSE_PATH, "GET") {
                respond(200)
            }
        }.verify { listener ->
            assertThat(listener.testResults).hasSize(1).allSatisfy { record ->
                assertThat(record.testRecord.result).isEqualTo(TestResult.Success)
                assertThat(record.request).hasSize(record.response.size).hasSize(2)
                assertThat(record.response.mapNotNull { it?.status }).containsExactly(TRANSIENT_RESPONSE_STATUS, 200)
            }
        }
    }

    @Test
    fun `onTestResult reports every response when handling is exhausted`(@TempDir tempDir: File) {
        ContractTestScope.from(handledResponseSpecYaml(), tempDir).execute(v3Config(tempDir)) { server ->
            server.on(HANDLED_RESPONSE_PATH, "GET") {
                respond(HttpResponse(status = TRANSIENT_RESPONSE_STATUS))
            }
        }.verify { listener ->
            assertThat(listener.testResults).hasSize(1).allSatisfy { record ->
                assertThat(record.testRecord.result).isEqualTo(TestResult.Failed)
                assertThat(record.request).hasSize(record.response.size).hasSize(4)
                assertThat(record.response.mapNotNull { it?.status }).containsExactly(
                    TRANSIENT_RESPONSE_STATUS,
                    TRANSIENT_RESPONSE_STATUS,
                    TRANSIENT_RESPONSE_STATUS,
                    TRANSIENT_RESPONSE_STATUS,
                )
            }
        }
    }
}

private fun handledResponseSpecYaml() = """
    openapi: 3.0.0
    info:
      title: TestHooks handled response reporting
      version: 1.0.0
    paths:
      $HANDLED_RESPONSE_PATH:
        get:
          responses:
            '200':
              description: OK
""".trimIndent()

private fun v3Config(tempDir: File) =
    tempDir.resolve("specmatic.yaml").apply {
        writeText("version: 3")
    }.toSpecmaticConfig()
