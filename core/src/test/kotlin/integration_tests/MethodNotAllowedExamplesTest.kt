package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiLintViolations
import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.conversions.missingRequestExampleErrorMessageForTest
import io.specmatic.core.FailureReason
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.Result
import io.specmatic.core.SPECMATIC_RESULT_HEADER
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.examples.module.ExampleValidationModule
import io.specmatic.core.examples.source.DirectoryExampleSource
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Decision
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.FeatureStubsResult
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.stub.loadContractStubsAsResults
import io.specmatic.test.TestExecutor
import io.specmatic.test.TestSkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File

class MethodNotAllowedExamplesTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `external 405 example with undeclared method loads and runs as a contract test`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "patch-with-post-body",
            requestMethod = "PATCH",
            requestPath = "/orders",
            requestBody = """"body": { "data": "found" }""",
            responseStatus = 405,
            responseBody = """"body": { "error": "occurred" }"""
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()
        val methodNotAllowedScenario = feature.scenarios.single { it.method == "POST" && it.status == 405 }
        assertThat(methodNotAllowedScenario.hasExamples()).isTrue()
        assertThat(methodNotAllowedScenario.method).isEqualTo("POST")
        val generatedRequest = methodNotAllowedScenario
            .newBasedOn(methodNotAllowedScenario.examples.single().rows.single(), feature.flagsBased)
            .first()
            .value
            .generateHttpRequest()
        assertThat(generatedRequest.method).isEqualTo("PATCH")
        assertThat(feature.scenarios.single { it.method == "PUT" && it.status == 405 }.hasExamples()).isFalse()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if (request.method != "PATCH") return HttpResponse.ok("")

                assertThat(request.path).isEqualTo("/orders")
                assertThat(request.body).isEqualTo(parsedJSONObject("""{"data":"found"}"""))
                return HttpResponse(
                    status = 405,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = parsedJSONObject("""{"error":"occurred"}""")
                )
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.successCount).isEqualTo(3)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `generated 405 request uses a method not declared for the path`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only405Spec()).canonicalPath).toFeature()
        val methodNotAllowedScenario = feature.scenarios.single { it.status == 405 }

        val generatedRequest = methodNotAllowedScenario.generateHttpRequest()
        val generatedRequestV2 = methodNotAllowedScenario.generateHttpRequestV2().single().value
        val declaredMethods = methodNotAllowedScenario.httpRequestPattern.undeclaredRequestVariantMetadata.methodsForPath.map { it.uppercase() }

        assertThat(methodNotAllowedScenario.disallowedMethodFor405Example()).isEqualTo(generatedRequest.method)
        assertThat(methodNotAllowedScenario.unsupportedContentTypeFor415Example()).isNull()
        assertThat(generatedRequest.method).isNotBlank()
        assertThat(generatedRequest.method?.uppercase()).isNotIn(declaredMethods)
        assertThat(generatedRequestV2.method).isNotBlank()
        assertThat(generatedRequestV2.method?.uppercase()).isNotIn(declaredMethods)
    }

    @Test
    fun `generated 405 request fails clearly when every preferred HTTP method is declared`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(allMethods405Spec()).canonicalPath).toFeature()
        val methodNotAllowedScenario = feature.scenarios.first { it.status == 405 }

        assertThat(methodNotAllowedScenario.disallowedMethodFor405Example()).isNull()

        val exception = assertThrows<ContractException> {
            methodNotAllowedScenario.generateHttpRequest()
        }
        assertThat(exception.message).contains("all known HTTP methods are already declared for this path")
        assertThat(exception.failure().hasReason(FailureReason.MethodNotAllowedNoDisallowedMethod)).isTrue()

        val v2Exception = assertThrows<ContractException> {
            methodNotAllowedScenario.generateHttpRequestV2()
        }
        assertThat(v2Exception.message).contains("all known HTTP methods are already declared for this path")
        assertThat(v2Exception.failure().hasReason(FailureReason.MethodNotAllowedNoDisallowedMethod)).isTrue()
    }

    @Test
    fun `405 response warns at parse time when every known HTTP method is declared for the path`() {
        val specification = OpenApiSpecification.fromFile(writeSpec(allMethods405Spec()).canonicalPath)
        val collectorContext = CollectorContext()
        specification.toScenarioInfos(collectorContext)

        val warningReport = collectorContext.toCollector().toResult().reportString()
        assertThat(warningReport).contains(OpenApiLintViolations.METHOD_NOT_ALLOWED_RESPONSE_HAS_NO_DISALLOWED_METHOD.id)
        assertThat(warningReport).contains("paths./orders.get.responses.405")
        assertThat(warningReport).contains("all known HTTP methods are already declared for /orders")
        assertThat(warningReport).doesNotContain(OpenApiLintViolations.UNDECLARED_REQUEST_VARIANT_RESPONSE_REQUIRES_EXTERNAL_EXAMPLE.id)
    }

    @Test
    fun `external 405 example with undeclared method is served by mock`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "patch-with-post-body",
            requestMethod = "PATCH",
            requestPath = "/orders",
            requestBody = """"body": { "data": "found" }""",
            responseStatus = 405,
            responseBody = """"body": { "error": "occurred" }"""
        )
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        HttpStub(feature, listOf(ScenarioStub.readFromFile(exampleFile))).use { stub ->
            val response = stub.client.execute(
                HttpRequest(
                    method = "PATCH",
                    path = "/orders",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = parsedJSONObject("""{"data":"found"}""")
                )
            )

            assertThat(response.status).isEqualTo(405)
            assertThat(response.body).isEqualTo(parsedJSONObject("""{"error":"occurred"}"""))
        }
    }

    @Test
    fun `inline 405 examples are ignored because OpenAPI cannot declare a disallowed request method`() {
        val specification = OpenApiSpecification.fromFile(writeSpec(inline405Spec()).canonicalPath)
        val collectorContext = CollectorContext()
        specification.toScenarioInfos(collectorContext)

        val warningReport = collectorContext.toCollector().toResult().reportString()
        assertThat(warningReport).doesNotContain(OpenApiLintViolations.UNDECLARED_REQUEST_VARIANT_RESPONSE_REQUIRES_EXTERNAL_EXAMPLE.id)
        assertThat(warningReport).doesNotContain("paths./orders.post.responses.405")
        assertThat(warningReport).doesNotContain(missingRequestExampleErrorMessageForTest("method-not-allowed"))

        val feature = specification.toFeature()

        assertThat(feature.inlineNamedStubs).isEmpty()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                throw ContractException("Inline 405 examples should not be generated as tests")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.successCount).isEqualTo(0)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `405 response schemas without inline examples do not produce ignored response warnings`() {
        val specification = OpenApiSpecification.fromFile(writeSpec(ordersSpec()).canonicalPath)
        val collectorContext = CollectorContext()
        specification.toScenarioInfos(collectorContext)

        val warningReport = collectorContext.toCollector().toResult().reportString()
        assertThat(warningReport).doesNotContain(OpenApiLintViolations.UNDECLARED_REQUEST_VARIANT_RESPONSE_REQUIRES_EXTERNAL_EXAMPLE.id)
    }

    @Test
    fun `one external 405 example can attach to multiple compatible operations on a path`() {
        val specFile = writeSpec(orderByIdSpec())
        val exampleFile = writeExample(
            name = "patch-order-by-id",
            requestMethod = "PATCH",
            requestPath = "/orders/10",
            requestBody = null,
            responseStatus = 405,
            responseBody = """"body": "I use a different method"""",
            requestContentType = null,
            responseContentType = "text/plain"
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()
        assertThat(feature.scenarios.filter { it.status == 405 && it.hasExamples() }.map { it.method })
            .containsExactlyInAnyOrder("GET", "DELETE")

        val seenMethods = mutableListOf<String?>()
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if (request.method == "DELETE") return HttpResponse(204)
                if (request.method != "PATCH") return HttpResponse.ok("")

                seenMethods.add(request.method)
                assertThat(request.path).isEqualTo("/orders/10")
                return HttpResponse(
                    status = 405,
                    body = "I use a different method",
                    headers = mapOf("Content-Type" to "text/plain")
                )
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.successCount).isEqualTo(4)
        assertThat(seenMethods).containsExactly("PATCH", "PATCH")
    }

    @Test
    fun `405 example using a declared method is rejected`() {
        val specFile = writeSpec(orderByIdSpec())
        val exampleFile = writeExample(
            name = "get-order-by-id",
            requestMethod = "GET",
            requestPath = "/orders/10",
            requestBody = null,
            responseStatus = 405,
            responseBody = """"body": "I use a different method"""",
            requestContentType = null,
            responseContentType = "text/plain"
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()
        val scenario = feature.scenarios.single { it.method == "GET" && it.status == 405 }
        assertThat(scenario.hasExamples()).isTrue()
        assertThat(feature.scenarios.filter { it.status == 405 && it.hasExamples() }.map { it.method })
            .containsExactly("GET")

        val matchResult = scenario.matches(
            HttpRequest(method = "GET", path = "/orders/10"),
            HttpResponse(
                status = 405,
                body = "I use a different method",
                headers = mapOf("Content-Type" to "text/plain")
            ),
            mismatchMessages = io.specmatic.core.DefaultMismatchMessages,
            flagsBased = feature.flagsBased
        )

        assertThat(matchResult).isInstanceOf(Result.Failure::class.java)
        val expectedMethodError = "Expected method not to be one of DELETE, GET"
        val matchFailure = matchResult as Result.Failure
        assertThat(matchFailure.reportString()).contains(expectedMethodError)
        assertThat(matchFailure.toMatchFailureDetails().breadCrumbs).containsExactly("REQUEST", "METHOD")

        val validationException = assertThrows<ContractException> { feature.validateExamplesOrException() }
        val validationExceptionMessage = validationException.message.orEmpty()
        assertThat(validationExceptionMessage).contains(expectedMethodError)
        assertThat(Regex(Regex.escape(expectedMethodError)).findAll(validationExceptionMessage).count()).isEqualTo(1)

        val validationMessage = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateExample(specFile, exampleFile)
            .errorMessage
        assertThat(validationMessage).contains(expectedMethodError)
        assertThat(validationMessage).doesNotContain("No matching specification found for this example")

        val stubLoadFailure = loadContractStubsAsResults(
            features = listOf(specFile.canonicalPath to feature),
            stubData = listOf(exampleFile.canonicalPath to ScenarioStub.readFromFile(exampleFile)),
            logIgnoredFiles = true
        ).filterIsInstance<FeatureStubsResult.Failure>().single()
        assertThat(stubLoadFailure.errorMessage).contains(expectedMethodError)
        assertThat(stubLoadFailure.errorMessage).doesNotContain("No matching REST stub or contract found")
    }

    @Test
    fun `405 example using declared method without required request content type is left unloadable`() {
        val specFile = writeSpec(ordersSpec())
        val exampleFile = writeExample(
            name = "post-without-content-type",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": { "data": "found" }""",
            responseStatus = 405,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = null
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).containsExactly(exampleFile.canonicalPath)
        assertThat(feature.scenarios.filter { it.status == 405 && it.hasExamples() }).isEmpty()
    }

    @Test
    fun `405 example using declared method with another operation request content type is left unloadable`() {
        val specFile = writeSpec(ordersSpecWithTextPlainPut())
        val exampleFile = writeExample(
            name = "post-with-text-plain-put-body",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "stuff goes here"""",
            responseStatus = 405,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "text/plain"
        )

        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).containsExactly(exampleFile.canonicalPath)
        assertThat(feature.scenarios.filter { it.status == 405 && it.hasExamples() }).isEmpty()
    }

    @Test
    fun `405 examples using declared methods are rejected even when the payload matches another operation`() {
        val cases = listOf(
            RejectedExample(
                name = "put-with-put-body",
                spec = ordersSpec(),
                requestMethod = "PUT",
                requestBody = """"body": { "content": "is great" }""",
                requestContentType = "application/json"
            ),
            RejectedExample(
                name = "post-with-put-body",
                spec = ordersSpec(),
                requestMethod = "POST",
                requestBody = """"body": { "content": "is great" }""",
                requestContentType = "application/json"
            )
        )

        cases.forEach { rejectedExample ->
            val specFile = writeSpec(rejectedExample.spec)
            val exampleFile = writeExample(
                name = rejectedExample.name,
                requestMethod = rejectedExample.requestMethod,
                requestPath = "/orders",
                requestBody = rejectedExample.requestBody,
                responseStatus = 405,
                responseBody = """"body": { "error": "occurred" }""",
                requestContentType = rejectedExample.requestContentType
            )

            val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)
            val exampleStub = ScenarioStub.readFromFile(exampleFile)

            assertThat(unusedExamples).isEmpty()
            assertThat(feature.scenarios.filter { it.status == 405 && it.hasExamples() }.map { it.method })
                .containsExactly(rejectedExample.requestMethod)

            val expectedMethodError = "Expected method not to be one of POST, PUT"
            val validationException = assertThrows<ContractException> { feature.validateExamplesOrException() }
            val validationExceptionMessage = validationException.message.orEmpty()
            assertThat(validationExceptionMessage).contains(expectedMethodError)
            assertThat(Regex(Regex.escape(expectedMethodError)).findAll(validationExceptionMessage).count()).isEqualTo(1)

            val matchResult = feature.scenarios
                .filter { it.status == 405 }
                .map {
                    it.matches(
                        exampleStub.request,
                        exampleStub.response,
                        mismatchMessages = io.specmatic.core.DefaultMismatchMessages,
                        flagsBased = feature.flagsBased
                    )
                }

            assertThat(matchResult).allSatisfy {
                assertThat(it).isInstanceOf(Result.Failure::class.java)
                assertThat((it as Result.Failure).reportString()).contains(expectedMethodError)
            }
        }
    }

    @Test
    fun `405 example with no request method is rejected`() {
        val specFile = writeSpec(orderByIdSpec())
        val exampleFile = writeExample(
            name = "missing-method",
            requestMethod = null,
            requestPath = "/orders/10",
            requestBody = null,
            responseStatus = 405,
            responseBody = """"body": "I use a different method"""",
            requestContentType = null,
            responseContentType = "text/plain"
        )

        val lenientStub = ScenarioStub.readFromFile(exampleFile, strictMode = false)
        assertThat(lenientStub.request.method).isBlank()
        assertThat(OpenApiSpecification.OperationIdentifier(ExampleFromFile(exampleFile, strictMode = false)).requestMethod).isBlank()
        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).isEmpty()
        assertThat(feature.scenarios.flatMap { scenario -> scenario.examples.flatMap { it.rows } }.mapNotNull { it.fileSource })
            .doesNotContain(exampleFile.canonicalPath)
        val matchResult = feature.scenarios.single { it.method == "GET" && it.status == 405 }.matches(
            lenientStub.request,
            lenientStub.response,
            mismatchMessages = io.specmatic.core.DefaultMismatchMessages,
            flagsBased = feature.flagsBased
        )

        assertThat(matchResult).isInstanceOf(Result.Failure::class.java)
        assertThat((matchResult as Result.Failure).reportString()).contains("Expected method not to be one of DELETE, GET")
    }

    @Test
    fun `method relaxation does not apply to statuses other than 405`() {
        val specFile = writeSpec(notAcceptableSpec())
        val exampleFile = writeExample(
            name = "patch-not-acceptable",
            requestMethod = "PATCH",
            requestPath = "/orders",
            requestBody = """"body": { "data": "found" }""",
            responseStatus = 406,
            responseBody = """"body": { "error": "occurred" }"""
        )

        val (_, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)

        assertThat(unusedExamples).containsExactly(exampleFile.canonicalPath)
    }

    @Test
    fun `405 without examples is not generated as a contract test in any resiliency mode`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only405Spec()).canonicalPath).toFeature()

        resiliencyModes().forEach { resiliencyMode ->
            val featureWithResiliency = feature.copy(specmaticConfig = specmaticConfigWith(resiliencyMode))
            val generatedScenarios = featureWithResiliency.generateContractTestScenarios(emptyList()).toList()

            assertThat(generatedScenarios).isEmpty()
        }
    }

    @Test
    fun `405 without examples is skipped with undeclared request variant example required reason`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only405Spec()).canonicalPath).toFeature()
        val methodNotAllowedScenario = feature.scenarios.single { it.status == 405 }

        resiliencyModes().forEach { resiliencyMode ->
            val decision = methodNotAllowedScenario.newBasedOnWithDecision(
                strictMode = false,
                resiliencyTestSuite = resiliencyMode
            )

            assertThat(decision).isInstanceOf(Decision.Skip::class.java)
            assertThat(decision!!.reasoning.mainReason).isEqualTo(TestSkipReason.UNDECLARED_REQUEST_VARIANT_EXAMPLE_REQUIRED)
        }
    }

    @Test
    fun `405 without examples is not used by mock even with a response code hint`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only405Spec()).canonicalPath).toFeature()

        HttpStub(feature).use { stub ->
            val responseWithoutHint = stub.client.execute(HttpRequest(method = "PATCH", path = "/orders/10"))
            val responseWithHint = stub.client.execute(
                HttpRequest(
                    method = "PATCH",
                    path = "/orders/10",
                    headers = mapOf(SPECMATIC_RESPONSE_CODE_HEADER to "405")
                )
            )

            assertThat(responseWithoutHint.status).isNotEqualTo(405)
            assertThat(responseWithHint.status).isEqualTo(400)
            assertThat(responseWithHint.headers).containsEntry(SPECMATIC_RESULT_HEADER, "failure")
            assertThat(responseWithHint.body.toStringLiteral())
                .contains("Cannot respond with 405 because no external 405 example was found.")
        }
    }

    private data class RejectedExample(
        val name: String,
        val spec: String,
        val requestMethod: String,
        val requestBody: String,
        val requestContentType: String
    )

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
        requestMethod: String?,
        requestPath: String,
        requestBody: String?,
        responseStatus: Int,
        responseBody: String,
        requestContentType: String? = "application/json",
        responseContentType: String = "application/json",
        extraRequestHeaders: Map<String, String> = emptyMap()
    ): File {
        val examplesDir = tempDir.resolve("examples-$name").also(File::mkdirs)
        return examplesDir.resolve("$name.json").also { file ->
            val requestHeaderEntries = listOfNotNull(
                requestContentType?.let { """"Content-Type": "$it"""" }
            ).plus(extraRequestHeaders.map { (name, value) -> """"$name": "$value"""" })

            val requestHeaders = requestHeaderEntries.takeIf { it.isNotEmpty() }?.let {
                """
                    "headers": {
                      ${it.joinToString(",\n")}
                    }
                """.trimIndent()
            }
            file.writeText(
                """
                {
                  "http-request": {
                    ${requestMethod?.let { """"method": "$it",""" }.orEmpty()}
                    "path": "$requestPath"${requestHeaders?.let { ",$it" }.orEmpty()}${requestBody?.let { ",$it" }.orEmpty()}
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
                "405":
                  description: method not allowed
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
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
              responses:
                "200":
                  description: ok
                "405":
                  description: method not allowed
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

    private fun ordersSpecWithTextPlainPut() = """
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
                "405":
                  description: method not allowed
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
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
                "405":
                  description: method not allowed
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

    private fun inline405Spec() = """
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
                "405":
                  description: method not allowed
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
                        method-not-allowed:
                          value:
                            error: occurred
    """

    private fun orderByIdSpec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders/{id}:
            get:
              parameters:
                - in: path
                  name: id
                  required: true
                  schema:
                    type: integer
              responses:
                "200":
                  description: ok
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            delete:
              parameters:
                - in: path
                  name: id
                  required: true
                  schema:
                    type: integer
              responses:
                "204":
                  description: ok
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
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

    private fun only405Spec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders/{id}:
            get:
              parameters:
                - in: path
                  name: id
                  required: true
                  schema:
                    type: integer
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
    """

    private fun allMethods405Spec() = """
        openapi: 3.0.4
        info:
          title: Orders
          version: 1.0.0
        paths:
          /orders:
            get:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            post:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            put:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            delete:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            patch:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            head:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            options:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
            trace:
              responses:
                "405":
                  description: method not allowed
                  content:
                    text/plain:
                      schema:
                        type: string
    """
}
