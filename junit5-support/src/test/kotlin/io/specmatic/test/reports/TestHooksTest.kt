package io.specmatic.test.reports

import io.ktor.http.HttpHeaders
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.reporter.model.TestResult
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

        ContractTestScope.from(specYaml, tempDir).execute(SpecmaticConfig().enableResiliencyTests()) { server ->
            server.on("/products", "GET") {
                header("X-Header", "(number)")
                respond(200); otherwise(400)
            }
        }.verify { listener ->
            assertThat(listener.testResults).hasSize(listener.dynamicTests.size)
            val negativeResults = listener.testResults.filter { it.scenario.generativePrefix.contains("-ve") }
            assertThat(negativeResults).isNotEmpty
            assertThat(negativeResults).allSatisfy { testExecutionResult ->
                assertThat(testExecutionResult.testResult).isEqualTo(TestResult.Success)
                assertThat(testExecutionResult.actualResponseStatus).isEqualTo(400)
                assertThat(testExecutionResult.scenario.status).isEqualTo(400)
                assertThat(testExecutionResult.result.scenario).isNotNull
                assertThat(testExecutionResult.result.scenario?.status).isEqualTo(400)
            }
        }
    }

    @Test
    fun `onTestResult should report retries for special handling cases like 429`(@TempDir tempDir: File) {
        val specYaml = """
        openapi: 3.0.0
        info:
          title: TestHooks special case reporting
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
                  examples:
                    TOO_MANY_REQUESTS:
                      value: 0
              responses:
                '200':
                  description: OK
                '429':
                  description: Bad request
                  headers:
                    X-Retry-After:
                      schema:
                        type: integer
                      examples:
                        TOO_MANY_REQUESTS:
                          value: 0
        """.trimIndent()

        ContractTestScope.from(specYaml, tempDir).execute { server ->
            server.on("/products", "GET") {
                header("X-Header", "0")
                respond(HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to "0")))
                times(3)
            }

            server.on("/products", "GET") {
                header("X-Header", "(number)")
                respond(200)
            }
        }.verify { listener ->
            assertThat(listener.testResults).hasSize(listener.dynamicTests.size).hasSize(2)
            assertThat(listener.testResults.filter { it.scenario.status == 429 }).hasSize(1).allSatisfy { record ->
                assertThat(record.actualResponseStatus).isEqualTo(429)
                assertThat(record.testResult).isEqualTo(TestResult.Success)
                assertThat(record.request).hasSize(record.response.size).hasSize(4)
                assertThat(record.response.mapNotNull { it?.status }).containsExactly(429, 429, 429, 200)
            }
        }
    }
}
