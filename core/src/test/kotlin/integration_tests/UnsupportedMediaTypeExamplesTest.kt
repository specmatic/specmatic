package integration_tests

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiLintViolations
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.DefaultMismatchMessages
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.examples.module.ExampleValidationModule
import io.specmatic.core.examples.source.DirectoryExampleSource
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Decision
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.test.TestExecutor
import io.specmatic.test.TestSkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File

class UnsupportedMediaTypeExamplesTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `external 415 example with unsupported request content type loads and runs as a contract test`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "post-text-plain",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain"
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.method == "POST" && it.status == 415 }
        assertThat(unsupportedMediaTypeScenario.hasExamples()).isTrue()
        assertThat(unsupportedMediaTypeScenario.requestContentType).isEqualTo("application/json")
        val generatedRequest = unsupportedMediaTypeScenario
            .newBasedOn(unsupportedMediaTypeScenario.examples.single().rows.single(), feature.flagsBased)
            .first()
            .value
            .generateHttpRequest()
        assertThat(generatedRequest.contentType()).isEqualTo("text/plain")
        assertThat(generatedRequest.body).isEqualTo(StringValue("request sent here"))

        var sawUnsupportedMediaTypeRequest = false
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.path).isEqualTo("/orders")

                if (request.contentType() == "application/json") {
                    return HttpResponse.ok("")
                }

                sawUnsupportedMediaTypeRequest = true
                assertThat(request.contentType()).isEqualTo("text/plain")
                assertThat(request.body).isEqualTo(StringValue("request sent here"))
                return HttpResponse(
                    status = 415,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = parsedJSONObject("""{"error":"occurred"}""")
                )
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(sawUnsupportedMediaTypeRequest).isTrue()
        assertThat(results.successCount).isEqualTo(2)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `external 415 example generates exactly one 415 test in every resiliency mode`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "post-text-plain",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain",
            extraRequestHeaders = mapOf("X-Request-Mode" to "example")
        )
        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()

        resiliencyModes().forEach { resiliencyMode ->
            val generated415Scenarios = feature
                .copy(specmaticConfig = specmaticConfigWith(resiliencyMode))
                .generateContractTestScenarios(emptyList())
                .filter { (originalScenario, _) -> originalScenario.status == 415 && originalScenario.hasExamples() }
                .toList()

            assertThat(generated415Scenarios).hasSize(1)
            val generatedRequest = generated415Scenarios.single().second.value.generateHttpRequest()
            assertThat(generatedRequest.contentType()).isEqualTo("text/plain")
            assertThat(generatedRequest.hasHeader("X-Request-Mode", "example")).isTrue()
        }
    }

    @Test
    fun `generated 415 request uses a content type not supported by the operation`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(ordersSpecWithParameterizedTextPlainRequestContentType()).canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }

        val generatedRequest = unsupportedMediaTypeScenario.generateHttpRequest()
        val generatedRequestV2 = unsupportedMediaTypeScenario.generateHttpRequestV2().single().value

        assertThat(generatedRequest.contentType()?.normalizedContentType()).isEqualTo("application/xml")
        assertThat(generatedRequestV2.contentType()?.normalizedContentType()).isEqualTo("application/xml")
    }

    @Test
    fun `external 415 example with unsupported request content type is served by mock`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "post-text-plain",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain"
        )
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        HttpStub(feature, listOf(ScenarioStub.readFromFile(exampleFile))).use { stub ->
            val response = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/orders",
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = StringValue("request sent here")
                )
            )

            assertThat(response.status).isEqualTo(415)
            assertThat(response.body).isEqualTo(parsedJSONObject("""{"error":"occurred"}"""))
        }
    }

    @Test
    fun `external 415 example without content type loads and is served by mock`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "post-no-content-type",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = null
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.method == "POST" && it.status == 415 }
        val generatedRequest = unsupportedMediaTypeScenario
            .newBasedOn(unsupportedMediaTypeScenario.examples.single().rows.single(), feature.flagsBased)
            .first()
            .value
            .generateHttpRequest()
        assertThat(generatedRequest.contentType()).isNull()
        assertThat(generatedRequest.headers).doesNotContainKey("Content-Type")
        assertThat(unsupportedMediaTypeScenario.requestContentType).isEqualTo("application/json")

        HttpStub(feature, listOf(ScenarioStub.readFromFile(exampleFile))).use { stub ->
            val response = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/orders",
                    body = StringValue("request sent here")
                )
            )

            assertThat(response.status).isEqualTo(415)
            assertThat(response.body).isEqualTo(parsedJSONObject("""{"error":"occurred"}"""))
        }
    }

    @Test
    fun `inline 415 examples are ignored because OpenAPI cannot declare an unsupported request media type`() {
        val specification = OpenApiSpecification.fromFile(writeSpec(inline415Spec()).canonicalPath)
        val collectorContext = CollectorContext()
        specification.toScenarioInfos(collectorContext)

        val warningReport = collectorContext.toCollector().toResult().reportString()
        assertThat(warningReport).contains(OpenApiLintViolations.REQUEST_REJECTION_RESPONSE_REQUIRES_EXTERNAL_EXAMPLE.id)
        assertThat(warningReport).contains("paths./orders.post.responses.415")
        assertThat(warningReport).contains("External 415 examples are still loaded and used")

        val feature = specification.toFeature()

        assertThat(feature.inlineNamedStubs).isEmpty()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                throw ContractException("Inline 415 examples should not be generated as tests")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.successCount).isEqualTo(0)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `415 response schemas without inline examples do not produce ignored response warnings`() {
        val specification = OpenApiSpecification.fromFile(writeSpec(ordersSpec()).canonicalPath)
        val collectorContext = CollectorContext()
        specification.toScenarioInfos(collectorContext)

        val warningReport = collectorContext.toCollector().toResult().reportString()
        assertThat(warningReport).doesNotContain(OpenApiLintViolations.REQUEST_REJECTION_RESPONSE_REQUIRES_EXTERNAL_EXAMPLE.id)
    }

    @Test
    fun `415 example using a supported request content type is rejected`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "post-json",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": { "data": "found" }""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "application/json"
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.method == "POST" && it.status == 415 }
        assertThat(unsupportedMediaTypeScenario.hasExamples()).isTrue()

        val exampleStub = ScenarioStub.readFromFile(exampleFile)
        val matchResult = unsupportedMediaTypeScenario.matches(
            exampleStub.request,
            exampleStub.response,
            mismatchMessages = DefaultMismatchMessages,
            flagsBased = feature.flagsBased
        )

        assertThat(matchResult).isInstanceOf(Result.Failure::class.java)
        val expectedMediaTypeError = "Request Content-Type \"application/json\" is supported by the specification"
        val matchFailure = matchResult as Result.Failure
        assertThat(matchFailure.reportString()).contains(expectedMediaTypeError)
        assertThat(matchFailure.toMatchFailureDetails().breadCrumbs)
            .containsExactly("REQUEST", "PARAMETERS", "HEADER", "Content-Type")

        val validationException = assertThrows<ContractException> { feature.validateExamplesOrException() }
        val validationExceptionMessage = validationException.message.orEmpty()
        assertThat(validationExceptionMessage).contains(expectedMediaTypeError)
        assertThat(Regex(Regex.escape(expectedMediaTypeError)).findAll(validationExceptionMessage).count()).isEqualTo(1)

        val validationMessage = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateExample(specFile, exampleFile)
            .errorMessage
        assertThat(validationMessage).contains(expectedMediaTypeError)
        assertThat(validationMessage).doesNotContain("No matching specification found for this example")
    }

    @Test
    fun `415 example using supported request content type with invalid body is rejected as supported media type`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "post-json-invalid-body",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": { "content": "is wonderful" }""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "application/json"
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).containsExactly(exampleFile.canonicalPath)
    }

    @Test
    fun `415 example with unsupported request content type but invalid path parameter is rejected`() {
        val specFile = writeSpec(orderByIdSpec())
        val exampleFile = writeExample(
            name = "post-invalid-id",
            requestMethod = "POST",
            requestPath = "/orders/not-a-number",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain"
        )

        val (_, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).containsExactly(exampleFile.canonicalPath)
    }

    @Test
    fun `415 example with unsupported request content type but missing required query parameter is rejected`() {
        val specFile = writeSpec(orderSearchSpec())
        val exampleFile = writeExample(
            name = "post-missing-query",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain"
        )

        val (_, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).containsExactly(exampleFile.canonicalPath)
    }

    @Test
    fun `415 relaxation does not apply to statuses other than 415`() {
        val specFile = writeSpec(notAcceptableSpec())
        val exampleFile = writeExample(
            name = "post-text-not-acceptable",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 406,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain"
        )

        val (_, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).containsExactly(exampleFile.canonicalPath)
    }

    @Test
    fun `OpenAPI 415 response creates one scenario when operation supports multiple request content types`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(ordersSpecWithMultipleRequestContentTypes()).canonicalPath).toFeature()

        assertThat(feature.scenarios.filter { it.method == "PUT" && it.path == "/orders" && it.status == 415 }).hasSize(1)
    }

    @Test
    fun `415 without examples is not generated as a contract test in any resiliency mode`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only415Spec()).canonicalPath).toFeature()

        resiliencyModes().forEach { resiliencyMode ->
            val featureWithResiliency = feature.copy(specmaticConfig = specmaticConfigWith(resiliencyMode))
            val generatedScenarios = featureWithResiliency.generateContractTestScenarios(emptyList()).toList()

            assertThat(generatedScenarios).isEmpty()
        }
    }

    @Test
    fun `415 without examples is skipped with request rejection example required reason`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only415Spec()).canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }

        resiliencyModes().forEach { resiliencyMode ->
            val decision = unsupportedMediaTypeScenario.newBasedOnWithDecision(
                strictMode = false,
                resiliencyTestSuite = resiliencyMode
            )

            assertThat(decision).isInstanceOf(Decision.Skip::class.java)
            assertThat(decision!!.reasoning.mainReason).isEqualTo(TestSkipReason.REQUEST_REJECTION_EXAMPLE_REQUIRED)
        }
    }

    @Test
    fun `415 without examples is not used by mock even with a response code hint`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only415Spec()).canonicalPath).toFeature()

        HttpStub(feature).use { stub ->
            val responseWithoutHint = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/orders",
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = StringValue("request sent here")
                )
            )
            val responseWithHint = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/orders",
                    headers = mapOf("Content-Type" to "text/plain", SPECMATIC_RESPONSE_CODE_HEADER to "415"),
                    body = StringValue("request sent here")
                )
            )

            assertThat(responseWithoutHint.status).isNotEqualTo(415)
            assertThat(responseWithHint.status).isNotEqualTo(415)
        }
    }

    @Test
    fun `operation identifier records unsupported request content type for external 415 example`() {
        val exampleFile = writeExample(
            name = "post-text-plain",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain"
        )

        val operationIdentifier = OpenApiSpecification.OperationIdentifier(ExampleFromFile(exampleFile, strictMode = false))

        assertThat(operationIdentifier.requestContentType).isEqualTo("text/plain")
        assertThat(operationIdentifier.responseStatus).isEqualTo(415)
    }

    private fun loadFeatureWithExamples(specFile: File, vararg exampleFiles: File) =
        OpenApiSpecification.fromFile(specFile.canonicalPath)
            .toFeature()
            .loadExternalisedExamplesAndListUnloadableExamples(
                DirectoryExampleSource(
                    exampleDirs = exampleFiles.map { it.parentFile.canonicalPath }.distinct(),
                    strictMode = false,
                    specmaticConfig = SpecmaticConfig()
                )
            )

    private fun writeSpec(spec: String): File =
        tempDir.resolve("api-${System.nanoTime()}.yaml").also { it.writeText(spec.trimIndent()) }

    private fun writeExample(
        name: String,
        requestMethod: String,
        requestPath: String,
        requestBody: String,
        responseStatus: Int,
        responseBody: String,
        requestContentType: String? = "application/json",
        responseContentType: String = "application/json",
        extraRequestHeaders: Map<String, String> = emptyMap()
    ): File {
        val examplesDir = tempDir.resolve("examples-$name").also(File::mkdirs)
        return examplesDir.resolve("$name.json").also { file ->
            file.writeText(
                """
                {
                  "http-request": {
                    "method": "$requestMethod",
                    "path": "$requestPath",
                    ${requestHeaders(requestContentType, extraRequestHeaders)}
                    $requestBody
                  },
                  "http-response": {
                    "status": $responseStatus,
                    "headers": {
                      "Content-Type": "$responseContentType"
                    },
                    $responseBody
                  }
                }
                """.trimIndent()
            )
        }
    }

    private fun requestHeaders(requestContentType: String?, extraRequestHeaders: Map<String, String>): String {
        val requestHeaderEntries = listOfNotNull(
            requestContentType?.let { """"Content-Type": "$it"""" }
        ).plus(extraRequestHeaders.map { (name, value) -> """"$name": "$value"""" })

        return if (requestHeaderEntries.isEmpty()) {
            """"headers": {},"""
        } else {
            """"headers": {
                      ${requestHeaderEntries.joinToString(",\n")}
                    },"""
        }
    }

    private fun resiliencyModes(): List<ResiliencyTestSuite> =
        listOf(ResiliencyTestSuite.none, ResiliencyTestSuite.positiveOnly, ResiliencyTestSuite.all)

    private fun specmaticConfigWith(resiliencyTestSuite: ResiliencyTestSuite): SpecmaticConfig =
        when (resiliencyTestSuite) {
            ResiliencyTestSuite.none -> SpecmaticConfig()
            ResiliencyTestSuite.positiveOnly -> SpecmaticConfig().enableResiliencyTests(onlyPositive = true)
            ResiliencyTestSuite.all -> SpecmaticConfig().enableResiliencyTests(onlyPositive = false)
        }

    private fun HttpRequest.hasHeader(name: String, value: String): Boolean =
        headers.any { (headerName, headerValue) -> headerName.equals(name, ignoreCase = true) && headerValue == value }

    private fun ordersSpec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
              responses:
                "200":
                  description: ok
                "415":
                  description: unsupported media type
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
    """

    private fun ordersSpecWithMultipleRequestContentTypes() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            put:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - content
                      properties:
                        content:
                          type: string
                  text/plain:
                    schema:
                      type: string
              responses:
                "200":
                  description: ok
                "415":
                  description: unsupported media type
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
    """

    private fun ordersSpecWithParameterizedTextPlainRequestContentType() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
                  "text/plain; charset=utf-8":
                    schema:
                      type: string
              responses:
                "415":
                  description: unsupported media type
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
    """

    private fun orderByIdSpec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders/{id}:
            post:
              parameters:
                - in: path
                  name: id
                  required: true
                  schema:
                    type: integer
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
              responses:
                "415":
                  description: unsupported media type
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
    """

    private fun orderSearchSpec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              parameters:
                - in: query
                  name: customerId
                  required: true
                  schema:
                    type: string
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
              responses:
                "415":
                  description: unsupported media type
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
    """

    private fun inline415Spec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
                    examples:
                      unsupported-media-type:
                        value:
                          data: found
              responses:
                "415":
                  description: unsupported media type
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
                      examples:
                        unsupported-media-type:
                          value:
                            error: occurred
    """

    private fun notAcceptableSpec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
              responses:
                "406":
                  description: not acceptable
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
    """

    private fun only415Spec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - data
                      properties:
                        data:
                          type: string
              responses:
                "415":
                  description: unsupported media type
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
    """

    private fun String.normalizedContentType(): String =
        substringBefore(";").trim().lowercase()
}
