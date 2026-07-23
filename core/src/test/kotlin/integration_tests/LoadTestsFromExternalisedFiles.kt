package integration_tests

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.examples.source.CombinedSource
import io.specmatic.core.examples.source.DirectoryExampleSource
import io.specmatic.core.examples.source.PreLoadedExampleObjects
import io.specmatic.core.examples.module.ExampleValidationModule
import io.specmatic.core.log.*
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_SCHEMA
import io.specmatic.core.utilities.Flags.Companion.IGNORE_INLINE_EXAMPLES
import io.specmatic.core.value.*
import io.specmatic.mock.ScenarioStub
import io.specmatic.test.TestExecutor
import io.specmatic.test.FixtureExecutionDetails
import io.specmatic.test.fixtures.FixtureExecutionMetadata
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import io.specmatic.test.interceptor.ContractTestInterceptor
import io.specmatic.test.interceptor.InterceptResult
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.net.URLClassLoader
import java.nio.file.Files

private val doubleMax = BigDecimal(Double.MAX_VALUE)
private val doubleMin = BigDecimal(-Double.MAX_VALUE)
private val XML_ONEOF_RESOURCE_DIR = File("src/test/resources/openapi/xml_oneof_document_parcel")
private val XML_ONEOF_CONTRACT = XML_ONEOF_RESOURCE_DIR.resolve("api.yaml")
private val XML_ONEOF_CONTRACT_WITH_INLINE_EXAMPLES = XML_ONEOF_RESOURCE_DIR.resolve("api_with_inline_examples.yaml")
private val XML_ONEOF_EXTERNAL_EXAMPLES_DIR = XML_ONEOF_RESOURCE_DIR.resolve("api_examples")
private val XML_ONEOF_INVALID_INVOICE_EXAMPLE = XML_ONEOF_RESOURCE_DIR.resolve("invalid_examples/invoice.json")

class LoadTestsFromExternalisedFiles {
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        unmockkAll()
    }

    @Test
    fun `should load and execute externalized tests for header and request body from _examples directory`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/has_externalized_test_and_no_example.yaml")
            .toFeature().loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/order_action_figure")
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.headers).containsEntry("X-Request-ID", "12345")
                assertThat(request.body).isEqualTo(parsedJSONObject("""{"name": "Master Yoda", "description": "Head of the Jedi Council"}"""))

                return HttpResponse.ok(parsedJSONObject("""{"id": 1}"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())
        assertThat(results.successCount).isEqualTo(1)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `should load and execute externalized tests for header and request body from _tests directory`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/has_externalized_test.yaml")
            .toFeature().loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/order_action_figure")
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.headers).containsEntry("X-Request-ID", "12345")
                assertThat(request.body).isEqualTo(parsedJSONObject("""{"name": "Master Yoda", "description": "Head of the Jedi Council"}"""))

                return HttpResponse.ok(parsedJSONObject("""{"id": 1}"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())
        assertThat(results.successCount).isEqualTo(1)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `should load and execute externalized tests for header and request body from _examples sub-directory`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/has_externalized_tests_in_subdirectories.yaml")
            .toFeature().loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/order_action_figure")
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.headers).containsEntry("X-Request-ID", "12345")
                assertThat(request.body).isEqualTo(parsedJSONObject("""{"name": "Master Yoda", "description": "Head of the Jedi Council"}"""))

                return HttpResponse.ok(parsedJSONObject("""{"id": 1}"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())
        assertThat(results.successCount).isEqualTo(2)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `should load examples from spec _examples and explicit example directories`() {
        val specDir = tempDir.resolve("contract").apply { mkdirs() }
        val specFile = specDir.resolve("order.yaml")
        specFile.writeText("""
        openapi: 3.0.0
        info:
          title: Order API
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
                      properties:
                        source:
                          type: string
                      required:
                        - source
              responses:
                '200':
                  description: ok
        """.trimIndent())

        val implicitExamplesDir = specDir.resolve("order_examples").apply { mkdirs() }
        implicitExamplesDir.resolve("implicit.json").writeText("""
        {
          "http-request": {
            "method": "POST",
            "path": "/orders",
            "body": { "source": "implicit" }
          },
          "http-response": { "status": 200 }
        }
        """.trimIndent())

        val explicitExamplesDir = tempDir.resolve("explicit_examples").apply { mkdirs() }
        explicitExamplesDir.resolve("explicit.json").writeText("""
        {
          "http-request": {
            "method": "POST",
            "path": "/orders",
            "body": { "source": "explicit" }
          },
          "http-response": { "status": 200 }
        }
        """.trimIndent())

        val feature = parseContractFileToFeature(specFile, exampleDirPaths = listOf(implicitExamplesDir.canonicalPath))
        val (loadedFeature, unusedExamples) = feature.loadExternalisedExamplesAndListUnloadableExamples(
            exampleSource = CombinedSource(
                sources = listOf(
                    DirectoryExampleSource(
                        strictMode = true,
                        exampleDirs = listOf(implicitExamplesDir.canonicalPath),
                        specmaticConfig = feature.specmaticConfig
                    ),
                    PreLoadedExampleObjects(
                        specmaticConfig = feature.specmaticConfig,
                        examples = listOf(ScenarioStub.readFromFile(explicitExamplesDir.resolve("explicit.json"))),
                    )
                )
            )
        )

        assertThat(loadedFeature.scenarios).hasSize(1)
        assertThat(loadedFeature.scenarios.single().examples.single().rows).hasSize(2)
        assertThat(unusedExamples).isEmpty()
    }

    @Nested
    inner class RowValueLookupGenerationTest {
        @Test
        fun `should resolve lookup expressions in row values for positive and negative generated tests`() {
            val specFile = File("src/test/resources/openapi/row_value_lookup/api.yaml")
            val feature = loadRowValueLookupFeature(specFile)

            val observedRequests = mutableListOf<String>()
            val positiveRequests = mutableListOf<HttpRequest>()
            val negativeMutationEvidences = mutableSetOf<String>()
            val expectedPositiveRequestBody = parsedJSONObject("""{"name": "Sherlock"}""")
            val positiveHeaderPattern = Regex("A+")

            val results = executeRowValueLookupGenerativeTests(feature) { request ->
                println(request.toLogString())
                observedRequests.add(request.toLogString())
                assertRequestHasNoUnresolvedLookupExpressions(request)

                val randomHeader = request.headers["Random-String"]
                val hasValidRandomHeader = randomHeader == null || positiveHeaderPattern.matches(randomHeader)
                val isPositiveRequest =
                    request.path == "/orders/order-123" &&
                        request.queryParams.asMap()["page"] == "page-7" &&
                        request.headers["X-Tenant"] == "north" &&
                        request.body == expectedPositiveRequestBody &&
                        hasValidRandomHeader

                if (isPositiveRequest) {
                    positiveRequests.add(request)
                    HttpResponse.ok(parsedJSONObject("""{"result": "accepted"}"""))
                } else {
                    when {
                        request.path != "/orders/order-123" -> negativeMutationEvidences.add("path")
                        request.queryParams.asMap()["page"] != "page-7" -> negativeMutationEvidences.add("query")
                        request.headers["X-Tenant"] != "north" -> negativeMutationEvidences.add("header")
                        request.body != expectedPositiveRequestBody -> negativeMutationEvidences.add("body")
                    }

                    HttpResponse(
                        status = 400,
                        body = parsedJSONObject("""{"message": "bad request"}"""),
                        headers = mapOf("Content-Type" to "application/json")
                    )
                }
            }

            assertThat(results.success())
                .withFailMessage("Observed requests:\n%s\n\n%s", observedRequests.joinToString("\n\n"), results.report())
                .isTrue()

            assertThat(negativeMutationEvidences).contains("query", "header", "body")
            assertThat(positiveRequests)
                .withFailMessage("Observed requests:\n%s\n\n%s", observedRequests.joinToString("\n\n"), results.report())
                .isNotEmpty()
        }

        @Test
        fun `should resolve lookup expressions when the top level body is substituted with a number`() {
            val specFile = File("src/test/resources/openapi/row_value_lookup/top_level_number_body_api.yaml")
            val feature = loadRowValueLookupFeature(specFile)

            val observedRequests = mutableListOf<String>()
            val positiveRequests = mutableListOf<HttpRequest>()
            val negativeBodies = mutableListOf<Value>()

            val results = executeRowValueLookupGenerativeTests(feature) { request ->
                println(request.toLogString())
                observedRequests.add(request.toLogString())
                assertRequestHasNoUnresolvedLookupExpressions(request)

                if (request.path == "/counts" && request.body.toUnformattedString().toIntOrNull() in setOf(42, 43)) {
                    positiveRequests.add(request)
                    HttpResponse.ok(parsedJSONObject("""{"result": "accepted"}"""))
                } else {
                    negativeBodies.add(request.body)
                    HttpResponse(
                        status = 400,
                        body = parsedJSONObject("""{"message": "bad request"}"""),
                        headers = mapOf("Content-Type" to "application/json")
                    )
                }
            }

            assertThat(results.success())
                .withFailMessage("Observed requests:\n%s\n\n%s", observedRequests.joinToString("\n\n"), results.report())
                .isTrue()

            assertThat(negativeBodies).isNotEmpty
            assertThat(positiveRequests)
                .withFailMessage("Observed requests:\n%s\n\n%s", observedRequests.joinToString("\n\n"), results.report())
                .isNotEmpty()
        }

        private fun loadRowValueLookupFeature(specFile: File): Feature {
            val examplesDir = specFile.resolveSibling(specFile.nameWithoutExtension + "_examples")
            return Flags.using(EXAMPLE_DIRECTORIES to examplesDir.canonicalPath) {
                OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature().copy(strictMode = true).loadExternalisedExamples().also {
                    it.validateExamplesOrException()
                }
            }
        }

        private fun executeRowValueLookupGenerativeTests(feature: Feature, handler: (HttpRequest) -> HttpResponse): Results {
            return withServiceLoaderEntries(
                mapOf(
                    OpenAPIFixtureExecutor::class.java to RowValueLookupFixtureExecutor::class.java.name,
                    ContractTestInterceptor::class.java to RowValueLookupContractTestInterceptor::class.java.name
                )
            ) {
                feature.enableGenerativeTesting().executeTests(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse = handler(request)
                })
            }
        }

        private fun assertRequestHasNoUnresolvedLookupExpressions(request: HttpRequest) {
            assertThat(request.path).doesNotContain("$(")
            assertThat(request.queryParams.toString()).doesNotContain("$(")
            assertThat(request.headers.values.joinToString(" ")).doesNotContain("$(")
            assertThat(request.body.toStringLiteral()).doesNotContain("$(")
        }
    }

    @Test
    fun `externalized tests be converted to rows`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/has_two_externalised_tests.yaml").toFeature().loadExternalisedExamples()
        assertThat(feature.scenarios.first().examples.first().rows.size).isEqualTo(2)
    }

    @Test
    fun `externalized tests should be validated`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/has_invalid_externalized_test.yaml").toFeature().loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/order_action_figure")
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.body).isEqualTo(parsedJSONObject("""{"name": "Master Yoda", "description": "Head of the Jedi Council"}"""))

                return HttpResponse.ok(parsedJSONObject("""{"id": 1}"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())

        assertThat(results.report())
            .contains(">> REQUEST.BODY.description")
            .contains("10")

        assertThat(results.success()).isFalse()
    }

    @Test
    fun `externalized tests with query parameters`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/has_externalised_test_with_query_params.yaml")
            .toFeature().loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.queryParams.containsEntry("description", "Jedi")).isTrue

                return HttpResponse.ok(parsedJSONArray("""[{"name": "Master Yoda", "description": "Head of the Jedi Council"}]"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())

        assertThat(results.successCount).isEqualTo(1)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `unUtilized externalized tests should be logged and an exception thrown`() {
        val defaultLogger = logger
        val logBuffer = object : CompositePrinter(emptyList()) {
            var buffer: MutableList<String> = mutableListOf()

            override fun print(msg: LogMessage, indentation: String) {
                buffer.add(msg.toLogString())
            }
        }
        val testLogger = NonVerbose(logBuffer)

        try {
            logger = testLogger

            val (_, unusedExamplesFilePaths) =
                OpenApiSpecification
                    .fromFile("src/test/resources/openapi/has_irrelevant_externalized_test.yaml")
                    .toFeature()
                    .loadExternalisedExamplesAndListUnloadableExamples()

            assertThat(unusedExamplesFilePaths).hasSize(2)
            assertThat(unusedExamplesFilePaths.any {
                it.endsWith("irrelevant_test.json")
            }).isTrue()
        } finally {
            logger = defaultLogger
        }

        println(logBuffer.buffer)

        val messageTitle = "The following externalized examples were not used:"

        assertThat(logBuffer.buffer).contains(messageTitle)
        val titleIndex = logBuffer.buffer.lastIndexOf(messageTitle)
        val elementContainingIrrelevantFile = logBuffer.buffer.findLast { it.contains("irrelevant_test.json") }
        assertThat(logBuffer.buffer.lastIndexOf(elementContainingIrrelevantFile)).isGreaterThan(titleIndex)
    }

    @Test
    fun `should load tests from local test directory`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Add Person API
              version: 1.0.0

            # Path for adding a person
            paths:
              /person:
                post:
                  summary: Add a new person
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Person'
                  responses:
                    '201':
                      description: Person created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                Person:
                  type: object
                  properties:
                    name:
                      type: string
                      description: Name of the person
        """.trimIndent()

        System.setProperty(EXAMPLE_DIRECTORIES, "src/test/resources/local_tests")
        val feature = OpenApiSpecification
            .fromYAML(
                spec,
                ""
            )
            .toFeature()
            .loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val requestBody = request.body as JSONObjectValue
                assertThat(requestBody.findFirstChildByPath("name")?.toStringLiteral()).isEqualTo("Jack")
                return HttpResponse(201, "success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        try {
            assertThat(results.successCount).isEqualTo(1)
            assertThat(results.success()).withFailMessage(results.report()).isTrue()
        } finally {
            System.clearProperty(EXAMPLE_DIRECTORIES)
        }
    }

    @Test
    fun `external and internal examples are both run as tests`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/has_inline_and_external_examples.yaml")
            .toFeature()
            .loadExternalisedExamples()

        val idsSeen = mutableListOf<String>()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val path = request.path ?: fail("Path expected")
                idsSeen.add(path.split("/").last())

                return HttpResponse(200, parsedJSONObject("""{"id": 10, "name": "Jack"}""")).also {
                    println("---")
                    println(request.toLogString())
                    println(it.toLogString())
                    println()
                }
            }
        })

        assertThat(idsSeen).contains("123", "456")
        assertThat(results.testCount).isEqualTo(3)
    }

    @Test
    fun `external example should override the inline example with the same name and should restrict it from running as a test`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/has_overriding_external_examples.yaml")
            .toFeature()
            .loadExternalisedExamples()

        val idsSeen = mutableListOf<String>()

        val result = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val path = request.path ?: fail("Path expected")
                idsSeen.add(path.split("/").last())

                return HttpResponse(200, parsedJSONObject("""{"id": 10, "name": "Jack"}""")).also {
                    println("---")
                    println(request.toLogString())
                    println(it.toLogString())
                    println()
                }
            }
        })

        assertThat(idsSeen).contains("overriding_external_id")
        assertThat(idsSeen).doesNotContain("overridden_inline_id")

        assertThat(idsSeen).hasSize(2)
        assertThat(result.testCount).isEqualTo(2)
    }

    @Test
    fun `tests from external examples validate response schema as per the given example by default`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/has_inline_and_external_examples.yaml")
            .toFeature()
            .loadExternalisedExamples()

        val idsSeen = mutableListOf<String>()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val path = request.path ?: fail("Path expected")
                idsSeen.add(path.split("/").last())

                return HttpResponse(200, parsedJSONObject("""{"id": 10, "name": "Justin"}""")).also {
                    println("---")
                    println(request.toLogString())
                    println(it.toLogString())
                    println()
                }
            }
        })

        assertThat(idsSeen).contains("123", "456")
        assertThat(results.testCount).isEqualTo(3)
    }

    @Test
    fun `should load anyvalue pattern based examples`() {
        val feature = OpenApiSpecification.fromFile(
            "src/test/resources/openapi/spec_with_path_param.yaml"
        ).toFeature().loadExternalisedExamples()

        val results = feature.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.queryParams.asMap().getValue("item") == "10") {
                    return HttpResponse(status = 200, body = JSONObjectValue(
                        mapOf("id" to NumberValue(10))
                    ))
                }

                return HttpResponse(status = 200, body = JSONObjectValue(
                    mapOf("id" to NumberValue(10000))
                ))
            }
        })

        assertThat(results.successCount).isEqualTo(2)
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `should complain when interpolated path params in externalized example are invalid`(@TempDir tempDir: File) {
        val spec = """
        openapi: 3.0.3
        info:
          title: Interpolated Invalid Example API
          version: 1.0.0
        paths:
          /example/{id1},{id2}/status:
            get:
              parameters:
                - name: id1
                  in: path
                  required: true
                  schema:
                    type: number
                - name: id2
                  in: path
                  required: true
                  schema:
                    type: number
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [message]
                        properties:
                          message:
                            type: string
        """.trimIndent()
        val exampleFile = tempDir.resolve("invalid_interpolated_comma_path.json").apply {
            writeText("""
            {
              "http-request": {
                "method": "GET",
                "path": "/example/alpha,beta/status"
              },
              "http-response": {
                "status": 200,
                "body": {
                  "message": "ok"
                }
              }
            }""".trimIndent())
        }

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exception = assertThrows<ContractException> {
            Flags.using(EXAMPLE_DIRECTORIES to tempDir.canonicalPath) {
                feature.loadExternalisedExamples().validateExamplesOrException()
            }
        }

        assertThat(exception.report()).contains(
            "Error loading example for GET /example/(id1:number),(id2:number)/status -> 200 from ${exampleFile.canonicalPath}",
            "In scenario \"GET /example/(id1:number),(id2:number)/status. Response: ok\"",
            "API: GET /example/(id1:number),(id2:number)/status -> 200",
            ">> REQUEST.PARAMETERS.PATH.id1",
            "Specification expected type number but example \"invalid_interpolated_comma_path\" contained value \"alpha\" of type string",
            ">> REQUEST.PARAMETERS.PATH.id2",
            "Specification expected type number but example \"invalid_interpolated_comma_path\" contained value \"beta\" of type string",
            "R1001: Type mismatch"
        )
    }

    @Test
    fun `should complain when example request-response contains out-of-spec headers`() {
        val openApiFile = File("src/test/resources/openapi/apiKeyAuth.yaml")
        val examplesDir = File("src/test/resources/openapi/apiKeyAuthExtraHeader_examples")

        Flags.using(Flags.EXAMPLE_DIRECTORIES to examplesDir.canonicalPath) {
            val feature = OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature().loadExternalisedExamples()
            val exception = assertThrows<ContractException> { feature.validateExamplesOrException() }

            assertThat(exception.report()).contains(
                "Error loading example for GET /hello/(id:number) -> 200 from ${examplesDir.resolve("extra_header.json").canonicalPath}",
                "In scenario \"hello world. Response: Says hello\"",
                "API: GET /hello/(id:number) -> 200",
                ">> REQUEST.PARAMETERS.HEADER.X-Extra-Header",
                "Header \"X-Extra-Header\" in the example \"extra_header\" was not in the specification",
                ">> RESPONSE.HEADER.X-Extra-Header",
                "R2003: Unknown property"
            )
        }
    }

    @Test
    fun `should be able to load and use multi-value dictionary when making requests`() {
        val openApiFile = File("src/test/resources/openapi/spec_with_multi_value_dict/api.yaml")
        val feature = OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature()

        val evidences = mutableListOf<String>()

        val assertNumberValue: (Collection<String>) -> Unit = { values ->
            assertThat(values).allSatisfy {
                assertThat(BigDecimal(it)).isIn(BigDecimal(123), BigDecimal(456), doubleMin, doubleMax)
            }
        }

        val results = feature.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = (request.body as JSONObjectValue).jsonObject
                assertNumberValue(request.path!!.split("/").filter { it.isNotBlank() && it !in setOf("creators", "pets") })
                evidences.add("path from dictionary")
                assertNumberValue(request.queryParams.asMap().values)
                evidences.add("query from dictionary")
                assertNumberValue(request.headers.filterKeys { it in setOf("CREATOR-ID", "PET-ID") }.values)
                evidences.add("headers from dictionary")
                assertNumberValue(body["creatorId"]!!.let(Value::toStringLiteral).let(::listOf))
                assertThat(body["name"]?.toStringLiteral()).isIn("Tom", "Jerry")
                evidences.add("body from dictionary")

                return HttpResponse(
                    status = 201,
                    body =
                        JSONObjectValue(
                            body + mapOf("id" to NumberValue(123), "petId" to NumberValue(456)),
                        ),
                )
            }
        })

        assertThat(evidences.distinct()).containsExactlyInAnyOrder(
            "path from dictionary",
            "query from dictionary",
            "headers from dictionary",
            "body from dictionary",
        )

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `should be able to load and use examples when there are shadow-ed paths`() {
        val openApiFile = File("src/test/resources/openapi/has_shadow_paths/api.yaml")
        val validExamplesDir = openApiFile.resolveSibling("valid_examples")
        val feature = Flags.using(EXAMPLE_DIRECTORIES to validExamplesDir.canonicalPath) {
            OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature().loadExternalisedExamples()
        }
        assertDoesNotThrow { feature.validateExamplesOrException() }

        val results = feature.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue
                val value = body.jsonObject.getValue("value") as ScalarValue
                when(request.path) {
                    "/test/latest", "/123/reports/456" -> assertThat(value.nativeValue).isEqualTo(true)
                    else -> assertThat(value.nativeValue).isEqualTo(123)
                }

                println(request.toLogString())
                return HttpResponse(status = 200, body = body)
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `should provide accurate example load failures when shadowed-paths have invalid examples`() {
        val openApiFile = File("src/test/resources/openapi/has_shadow_paths/api.yaml")
        val invalidExamplesDir = openApiFile.resolveSibling("invalid_examples")
        val feature = Flags.using(EXAMPLE_DIRECTORIES to invalidExamplesDir.canonicalPath) {
            OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature().loadExternalisedExamples()
        }
        val exception = assertThrows<ContractException> { feature.validateExamplesOrException() }

        assertThat(exception.report()).contains(
            "Error loading example for POST /test/(testId:string) -> 200 from ${invalidExamplesDir.resolve("testId_example.json").canonicalPath}",
            "In scenario \"POST /test/(testId:string). Response: OK\"",
            "API: POST /test/(testId:string) -> 200",
            "Specification expected type number but example \"testId_example\" contained value true of type boolean",
            "Error loading example for POST /test/latest -> 200 from ${invalidExamplesDir.resolve("latest_example.json").canonicalPath}",
            "In scenario \"POST /test/latest. Response: OK\"",
            "API: POST /test/latest -> 200",
            "Specification expected type boolean but example \"latest_example\" contained value 123 of type number",
            "Error loading example for POST /reports/(testId:string)/latest -> 200 from ${invalidExamplesDir.resolve("reports_testId_latest.json").canonicalPath}",
            "In scenario \"POST /reports/(testId:string)/latest. Response: OK\"",
            "API: POST /reports/(testId:string)/latest -> 200",
            "Specification expected type number but example \"reports_testId_latest\" contained value true of type boolean",
            "Error loading example for POST /(testId:string)/reports/(reportId:string) -> 200 from ${invalidExamplesDir.resolve("testId_reports_reportId.json").canonicalPath}",
            "In scenario \"POST /(testId:string)/reports/(reportId:string). Response: OK\"",
            "API: POST /(testId:string)/reports/(reportId:string) -> 200",
            "Specification expected type boolean but example \"testId_reports_reportId\" contained value 123 of type number",
            ">> REQUEST.BODY.value",
            ">> RESPONSE.BODY.value",
            "R1001: Type mismatch"
        )
    }

    @Test
    fun `should be able to load and run tests with serialized json-value in of the fields`() {
        val apiFile = File("src/test/resources/openapi/has_serialized_json_field/api.yaml")
        val feature = OpenApiSpecification.fromFile(apiFile.canonicalPath).toFeature().loadExternalisedExamples()
        assertDoesNotThrow { feature.validateExamplesOrException() }

        val results = feature.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue
                val value = body.jsonObject.getValue("json") as ScalarValue
                assertThat(value.nativeValue).isEqualTo("{\"value\": [\"string\", 1, false, null], \"location\": \"request\"}")

                println(request.toLogString())
                return HttpResponse(
                    status = 200,
                    body = body.addEntry("json", "{\"value\": [\"string\", 1, false, null], \"location\": \"response\"}")
                )
            }
        })

        assertThat(results.results).hasSize(2)
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `should complain when examples have invalid security-scheme values or unexpected keys in header and query`() {
        val openApiFile = File("src/test/resources/openapi/has_composite_security/api.yaml")
        val examplesDir = openApiFile.resolveSibling("invalid_examples")
        val feature = Flags.using(EXAMPLE_DIRECTORIES to examplesDir.canonicalPath, IGNORE_INLINE_EXAMPLES to "true") {
            OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature().loadExternalisedExamples()
        }
        val exception = assertThrows<ContractException> { feature.validateExamplesOrException() }

        assertThat(exception.report()).contains(
            "Error loading example for POST /secure -> 200 from ${examplesDir.resolve("secure.json").canonicalPath}",
            "In scenario \"Secure endpoint requiring Bearer token and query API key. Response: Success\"",
            "API: POST /secure -> 200",
            "Error loading example for POST /partial -> 200 from ${examplesDir.resolve("partial.json").canonicalPath}",
            "In scenario \"Partially secure endpoint requiring either Bearer token or query API key. Response: Success\"",
            "API: POST /partial -> 200",
            "Error loading example for POST /overlap -> 200 from ${examplesDir.resolve("overlap.json").canonicalPath}",
            "In scenario \"overlap endpoint requiring either Bearer token and query API key or Bearer token only. Response: Success\"",
            "API: POST /overlap -> 200",
            "Authorization header must be prefixed with \"Bearer\"",
            "Error loading example for POST /insecure -> 200 from ${examplesDir.resolve("insecure.json").canonicalPath}",
            "In scenario \"Insecure endpoint not requiring authentication. Response: Success\"",
            "API: POST /insecure -> 200",
            ">> REQUEST.PARAMETERS.QUERY.apiKey",
            "Query param \"apiKey\" in the example \"insecure\" was not in the specification",
            ">> REQUEST.PARAMETERS.HEADER.Authorization",
            "Header \"Authorization\" in the example \"insecure\" was not in the specification"
        )
    }

    @Test
    fun `should use values provided in the example for security-schemes and fill-in missing values`() {
        val openApiFile = File("src/test/resources/openapi/has_composite_security/api.yaml")
        val examplesDir = openApiFile.resolveSibling("valid_examples")
        val feature = Flags.using(
            EXAMPLE_DIRECTORIES to examplesDir.canonicalPath,
            IGNORE_INLINE_EXAMPLES to "true",
            "bearerAuth" to "API-SECRET",
            "apiKeyQuery" to "1234"
        ) {
            OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature().loadExternalisedExamples()
        }

        val assertApiKey: (HttpRequest, String?) -> Unit = { request, expected ->
            assertThat(request.queryParams.asMap()["apiKey"]).isEqualTo(expected)
        }
        val assertAuthHeader: (HttpRequest, String?) -> Unit = { request, expected ->
            assertThat(request.headers["Authorization"]).isEqualTo(expected)
        }

        val results = feature.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val requestBody = request.body as JSONObjectValue
                val bodyString = requestBody.jsonObject["message"]!!.toStringLiteral()
                when (request.path) {
                    "/secure" -> {
                        assertApiKey(request, "1234")
                        assertAuthHeader(request, "Bearer API-SECRET")
                        assertThat(bodyString).isEqualTo("Hello to Secure")
                    }
                    "/overlap" -> {
                        assertThat(request).satisfiesAnyOf(
                            {
                                assertApiKey(it, "1234")
                                assertAuthHeader(it, "Bearer API-SECRET")
                            },
                            {
                                assertApiKey(it, null)
                                assertAuthHeader(it, "Bearer API-SECRET")
                            }
                        )
                        assertThat(bodyString).isEqualTo("Hello to Overlap")
                    }
                    "/partial" -> {
                        assertThat(request).satisfiesAnyOf(
                            {
                                assertApiKey(request, "1234")
                                assertAuthHeader(request, null)
                            },
                            {
                                assertApiKey(request, null)
                                assertAuthHeader(request, "Bearer API-SECRET")
                            }
                        )
                        assertThat(bodyString).isEqualTo("Hello to Partial")
                    }
                    else -> {
                        assertApiKey(request, null)
                        assertAuthHeader(request, null)
                        assertThat(bodyString).isEqualTo("Hello to Insecure")
                    }
                }

                return HttpResponse.ok(requestBody.addEntry("message", bodyString)).also {
                    println(request.toLogString())
                    println(it.toLogString())
                }
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.testCount).isEqualTo(6)
    }

    @Test
    fun `should be able to load and run tests using xml based openAPI Specification with external example`() {
        val openApiFile = File("src/test/resources/openapi/has_xml_payloads/api.yaml")
        val feature = parseContractFileToFeature(openApiFile).loadExternalisedExamples()
        assertDoesNotThrow { feature.validateExamplesOrException() }

        val createScenario = feature.scenarios.first()
        val results = feature.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val requestBody = request.body as XMLNode
                val productId = requestBody.findChildrenByName("productId")
                val inventory = requestBody.findChildrenByName("inventory")

                assertThat(productId).hasSize(1).containsOnly(toXMLNode("<productId>50</productId>"))
                assertThat(inventory).hasSize(1).containsOnly(toXMLNode("<inventory>100</inventory>"))

                return createScenario.generateHttpResponse(actualFacts = emptyMap())
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.testCount).isEqualTo(1)
    }

    @Test
    fun `should load inline xml oneOf examples as tests`() {
        val feature = OpenApiSpecification
            .fromFile(XML_ONEOF_CONTRACT_WITH_INLINE_EXAMPLES.path)
            .toFeature()

        assertThat(feature.inlineNamedStubs.map { it.name }).containsExactlyInAnyOrder("inline-document", "inline-parcel")
        assertThat(feature.inlineNamedStubs.map { it.stub.requestRootName() }).containsExactlyInAnyOrder("document", "parcel")
        assertDocumentAndParcelXmlOneOfExamplesRun(feature)
    }

    @Test
    fun `should load external xml oneOf examples as tests`() {
        val feature = OpenApiSpecification
            .fromFile(XML_ONEOF_CONTRACT.path)
            .toFeature()
            .loadExternalisedExamples()

        assertDocumentAndParcelXmlOneOfExamplesRun(feature)
    }

    @Test
    fun `should validate external xml oneOf examples`() {
        val feature = OpenApiSpecification
            .fromFile(XML_ONEOF_CONTRACT.path)
            .toFeature()

        val validationResults = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateExamples(feature, xmlOneOfExternalExampleFiles())

        assertThat(validationResults.success).isTrue()
        assertThat(validationResults.exampleValidationResults.values).allSatisfy { result ->
            assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
        }
    }

    @Test
    fun `should expose inline and external xml oneOf examples through core stub loading`() {
        val feature = OpenApiSpecification
            .fromFile(XML_ONEOF_CONTRACT_WITH_INLINE_EXAMPLES.path)
            .toFeature()

        val filteredExamples = feature.filterExamples(
            examples = xmlOneOfExternalExampleFiles().map(ScenarioStub::readFromFile),
            filter = "PATH='/items'"
        )

        assertThat(filteredExamples.unusedExamples).isEmpty()
        assertThat(filteredExamples.feature.inlineNamedStubs.map { it.name }).containsExactlyInAnyOrder("inline-document", "inline-parcel")
        assertThat(filteredExamples.feature.inlineNamedStubs.map { it.stub.requestRootName() }).containsExactlyInAnyOrder("document", "parcel")
        assertThat(filteredExamples.externalExamples.map { it.nameOrFileName }).containsExactlyInAnyOrder("external-document", "external-parcel")
        assertThat(filteredExamples.externalExamples.map { it.requestRootName() }).containsExactlyInAnyOrder("document", "parcel")
    }

    @Test
    fun `should reject external xml oneOf example that matches no branch during validation`() {
        val feature = OpenApiSpecification
            .fromFile(XML_ONEOF_CONTRACT.path)
            .toFeature()

        val result = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateExample(feature, XML_ONEOF_INVALID_INVOICE_EXAMPLE)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("invoice")
    }

    private fun assertDocumentAndParcelXmlOneOfExamplesRun(feature: Feature) {
        val rootElementNamesSeen = mutableListOf<String>()

        val results = feature.executeTests(object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val requestBody = request.body as XMLNode
                rootElementNamesSeen.add(requestBody.name)

                return HttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "application/xml"),
                    body = requestBody
                )
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.testCount).isEqualTo(2)
        assertThat(rootElementNamesSeen).containsExactlyInAnyOrder("document", "parcel")
    }

    private fun ScenarioStub.requestRootName(): String {
        return (requestElsePartialRequest().body as XMLNode).name
    }

    private fun xmlOneOfExternalExampleFiles(): List<File> {
        return XML_ONEOF_EXTERNAL_EXAMPLES_DIR.listFiles().orEmpty().sortedBy { it.name }
    }

    @Nested
    inner class AttributeSelection {
        @BeforeEach
        fun setup() {
            System.setProperty(ATTRIBUTE_SELECTION_QUERY_PARAM_KEY, "columns")
            System.setProperty(ATTRIBUTE_SELECTION_DEFAULT_FIELDS, "id")
        }

        @AfterEach
        fun tearDown() {
            System.clearProperty(ATTRIBUTE_SELECTION_QUERY_PARAM_KEY)
            System.clearProperty(ATTRIBUTE_SELECTION_DEFAULT_FIELDS)
        }

        @Test
        fun `should load an example with missing mandatory fields and object response`() {
            val feature = OpenApiSpecification.fromFile(
                "src/test/resources/openapi/attribute_selection_tests/api.yaml"
            ).toFeature().loadExternalisedExamples()

            val results = feature.executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    if (!request.path!!.contains("/employeesObjectResponse")) return HttpResponse.ok("")

                    assertThat(request.queryParams.containsEntry("columns", "name"))
                        .withFailMessage("Expected query param 'columns' to be present and with value 'name'")
                        .isTrue()

                    return HttpResponse.ok(parsedJSONObject("""
                    {
                      "id": 1,
                      "name": "name"
                    }
                    """.trimIndent()))
                }
            })

            val result = results.results.first {it.scenario!!.path == "/employeesObjectResponse"}
            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should load an example with missing mandatory fields and array response`() {
            val feature = OpenApiSpecification.fromFile(
                "src/test/resources/openapi/attribute_selection_tests/api.yaml"
            ).toFeature().loadExternalisedExamples()

            val results = feature.executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    if (!request.path!!.contains("/employeesArrayResponse")) return HttpResponse.ok("")

                    assertThat(request.queryParams.containsEntry("columns", "name"))
                        .withFailMessage("Expected query param 'columns' to be present and with value 'name'")
                        .isTrue()

                    return HttpResponse.ok(parsedJSONArray("""
                    [
                      {
                        "id": 1,
                        "name": "name1"
                      },
                      {
                        "id": 2,
                        "name": "name2"
                      }
                    ]
                    """.trimIndent()))
                }
            })

            val result = results.results.first {it.scenario!!.path == "/employeesArrayResponse"}
            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should load an example with missing mandatory fields and allOf response`() {
            val feature = OpenApiSpecification.fromFile(
                "src/test/resources/openapi/attribute_selection_tests/api.yaml"
            ).toFeature().loadExternalisedExamples()

            val results = feature.executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    if (!request.path!!.contains("/employeesAllOfResponse")) return HttpResponse.ok("")

                    assertThat(request.queryParams.containsEntry("columns", "name,department"))
                        .withFailMessage("Expected query param 'columns' to be present and with value 'name, department'")
                        .isTrue()

                    return HttpResponse.ok(parsedJSONObject("""
                    {
                        "id": 1,
                        "name": "name1",
                        "department": "department1"
                    }
                    """.trimIndent()))
                }
            })

            val result = results.results.first {it.scenario!!.path == "/employeesAllOfResponse"}
            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Nested
    inner class FillInTheBlankTests {
        private val feature = OpenApiSpecification.fromFile(
            "src/test/resources/openapi/simple_partial_non_partial_examples_with_dictionary/simple_pets.yaml"
        ).toFeature().loadExternalisedExamples()

        @Test
        fun `should fill the blanks in partial POST request using values from the dictionary`() {
            val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "POST" })

            val results = filteredFeature.executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    println(request.toLogString())

                    assertThat(request.headers["CREATOR-ID"]).isEqualTo("John")
                    assertThat(request.body).isEqualTo(parsedJSONObject("""
                    {
                        "name": "Tom",
                        "color": "black",
                        "tag": "cat"
                    }
                    """.trimIndent()))

                    return HttpResponse(
                        status = 201, body = parsedJSONObject("""
                        {
                            "id": 1,
                            "name": "Tom",
                            "tag": "cat",
                            "color": "black"
                        }
                        """.trimIndent())
                    )
                }
            }).results

            println(results.joinToString("\n\n") { it.reportString() })
            assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(1)
        }

        @Test
        fun `should be able to substitute values into query-params`() {
            val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "GET" })

            val results = filteredFeature.executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    println(request.toLogString())
                    assertThat(request.queryParams.asValueMap()).isEqualTo(mapOf("tag" to StringValue("cat")))

                    return HttpResponse(
                        status = 200, body = JSONArrayValue(List(2) {
                            parsedJSONObject("""
                            {
                                "id": 1,
                                "name": "Tom",
                                "tag": "cat"
                            }
                            """.trimIndent())
                        })
                    )
                }
            }).results

            println(results.joinToString("\n\n") { it.reportString() })
            assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(1)
        }

        @Test
        fun `should only substitute pattern tokens and missing mandatory fields`() {
            val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "PATCH" })

            val results = filteredFeature.executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    println(request.toLogString())

                    assertThat(request.path).isEqualTo("/pets/1")
                    assertThat(request.headers["CREATOR-ID"]).isEqualTo("John")
                    assertThat(request.body).isEqualTo(parsedJSONObject("""
                    {
                        "name": "Tom",
                        "tag": "cat"
                    }
                    """.trimIndent()))

                    return HttpResponse(
                        status = 200, body = parsedJSONObject("""
                        {
                            "id": 1,
                            "name": "Tom",
                            "tag": "cat"
                        }
                        """.trimIndent())
                    )
                }
            }).results

            println(results.joinToString("\n\n") { it.reportString() })
            assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(1)
        }
    }

    @Nested
    inner class PartialExampleTests {
        private val specFile = File("src/test/resources/openapi/partial_example_tests/simple.yaml")
        private val validExamplesDir = specFile.parentFile.resolve("valid_partial")
        private val invalidExamplesDir = specFile.parentFile.resolve("invalid_partial")
        private val validWithoutMandatoryExamplesDir = specFile.parentFile.resolve("valid_without_mandatory")
        private val badRequestExamplesDir = specFile.parentFile.resolve("bad_request_valid_example")

        @Test
        fun `should complain when invalid partial example is provided`() {
            val feature = parseContractFileToFeature(specFile).copy(strictMode = true)
            val exception = assertThrows<ContractException> {
                Flags.using(EXAMPLE_DIRECTORIES to invalidExamplesDir.canonicalPath) {
                    feature.loadExternalisedExamples().validateExamplesOrException()
                }
            }

            println(exception.report())
            assertThat(exception.report()).contains(
                "Error loading example for PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201 from ${invalidExamplesDir.resolve("pets_post.json").canonicalPath}",
                "In scenario \"PATCH /creators/(creatorId:number)/pets/(petId:number). Response: pet response\"",
                "API: PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201",
                ">> REQUEST.PARAMETERS.PATH.creatorId",
                ">> REQUEST.PARAMETERS.PATH.petId",
                ">> REQUEST.PARAMETERS.QUERY.creatorId",
                ">> REQUEST.PARAMETERS.QUERY.petId",
                ">> REQUEST.PARAMETERS.HEADER.CREATOR-ID",
                ">> REQUEST.PARAMETERS.HEADER.PET-ID",
                ">> REQUEST.BODY.creatorId",
                ">> REQUEST.BODY.petId",
                ">> RESPONSE.BODY.id",
                ">> RESPONSE.BODY.traceId",
                ">> RESPONSE.BODY.creatorId",
                "Specification expected type number but example \"pets_post\" contained value \"abc\" of type string",
                "Specification expected number but example \"pets_post\" contained string",
                "Specification expected string but example \"pets_post\" contained number",
                "R1001: Type mismatch"
            )
        }

        @Test
        fun `should load when valid partial example is provided`() {
            val feature = parseContractFileToFeature(specFile).copy(strictMode = true)
            Flags.using(EXAMPLE_DIRECTORIES to validExamplesDir.canonicalPath) {
                assertDoesNotThrow {
                    feature.loadExternalisedExamples().validateExamplesOrException()
                }
            }
        }

        @Test
        fun `should load when valid partial example is provided without mandatory fields`() {
            val feature = parseContractFileToFeature(specFile).copy(strictMode = true)
            Flags.using(EXAMPLE_DIRECTORIES to validWithoutMandatoryExamplesDir.canonicalPath) {
                assertDoesNotThrow {
                    feature.loadExternalisedExamples().validateExamplesOrException()
                }
            }
        }

        @Test
        fun `should be able to run full suite tests using valid examples`() {
            Flags.using(EXAMPLE_DIRECTORIES to validWithoutMandatoryExamplesDir.canonicalPath) {
                val feature = parseContractFileToFeature(specFile).copy(strictMode = true).loadExternalisedExamples()
                feature.validateExamplesOrException()

                val expectedGoodRequest =
                    HttpRequest(
                        path = "/creators/123/pets/999",
                        method = "PATCH",
                        queryParams = QueryParameters(mapOf("creatorId" to "123", "petId" to "999")),
                        headers =
                            mapOf(
                                "Content-Type" to "application/json",
                                "CREATOR-ID" to "123",
                                "PET-ID" to "999",
                                "Specmatic-Response-Code" to "201",
                            ),
                        body = parsedJSONObject("""{"petId": 999, "creatorId": "123"}"""),
                    )

                val evidences = mutableListOf<String>()

                val results =
                    feature
                        .enableGenerativeTesting()
                        .executeTests(
                            object : TestExecutor {
                                override fun execute(request: HttpRequest): HttpResponse {
                                    return if (request.headers["Specmatic-Response-Code"] == "400") {
                                        evidences.add("bad request")
                                        HttpResponse(status = 400, body = parsedJSONObject("""{"code": 400, "message": "BadRequest"}"""))
                                    } else if (request.headers["PET-ID"] == null || request.headers["CREATOR-ID"] == null || request.body !is JSONObjectValue) {
                                        evidences.add("bad request")
                                        HttpResponse(status = 400, body = parsedJSONObject("""{"code": 400, "message": "BadRequest"}"""))
                                    } else {
                                        assertThat(request.method).isEqualTo(expectedGoodRequest.method)
                                        assertThat(request.path).isEqualTo(expectedGoodRequest.path)

                                        assertThat(request.queryParams.asMap()["petId"])
                                            .isIn(
                                                expectedGoodRequest.queryParams.asMap()["petId"],
                                                StringValue(doubleMax.toString()),
                                                StringValue(doubleMin.toString()),
                                            )
                                        assertThat(request.headers["PET-ID"])
                                            .isIn("999", doubleMax.toString(), doubleMin.toString())

                                        val jsonRequestBody = request.body as JSONObjectValue
                                        assertThat(jsonRequestBody.jsonObject["petId"])
                                            .isIn(NumberValue(999), NumberValue(doubleMax), NumberValue(doubleMin))
                                        assertThat(jsonRequestBody.jsonObject["creatorId"])
                                            .isIn(NumberValue(123), NumberValue(doubleMax), NumberValue(doubleMin))

                                        HttpResponse(status = 201, body = parsedJSONObject("""{"id": 999, "petId": 10, "creatorId": 10, "traceId": "123"}"""))
                                    }
                                }
                            },
                        ).results

                assertThat(evidences.distinct()).containsExactlyInAnyOrder(
                    "bad request",
                )
                assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(24)
            }
        }

        @Test
        fun `should be able to make bad request using example`() {
            Flags.using(EXAMPLE_DIRECTORIES to badRequestExamplesDir.canonicalPath) {
                val feature = parseContractFileToFeature(specFile).copy(strictMode = true).loadExternalisedExamples()
                feature.validateExamplesOrException()

                val expectedGoodRequest = HttpRequest(
                    path = "/creators/123/pets/999",
                    method = "PATCH",
                    queryParams = QueryParameters(mapOf("creatorId" to "123", "petId" to "999")),
                    headers = mapOf("Content-Type" to "application/json", "CREATOR-ID" to "123", "PET-ID" to "999", "Specmatic-Response-Code" to "201"),
                    body = JSONObjectValue(mapOf("creatorId" to NumberValue(123), "petId" to NumberValue(999))),
                )

                var badRequestSeen = false

                feature.executeTests(
                    object : TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse {
                            val response: HttpResponse = if (request.headers["Specmatic-Response-Code"] == "400") {
                                val requestBodyJson = request.body as JSONObjectValue
                                if (requestBodyJson.jsonObject["creatorId"] is StringValue) {
                                    badRequestSeen = true
                                }

                                HttpResponse(status = 400, body = parsedJSONObject("""{"code": 400, "message": "BadRequest"}"""))
                            } else {
                                HttpResponse(status = 201, body = parsedJSONObject("""{"id": 999, "traceId": "123"}"""))
                            }

                            println(listOf(request.toLogString(), response.toLogString()).joinToString(separator = "\n\n"))

                            return response
                        }
                    },
                )

                assertThat(badRequestSeen).isTrue()
            }
        }

        @Nested
        inner class DiscriminatorTests {
            private val discriminatorSpecFile = File("src/test/resources/openapi/partial_with_discriminator/openapi.yaml")
            private val exampleWithDiscriminator= discriminatorSpecFile.parentFile.resolve("example_with_disc")
            private val exampleWithoutDisc = discriminatorSpecFile.parentFile.resolve("example_without_disc")
            private val exampleWithPatternToken = discriminatorSpecFile.parentFile.resolve("example_with_pattern_token")
            private val exampleWithInvalidDisc = discriminatorSpecFile.parentFile.resolve("example_with_invalid_disc")
            private val partialWithBodyToken = discriminatorSpecFile.parentFile.resolve("partial_with_body_token")
            private val extendedPartialExample = discriminatorSpecFile.parentFile.resolve("extended_partial")

            @Test
            fun `should be able to load example with only discriminator in request`() {
                Flags.using(EXAMPLE_DIRECTORIES to exampleWithDiscriminator.canonicalPath) {
                    val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()
                    val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "POST" })
                    filteredFeature.validateExamplesOrException()

                    val expectedRequest = HttpRequest(
                        path = "/pets", method = "POST",
                        headers = mapOf("Content-Type" to "application/json", "Specmatic-Response-Code" to "201"),
                        body = parsedJSONObject("""{"petType": "cat", "color": "black"}""")
                    )

                    val results = filteredFeature.executeTests(object: TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse {
                            assertThat(request).isEqualTo(expectedRequest)
                            return HttpResponse(
                                status = 201, body = parsedJSONObject("""{"id": 1, "petType": "cat", "color": "black"}""")
                            ).also { println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n")) }
                        }
                    }).results

                    println(results.joinToString("\n\n") { it.reportString() })
                    assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(1)
                }
            }

            @Test
            fun `should be able to load example without discriminator in request but one of the discriminator fields is present`() {
                Flags.using(EXAMPLE_DIRECTORIES to exampleWithoutDisc.canonicalPath) {
                    val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()
                    val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "POST" })
                    filteredFeature.validateExamplesOrException()

                    val expectedRequest = HttpRequest(
                        path = "/pets", method = "POST",
                        headers = mapOf("Content-Type" to "application/json", "Specmatic-Response-Code" to "201"),
                        body = parsedJSONObject("""{"petType": "cat", "livesLeft": 9, "color": "black"}""")
                    )

                    val results = filteredFeature.executeTests(object: TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse {
                            assertThat(request).isEqualTo(expectedRequest)
                            return HttpResponse(
                                status = 201, body = parsedJSONObject("""{"id": 1, "petType": "cat", "color": "black", "livesLeft": 9}""")
                            ).also { println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n")) }
                        }
                    }).results

                    println(results.joinToString("\n\n") { it.reportString() })
                    assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(1)
                }
            }

            @Test
            fun `should be able to load example with pattern token in request`() {
                Flags.using(EXAMPLE_DIRECTORIES to exampleWithPatternToken.canonicalPath) {
                    val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()
                    val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "POST" })
                    filteredFeature.validateExamplesOrException()

                    val expectedRequest = HttpRequest(
                        path = "/pets", method = "POST",
                        headers = mapOf("Content-Type" to "application/json", "Specmatic-Response-Code" to "201"),
                        body = parsedJSONObject("""{"petType": "cat", "color": "black"}""")
                    )

                    val results = filteredFeature.executeTests(object : TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse {
                            assertThat(request).isEqualTo(expectedRequest)
                            return HttpResponse(
                                status = 201,
                                body = parsedJSONObject("""{"id": 1, "petType": "cat", "color": "black"}""")
                            ).also { println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n")) }
                        }
                    }).results

                    println(results.joinToString("\n\n") { it.reportString() })
                    assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(1)
                }
            }

            @Test
            fun `should complain when discriminator is present but invalid`() {
                Flags.using(EXAMPLE_DIRECTORIES to exampleWithInvalidDisc.canonicalPath) {
                    val exception = assertThrows<ContractException> {
                        val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()
                        val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "POST" })
                        filteredFeature.validateExamplesOrException()
                    }

                    assertThat(exception.report()).contains(
                        "Error loading example for POST /pets -> 201 from ${exampleWithInvalidDisc.resolve("partial_example.json").canonicalPath}",
                        "In scenario \"Add a new pet. Response: Pet added successfully\"",
                        "API: POST /pets -> 201",
                        ">> REQUEST.BODY.petType",
                        "R3001: Discriminator mismatch",
                        "Expected the value of discriminator property to be one of dog, cat but it was UNKNOWN"
                    )
                }
            }

            @Test
            fun `should be able to load and run partial example with body token`() {
                Flags.using(EXAMPLE_DIRECTORIES to partialWithBodyToken.canonicalPath) {
                    val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()

                    val responseBody = parsedJSONObject("""{"id": 1, "petType": "cat", "color": "black"}""")
                    val results = feature.executeTests(object : TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse {
                            return when(request.method) {
                                "GET" -> HttpResponse(status = 200, body = JSONArrayValue(listOf(responseBody)))
                                "POST" -> HttpResponse(status = 201, body = responseBody)
                                else -> throw Exception("Unknown method ${request.method}")
                            }.also { println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n")) }
                        }
                    }).results

                    println(results.joinToString("\n\n") { it.reportString() })
                    assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(2)
                }
            }

            @Test
            fun `unexpected key check should not interfere with loading of partial example with no discriminator`() {
                Flags.using(EXAMPLE_DIRECTORIES to exampleWithoutDisc.canonicalPath, EXTENSIBLE_SCHEMA to "true") {
                    val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()
                    assertDoesNotThrow { feature.validateExamplesOrException() }
                }
            }

            @Test
            fun `should allow extra keys in partial example when extensible schema is enabled`() {
                Flags.using(EXAMPLE_DIRECTORIES to extendedPartialExample.canonicalPath, EXTENSIBLE_SCHEMA to "true") {
                    val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()
                    assertDoesNotThrow { feature.validateExamplesOrException() }

                    val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "POST" })
                    val results = filteredFeature.executeTests(object : TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse {
                            assertThat((request.body as JSONObjectValue).jsonObject).containsEntry("extraKey", StringValue("extraValue"))
                            return HttpResponse(
                                status = 201,
                                body = parsedJSONObject("""{"id": 1, "petType": "cat", "color": "black"}""")
                            ).also { println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n")) }
                        }
                    }).results

                    println(results.joinToString("\n\n") { it.reportString() })
                    assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(1)
                }
            }
        }
    }
}

private fun <T> withServiceLoaderEntries(entries: Map<Class<*>, String>, block: () -> T): T {
    val previousContextClassLoader = Thread.currentThread().contextClassLoader
    val tempDir = Files.createTempDirectory("specmatic-service-loader-test")
    val servicesDir = tempDir.resolve("META-INF/services")
    Files.createDirectories(servicesDir)
    entries.forEach { (service, implementationClassName) ->
        Files.writeString(servicesDir.resolve(service.name), "$implementationClassName\n")
    }

    val classLoader = URLClassLoader(arrayOf(tempDir.toUri().toURL()), previousContextClassLoader)
    Thread.currentThread().contextClassLoader = classLoader

    return try {
        block()
    } finally {
        Thread.currentThread().contextClassLoader = previousContextClassLoader
        classLoader.close()
        tempDir.toFile().deleteRecursively()
    }
}

class RowValueLookupFixtureExecutor : OpenAPIFixtureExecutor {
    override fun execute(
        id: String,
        fixtures: List<Value>,
        fixtureDiscriminatorKey: String,
        executionMetadata: FixtureExecutionMetadata,
        substitution: Substitution
    ): FixtureExecutionDetails {
        val updatedSubstitution = substitution
            .upsertStoreUsing(StringValue("(ORDER_ID:string)"), StringValue("order-123"))
            .upsertStoreUsing(StringValue("(PAGE:string)"), StringValue("page-7"))
            .upsertStoreUsing(StringValue("(TENANT:string)"), StringValue("north"))
            .upsertStoreUsing(StringValue("(NAME:string)"), StringValue("Sherlock"))
            .upsertStoreUsing(StringValue("(COUNT:number)"), NumberValue(42))

        return FixtureExecutionDetails(
            combinedResult = Result.Success(),
            updatedSubstitution = updatedSubstitution
        )
    }
}

class RowValueLookupContractTestInterceptor : ContractTestInterceptor {
    override fun updateRequest(
        testScenario: Scenario,
        originalScenario: Scenario,
        httpRequest: HttpRequest,
        substitution: Substitution,
    ): InterceptResult<HttpRequest> {
        return InterceptResult.Processed(
            value = originalScenario.resolveRequestSubstitutions(httpRequest, substitution)
        )
    }

    override fun updateSubstitution(
        testScenario: Scenario,
        originalScenario: Scenario,
        httpResponse: HttpResponse,
        substitution: Substitution,
    ): InterceptResult<Substitution> {
        val example = testScenario.exampleRow?.scenarioStub ?: return InterceptResult.PassThrough
        return InterceptResult.Processed(
            value = HasValue(
                substitution.upsertStoreUsing(
                    runningValue = httpResponse.toJSON(),
                    originalValue = example.response().toJSON(),
                )
            )
        )
    }
}
