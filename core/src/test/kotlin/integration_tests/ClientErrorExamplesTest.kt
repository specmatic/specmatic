package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.Substitution
import io.specmatic.core.isResponseContentTypeAccepted
import io.specmatic.core.examples.source.DirectoryExampleSource
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.mock.ScenarioStub
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.RequestValidator
import io.specmatic.test.TestExecutor
import io.specmatic.test.interceptor.ContractTestInterceptor
import io.specmatic.test.interceptor.InterceptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class ClientErrorExamplesTest {
    @TempDir
    lateinit var tempDir: File

    @ParameterizedTest
    @CsvSource("400,false", "422,false", "406,false", "409,false", "406,true", "409,true")
    fun `explicit client error examples preserve invalid request values through execution`(status: Int, intercepted: Boolean) {
        val feature = loadFeature(status)
        val tests = feature.generateContractTests().toList()
        assertThat(tests).hasSize(1)
        var executions = 0
        val result = ContractTestInterceptor.withLoaderForTest({ if (intercepted) ProcessingInterceptor() else null }) {
            tests.single().runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    executions++
                    assertThat(request.headers["Accept"]).isEqualTo("text/plain")
                    val body = request.body as JSONObjectValue
                    assertThat(body.jsonObject["id"]?.toStringLiteral()).isEqualTo("invalid")
                    assertThat(body.jsonObject["name"]).isInstanceOf(StringValue::class.java)
                    if (intercepted) assertThat(request.headers).containsEntry("X-Processed", "true")
                    return errorResponse(status)
                }
            }).result
        }
        assertThat(executions).isEqualTo(1)
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
    }

    @Test
    fun `explicit 406 still validates the response after preserving an invalid request`() {
        val test = loadFeature(406).generateContractTests().single()
        var executions = 0
        val result = test.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                executions++
                return HttpResponse(status = 406, headers = mapOf("Content-Type" to "application/json"), body = """{"error":123}""")
            }
        }).result
        assertThat(executions).isEqualTo(1)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains("error")
    }

    @Test
    fun `example derived negative tests preserve invalid requests after interceptor processing and accept declared 406`() {
        val feature = loadFeature(406, includeSuccessExample = true).let {
            it.copy(specmaticConfig = it.specmaticConfig.enableResiliencyTests(onlyPositive = false))
        }
        val tests = feature.generateContractTests().toList()
        val negatives = tests.filter { it.scenario.isNegative }
        assertThat(negatives).isNotEmpty()
        assertThat(negatives.mapNotNull { it.scenario.generateHttpRequest().headers["Accept"] })
            .anyMatch { !isResponseContentTypeAccepted(it, "application/json") }
        var executions = 0
        ContractTestInterceptor.withLoaderForTest({ ProcessingInterceptor() }) {
            negatives.forEach { test ->
                val result = test.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        executions++
                        assertThat(request.headers).containsEntry("X-Processed", "true")
                        return errorResponse(406)
                    }
                }).result
                assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
            }
        }
        assertThat(executions).isEqualTo(negatives.size)
    }

    @Test
    fun `explicit 406 preserves a body that cannot be parsed using the request schema`() {
        val test = loadFeature(406, requestBody = StringValue("not-an-object")).generateContractTests().single()
        var executions = 0
        val result = test.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                executions++
                assertThat(request.body).isEqualTo(StringValue("not-an-object"))
                return errorResponse(406)
            }
        }).result
        assertThat(executions).isEqualTo(1)
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
    }

    @ParameterizedTest
    @CsvSource("false,method", "false,path", "false,contentType", "true,method", "true,path", "true,contentType")
    fun `interceptor processing still checks operation identity for intentional invalid requests`(negative: Boolean, changedField: String) {
        val feature = loadFeature(406, includeSuccessExample = negative).let {
            if (negative) it.copy(specmaticConfig = it.specmaticConfig.enableResiliencyTests(onlyPositive = false)) else it
        }
        val test = feature.generateContractTests().first { it.scenario.isNegative == negative }
        val interceptor = object : ProcessingInterceptor() {
            override fun updateRequest(testScenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest, substitution: Substitution): InterceptResult<HttpRequest> {
                val changedRequest = when (changedField) {
                    "method" -> httpRequest.copy(method = "DELETE")
                    "path" -> httpRequest.copy(path = "/unrelated")
                    else -> httpRequest.copy(headers = httpRequest.headers + ("Content-Type" to "application/xml"))
                }
                return InterceptResult.Processed(HasValue(changedRequest))
            }
        }
        var executions = 0
        val result = ContractTestInterceptor.withLoaderForTest({ interceptor }) {
            test.runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    executions++
                    return errorResponse(406)
                }
            }).result
        }
        assertThat(executions).isZero()
        assertThat(result.isSuccess()).isFalse()
    }

    @Test
    fun `custom request validators remain authoritative for explicit client error examples`() {
        val test = loadFeature(406).generateContractTests().single().withRequestValidator(object : RequestValidator {
            override fun validate(feature: Feature, scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest): Result =
                Result.Failure("custom validation failed")
        })
        var executions = 0
        val result = ContractTestInterceptor.withLoaderForTest({ ProcessingInterceptor() }) {
            test.runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    executions++
                    return errorResponse(406)
                }
            }).result
        }
        assertThat(executions).isZero()
        assertThat(result.reportString()).contains("custom validation failed")
    }

    private fun loadFeature(
        status: Int,
        includeSuccessExample: Boolean = false,
        requestBody: Value = parsedJSONObject("""{"id":"invalid","name":"(string)"}""")
    ): Feature {
        val spec = tempDir.resolve("api.yaml").also { it.writeText(specification(status)) }
        val examples = tempDir.resolve("examples").also(File::mkdirs)
        val request = HttpRequest(
            method = "POST", path = "/orders",
            headers = mapOf("Content-Type" to "application/json", "Accept" to "text/plain"),
            body = requestBody
        )
        examples.resolve("client-error.json").writeText(ScenarioStub(request, errorResponse(status)).toJSON().toStringLiteral())
        if (includeSuccessExample) {
            examples.resolve("success.json").writeText(
                ScenarioStub(
                    request.copy(headers = request.headers + ("Accept" to "application/json"), body = parsedJSONObject("""{"id":10,"name":"order"}""")),
                    HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"), body = parsedJSONObject("{}"))
                ).toJSON().toStringLiteral()
            )
        }
        val (feature, unused) = OpenApiSpecification.fromFile(spec.canonicalPath).toFeature()
            .loadExternalisedExamplesAndListUnloadableExamples(
                DirectoryExampleSource(listOf(examples.canonicalPath), strictMode = false, specmaticConfig = SpecmaticConfig())
            )
        assertThat(unused).isEmpty()
        // Keep the successful scenario only when it supplies the source example for negative generation.
        return feature.copy(scenarios = feature.scenarios.filter { includeSuccessExample || it.status == status })
    }

    private fun errorResponse(status: Int) = HttpResponse(
        status = status, headers = mapOf("Content-Type" to "application/json"), body = parsedJSONObject("""{"error":"rejected"}""")
    )

    private fun specification(status: Int) = """
        openapi: 3.0.3
        info:
          title: Client error examples
          version: 1.0.0
        paths:
          /orders:
            post:
              parameters:
                - in: header
                  name: Accept
                  required: true
                  schema:
                    type: string
                    enum: [application/json]
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required: [id, name]
                      properties:
                        id:
                          type: integer
                        name:
                          type: string
              responses:
                '200':
                  description: Accepted
                  content:
                    application/json:
                      schema:
                        type: object
                '$status':
                  description: Rejected
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [error]
                        properties:
                          error:
                            type: string
    """.trimIndent()

    private open class ProcessingInterceptor : ContractTestInterceptor {
        override fun updateRequest(testScenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest, substitution: Substitution): InterceptResult<HttpRequest> =
            InterceptResult.Processed(HasValue(httpRequest.copy(headers = httpRequest.headers + ("X-Processed" to "true"))))

        override fun updateSubstitution(testScenario: Scenario, originalScenario: Scenario, httpResponse: HttpResponse, substitution: Substitution): InterceptResult<Substitution> =
            InterceptResult.PassThrough
    }
}
