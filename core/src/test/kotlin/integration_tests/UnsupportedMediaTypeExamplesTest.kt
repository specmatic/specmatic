package integration_tests

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiLintViolations
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.conversions.missingRequestExampleErrorMessageForTest
import io.specmatic.core.DefaultMismatchMessages
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
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.test.TestExecutor
import io.specmatic.test.TestSkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

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
        })

        assertThat(sawUnsupportedMediaTypeRequest).isTrue()
        assertThat(results.successCount).isEqualTo(2)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `generated negative test accepts declared external 415 response`() {
        val specFile = writeSpec(ordersSpecWith400And415())
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
        val featureWithNegativeGeneration = feature.copy(specmaticConfig = specmaticConfigWith(ResiliencyTestSuite.all))

        assertThat(unusedExamples).isEmpty()

        var sawGeneratedNegativeRequest = false
        val results = featureWithNegativeGeneration.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return when (request.expectedResponseCode()) {
                    200 -> HttpResponse.ok("")
                    400 -> {
                        sawGeneratedNegativeRequest = true
                        unsupportedMediaTypeResponse()
                    }
                    415 -> unsupportedMediaTypeResponse()
                    else -> throw ContractException("Unexpected response code hint ${request.expectedResponseCode()}")
                }
            }
        })

        assertThat(sawGeneratedNegativeRequest).isTrue()
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `generated 415 request uses a content type not supported by the operation`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(ordersSpecWithParameterizedTextPlainRequestContentType()).canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }

        val generatedRequest = unsupportedMediaTypeScenario.generateHttpRequest()
        val generatedRequestV2 = unsupportedMediaTypeScenario.generateHttpRequestV2().single().value

        assertThat(unsupportedMediaTypeScenario.unsupportedContentTypeFor415Example())
            .isEqualTo(generatedRequest.contentType()?.normalizedContentType())
        assertThat(unsupportedMediaTypeScenario.disallowedMethodFor405Example()).isNull()
        assertThat(generatedRequest.contentType()?.normalizedContentType()).isEqualTo("application/xml")
        assertThat(generatedRequestV2.contentType()?.normalizedContentType()).isEqualTo("application/xml")
    }

    @Test
    fun `415 fix request preserves unsupported request content type when body can be generated for it`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(ordersSpecWithSupportedRequestContentTypes(listOf("application/json"))).canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }
        val requestWithUnsupportedContentType = HttpRequest(
            method = "POST",
            path = "/orders",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = parsedJSONObject("""{"data":"found"}""")
        )

        val fixedRequest = unsupportedMediaTypeScenario.requestWithValidUnsupportedContentTypeFor415Example(requestWithUnsupportedContentType)

        assertThat(fixedRequest?.contentType()).isEqualTo("application/octet-stream")
        assertThat(fixedRequest?.body).isEqualTo(StringValue("unsupported request body"))
    }

    @Test
    fun `415 fix request falls back to generated unsupported content type when current content type cannot be generated`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(ordersSpecWithSupportedRequestContentTypes(listOf("application/json", "text/plain"))).canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }
        val requestWithUnknownContentType = HttpRequest(
            method = "POST",
            path = "/orders",
            headers = mapOf("Content-Type" to "application/vnd.unknown"),
            body = parsedJSONObject("""{"data":"found"}""")
        )

        val fixedRequest = unsupportedMediaTypeScenario.requestWithValidUnsupportedContentTypeFor415Example(requestWithUnknownContentType)

        assertThat(fixedRequest?.contentType()).isEqualTo("application/xml")
        assertThat(fixedRequest?.body).isInstanceOf(XMLNode::class.java)
    }

    @Test
    fun `415 fix request falls back to generated unsupported content type when current content type is supported`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(ordersSpecWithSupportedRequestContentTypes(listOf("application/json"))).canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }
        val requestWithSupportedContentType = HttpRequest(
            method = "POST",
            path = "/orders",
            headers = mapOf("Content-Type" to "application/json"),
            body = StringValue("wrong")
        )

        val fixedRequest = unsupportedMediaTypeScenario.requestWithValidUnsupportedContentTypeFor415Example(requestWithSupportedContentType)

        assertThat(fixedRequest?.contentType()).isEqualTo("text/plain")
        assertThat(fixedRequest?.body).isEqualTo(StringValue("unsupported request body"))
    }

    @ParameterizedTest(name = "generated 415 example with {0} is served by mock")
    @MethodSource("generated415MockCases")
    fun `generated 415 request body matches unsupported content type and is served by mock`(testCase: Generated415MockCase) {
        val specFile = writeSpec(ordersSpecWithSupportedRequestContentTypes(testCase.supportedRequestContentTypes))
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }

        val generatedRequest = unsupportedMediaTypeScenario.generateHttpRequest()
        val generatedRequestV2 = unsupportedMediaTypeScenario.generateHttpRequestV2().single().value

        assertThat(generatedRequest.contentType()).isEqualTo(testCase.expectedUnsupportedContentType)
        assertThat(generatedRequestV2.contentType()).isEqualTo(testCase.expectedUnsupportedContentType)
        assertGenerated415RequestBodyMatchesContentType(generatedRequest)
        assertGenerated415RequestBodyMatchesContentType(generatedRequestV2)

        val exampleFile = writeExampleFromRequest(
            name = "post-${testCase.expectedUnsupportedContentType.replace("/", "-")}",
            request = generatedRequest
        )

        HttpStub(feature, listOf(ScenarioStub.readFromFile(exampleFile))).use { stub ->
            val response = stub.client.execute(generatedRequest)

            assertThat(response.status).isEqualTo(415)
            assertThat(response.body).isEqualTo(parsedJSONObject("""{"error":"occurred"}"""))
        }
    }

    @Test
    fun `custom 415 fallback content type works across mock and contract test execution`() {
        val specFile = writeSpec(ordersSpecWithAllPreferredUnsupportedRequestContentTypes())
        val exampleFile = writeExample(
            name = "post-specmatic-unsupported",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": "request sent here"""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = "application/x-specmatic-unsupported"
        )
        val (feature, unusedExamples) = loadFeatureWithExamples(specFile, exampleFile)
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }

        assertThat(unusedExamples).isEmpty()
        assertThat(unsupportedMediaTypeScenario.unsupportedContentTypeFor415Example())
            .isEqualTo("application/x-specmatic-unsupported")

        HttpStub(feature, listOf(ScenarioStub.readFromFile(exampleFile))).use { stub ->
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse =
                    stub.client.execute(request)
            })

            assertThat(results.failureCount).isEqualTo(0)
            assertThat(results.successCount).isEqualTo(1)
        }
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
        assertThat(warningReport).doesNotContain(OpenApiLintViolations.UNDECLARED_REQUEST_VARIANT_RESPONSE_REQUIRES_EXTERNAL_EXAMPLE.id)
        assertThat(warningReport).doesNotContain("paths./orders.post.responses.415")
        assertThat(warningReport).doesNotContain(missingRequestExampleErrorMessageForTest("unsupported-media-type"))

        val feature = specification.toFeature()

        assertThat(feature.inlineNamedStubs).isEmpty()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                throw ContractException("Inline 415 examples should not be generated as tests")
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
        assertThat(warningReport).doesNotContain(OpenApiLintViolations.UNDECLARED_REQUEST_VARIANT_RESPONSE_REQUIRES_EXTERNAL_EXAMPLE.id)
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

    @ParameterizedTest(name = "415 example with {0} and mismatched body is rejected by validation")
    @MethodSource("invalid415PayloadCases")
    fun `415 example with unsupported request content type but mismatched body is rejected by validation`(
        testCase: Invalid415PayloadCase
    ) {
        val specFile = writeSpec(ordersSpecWithSupportedRequestContentTypes(listOf("application/json")))
        val exampleFile = writeExample(
            name = "post-${testCase.requestContentType.replace("/", "-")}-invalid-body",
            requestMethod = "POST",
            requestPath = "/orders",
            requestBody = """"body": { "data": "found" }""",
            responseStatus = 415,
            responseBody = """"body": { "error": "occurred" }""",
            requestContentType = testCase.requestContentType
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
        val expectedBodyError =
            "Request body for Content-Type \"${testCase.requestContentType}\" must be ${testCase.expectedBodyDescription}"
        val matchFailure = matchResult as Result.Failure
        assertThat(matchFailure.reportString()).contains(expectedBodyError)
        assertThat(matchFailure.toMatchFailureDetails().breadCrumbs)
            .containsExactly("REQUEST", "BODY")

        val validationMessage = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateExample(specFile, exampleFile)
            .errorMessage
        assertThat(validationMessage).contains(expectedBodyError)
        assertThat(validationMessage).doesNotContain("No matching specification found for this example")
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
            val generatedScenarios = featureWithResiliency.generateContractTestScenarios().toList()

            assertThat(generatedScenarios).isEmpty()
        }
    }

    @Test
    fun `415 without examples is skipped with undeclared request variant example required reason`() {
        val feature = OpenApiSpecification.fromFile(writeSpec(only415Spec()).canonicalPath).toFeature()
        val unsupportedMediaTypeScenario = feature.scenarios.single { it.status == 415 }

        resiliencyModes().forEach { resiliencyMode ->
            val decision = unsupportedMediaTypeScenario.newBasedOnWithDecision(
                strictMode = false,
                resiliencyTestSuite = resiliencyMode
            )

            assertThat(decision).isInstanceOf(Decision.Skip::class.java)
            assertThat(decision!!.reasoning.mainReason).isEqualTo(TestSkipReason.UNDECLARED_REQUEST_VARIANT_EXAMPLE_REQUIRED)
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
            assertThat(responseWithHint.status).isEqualTo(400)
            assertThat(responseWithHint.headers).containsEntry(SPECMATIC_RESULT_HEADER, "failure")
            assertThat(responseWithHint.body.toStringLiteral())
                .contains("Cannot respond with 415 because no external 415 example was found.")
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

    private fun writeExampleFromRequest(name: String, request: HttpRequest): File {
        val examplesDir = tempDir.resolve("examples-$name").also(File::mkdirs)
        return examplesDir.resolve("$name.json").also { file ->
            file.writeText(
                ScenarioStub(
                    request = request,
                    response = HttpResponse(
                        status = 415,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = parsedJSONObject("""{"error":"occurred"}""")
                    )
                ).withName(name).toJSON().toStringLiteral()
            )
        }
    }

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

    private fun unsupportedMediaTypeResponse(): HttpResponse =
        HttpResponse(
            status = 415,
            headers = mapOf("Content-Type" to "application/json"),
            body = parsedJSONObject("""{"error":"occurred"}""")
        )

    private fun HttpRequest.hasHeader(name: String, value: String): Boolean =
        headers.any { (headerName, headerValue) -> headerName.equals(name, ignoreCase = true) && headerValue == value }

    private fun assertGenerated415RequestBodyMatchesContentType(request: HttpRequest) {
        when (request.contentType()) {
            "application/xml" -> {
                assertThat(request.body).isInstanceOf(XMLNode::class.java)
                assertThat(request.formFields).isEmpty()
            }
            "application/x-www-form-urlencoded" -> {
                assertThat(request.body).isEqualTo(EmptyString)
                assertThat(request.formFields).containsEntry("specmatic", "unsupported")
            }
            else -> {
                assertThat(request.body).isInstanceOf(StringValue::class.java)
                assertThat(request.formFields).isEmpty()
            }
        }
    }

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

    private fun ordersSpecWith400And415() = """
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
                "400":
                  description: bad request
                  content:
                    application/json:
                      schema:
                        type: object
                        required:
                          - error
                        properties:
                          error:
                            type: string
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

    private fun ordersSpecWithSupportedRequestContentTypes(contentTypes: List<String>) = """
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
${requestContentTypes(contentTypes)}
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

    private fun requestContentTypes(contentTypes: List<String>): String =
        contentTypes.joinToString("\n") { requestContentType(it) }

    private fun requestContentType(contentType: String): String =
        requestContentTypeSchema(contentType).prependIndent("                  ")

    private fun requestContentTypeSchema(contentType: String): String {
        return when (contentType) {
            "application/json" -> """
                "$contentType":
                  schema:
                    type: object
                    required:
                      - data
                    properties:
                      data:
                        type: string
            """
            "application/xml" -> """
                "$contentType":
                  schema:
                    type: object
                    xml:
                      name: order
                    properties:
                      data:
                        type: string
            """
            "application/x-www-form-urlencoded" -> """
                "$contentType":
                  schema:
                    type: object
                    properties:
                      data:
                        type: string
            """
            else -> """
                "$contentType":
                  schema:
                    type: string
            """
        }.trimIndent()
    }

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

    private fun ordersSpecWithAllPreferredUnsupportedRequestContentTypes() = """
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
                  text/plain:
                    schema:
                      type: string
                  application/xml:
                    schema:
                      type: object
                      xml:
                        name: order
                      properties:
                        data:
                          type: string
                  application/octet-stream:
                    schema:
                      type: string
                  application/x-www-form-urlencoded:
                    schema:
                      type: object
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

    data class Generated415MockCase(
        val supportedRequestContentTypes: List<String>,
        val expectedUnsupportedContentType: String
    ) {
        override fun toString(): String = expectedUnsupportedContentType
    }

    data class Invalid415PayloadCase(
        val requestContentType: String,
        val expectedBodyDescription: String
    ) {
        override fun toString(): String = requestContentType
    }

    companion object {
        @JvmStatic
        fun generated415MockCases(): Stream<Generated415MockCase> = Stream.of(
            Generated415MockCase(
                supportedRequestContentTypes = listOf("application/json"),
                expectedUnsupportedContentType = "text/plain"
            ),
            Generated415MockCase(
                supportedRequestContentTypes = listOf("application/json", "text/plain"),
                expectedUnsupportedContentType = "application/xml"
            ),
            Generated415MockCase(
                supportedRequestContentTypes = listOf("application/json", "text/plain", "application/xml"),
                expectedUnsupportedContentType = "application/octet-stream"
            ),
            Generated415MockCase(
                supportedRequestContentTypes = listOf(
                    "application/json",
                    "text/plain",
                    "application/xml",
                    "application/octet-stream"
                ),
                expectedUnsupportedContentType = "application/x-www-form-urlencoded"
            ),
            Generated415MockCase(
                supportedRequestContentTypes = listOf(
                    "application/json",
                    "text/plain",
                    "application/xml",
                    "application/octet-stream",
                    "application/x-www-form-urlencoded"
                ),
                expectedUnsupportedContentType = "application/x-specmatic-unsupported"
            )
        )

        @JvmStatic
        fun invalid415PayloadCases(): Stream<Invalid415PayloadCase> = Stream.of(
            Invalid415PayloadCase(
                requestContentType = "text/plain",
                expectedBodyDescription = "a string value"
            ),
            Invalid415PayloadCase(
                requestContentType = "application/xml",
                expectedBodyDescription = "XML"
            ),
            Invalid415PayloadCase(
                requestContentType = "application/octet-stream",
                expectedBodyDescription = "a string value"
            ),
            Invalid415PayloadCase(
                requestContentType = "application/x-www-form-urlencoded",
                expectedBodyDescription = "form fields"
            ),
            Invalid415PayloadCase(
                requestContentType = "application/x-specmatic-unsupported",
                expectedBodyDescription = "a string value"
            )
        )
    }
}
