package integration_tests

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.log.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.ADDITIONAL_EXAMPLE_PARAMS_FILE
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.value.*
import io.specmatic.test.ExampleProcessor
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import java.io.File

class LoadTestsFromExternalisedFiles {

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
    fun `tests from external examples validate response values when the VALIDATE_RESPONSE_VALUE flag is true`() {
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true) {
            every { isResponseValueValidationEnabled() } returns true
            every { getWorkflowDetails() } returns null
        }
        mockkObject(SpecmaticConfig.Companion)
        every { SpecmaticConfig.Companion.getAttributeSelectionPattern(any()) } returns AttributeSelectionPattern()
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/has_inline_and_external_examples.yaml", specmaticConfig)
            .toFeature()
            .loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val path = request.path ?: fail("Path expected")
                val id = path.split("/").last()

                return when(id) {
                    "123" -> HttpResponse(200, parsedJSONObject("""{"id": 123, "name": "John Doe"}"""))
                    "456" -> HttpResponse(200, parsedJSONObject("""{"id": 456, "name": "Alice Johnson"}"""))
                    else -> HttpResponse(400, "Expected either 123 or 456")
                }.also {
                    println("---")
                    println(request.toLogString())
                    println(it.toLogString())
                    println()
                }
            }
        })

        assertThat(results.testCount).isEqualTo(3)
    }

    @Test
    fun `tests from external examples reject responses with values different from the example when the VALIDATE_RESPONSE_VALUE flag is true`() {
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true) {
            every { isResponseValueValidationEnabled() } returns true
            every { getWorkflowDetails() } returns null
        }
        mockkObject(SpecmaticConfig.Companion)
        every { SpecmaticConfig.Companion.getAttributeSelectionPattern(any()) } returns AttributeSelectionPattern()

        val feature = OpenApiSpecification
            .fromFile(
                "src/test/resources/openapi/has_inline_and_external_examples.yaml",
                specmaticConfig
            )
            .toFeature()
            .loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val path = request.path ?: fail("Path expected")
                val id = path.split("/").last()

                return when(id) {
                    "123" -> HttpResponse(200, parsedJSONObject("""{"id": 123, "name": "Unexpected name instead of John Doe"}"""))
                    "456" -> HttpResponse(200, parsedJSONObject("""{"id": 456, "name": "Unexpected name instead of Alice Johnson"}"""))
                    else -> HttpResponse(400, "Expected either 123 or 456")
                }.also {
                    println("---")
                    println(request.toLogString())
                    println(it.toLogString())
                    println()
                }
            }
        })

        println(results.report())

        assertThat(results.testCount).isEqualTo(3)
        assertThat(results.failureCount).isEqualTo(3)
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
    inner class ExampleResolution {
        @BeforeEach
        fun setup() {
            System.setProperty(ADDITIONAL_EXAMPLE_PARAMS_FILE, "src/test/resources/openapi/config_and_entity_tests/config.json")
            ExampleProcessor.cleanStores()
        }

        @AfterEach
        fun tearDown() {
            System.clearProperty(ADDITIONAL_EXAMPLE_PARAMS_FILE)
            ExampleProcessor.cleanStores()
        }

        @Test
        fun `should be able load example with substitutions for scalar and non scalar values`() {
            val feature = OpenApiSpecification.fromFile(
                "src/test/resources/openapi/config_and_entity_tests/spec.yaml"
            ).toFeature().loadExternalisedExamples()

            assertDoesNotThrow { feature.validateExamplesOrException() }
        }

        @Test
        fun `should not resolve $rand lookups on initial example load`() {
            val feature = OpenApiSpecification.fromFile(
                "src/test/resources/openapi/config_and_entity_tests/spec.yaml"
            ).toFeature().loadExternalisedExamples()

            val patchScenario = feature.scenarios.first { it.method == "PATCH" }
            val requestExample = patchScenario.examples.first().rows.first().requestExample
            val requestBody = requestExample!!.body as JSONObjectValue

            println(requestBody.toStringLiteral())
            assertThat(requestBody.jsonObject.entries).allSatisfy {
                assertThat(it.value.toStringLiteral()).isEqualTo("\$rand(CONFIG.patch.Pet.${it.key})")
            }
        }

        @Test
        fun `should resolve all lookups before making the request`() {
            val feature = OpenApiSpecification.fromFile(
                "src/test/resources/openapi/config_and_entity_tests/spec.yaml"
            ).toFeature().loadExternalisedExamples()

            val results = feature.executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    println(request.toLogString())
                    assertThat((request.body as JSONObjectValue).jsonObject.entries).allSatisfy {
                        assertThat(ExampleProcessor.isSubstitutionToken(it.value)).isFalse()
                    }

                    return HttpResponse(
                        status = if (request.method == "PATCH") 200 else 201,
                        body = parsedJSONObject("""{"id": 1}""").mergeWith(request.body)
                    )
                }
            }).results

            assertThat(results).isNotEmpty.hasOnlyElementsOfType(Result.Success::class.java)
        }

        @Test
        fun `should retain example information when resolution leads to invalid values`() {
            Flags.using(ADDITIONAL_EXAMPLE_PARAMS_FILE to "src/test/resources/openapi/config_and_entity_tests/invalid_config.json") {
                ExampleProcessor.cleanStores()
                val feature = OpenApiSpecification.fromFile(
                    "src/test/resources/openapi/config_and_entity_tests/spec.yaml"
                ).toFeature().loadExternalisedExamples()

                var requestsCount = 0
                val results = feature.executeTests(object: TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        requestsCount += 1
                        return HttpResponse(
                            status = 201,
                            body = parsedJSONObject("""{"id": 1}""").mergeWith(request.body)
                        ).also { println(request.toLogString()); println(it.toLogString()) }
                    }
                }).results
                val failure = results.filterIsInstance<Result.Failure>().first()
                println(failure.scenario?.testDescription())
                println(failure.reportString())

                assertThat(requestsCount).isEqualTo(1)
                assertThat(results).hasSize(2)
                assertThat(failure.scenario?.testDescription()).containsIgnoringWhitespaces("Scenario: PATCH /pets/(id:number) -> 200 | EX:patch")
                assertThat(failure.reportString()).containsIgnoringWhitespaces("""
                >> REQUEST.BODY.name
                Contract expected string but found value 10 (number)
                >> REQUEST.BODY.tag[0]
                Contract expected string but found value 10 (number)
                >> REQUEST.BODY.details
                Can't generate object value from type number
                >> REQUEST.BODY.adopted
                Contract expected boolean but found value "false"
                >> REQUEST.BODY.age
                Contract expected number but found value "20"
                >> REQUEST.BODY.birthdate
                Date types can only be represented using strings
             """.trimIndent())
            }

            // Ideally >> REQUEST.BODY.details
            // Should contain "Contract expected JSON object but found value 10 (number)"
        }

        @Test
        fun `should retain example information when rand resolve fails to find a substitution`() {
            Flags.using(ADDITIONAL_EXAMPLE_PARAMS_FILE to "src/test/resources/openapi/config_and_entity_tests/incomplete_config.json") {
                ExampleProcessor.cleanStores()
                val feature = OpenApiSpecification.fromFile(
                    "src/test/resources/openapi/config_and_entity_tests/spec.yaml"
                ).toFeature().loadExternalisedExamples()

                var requestsCount = 0
                val results = feature.executeTests(object: TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        requestsCount += 1
                        return HttpResponse(
                            status = 201,
                            body = parsedJSONObject("""{"id": 1}""").mergeWith(request.body)
                        )
                    }
                }).results
                val failure = results.filterIsInstance<Result.Failure>().first()
                println(failure.scenario?.testDescription())
                println(failure.reportString())

                assertThat(requestsCount).isEqualTo(1)
                assertThat(results).hasSize(2)
                assertThat(failure.scenario?.testDescription()).containsIgnoringWhitespaces("Scenario: PATCH /pets/(id:number) -> 200 | EX:patch")
                assertThat(failure.reportString()).containsIgnoringWhitespaces("""
                >> CONFIG.patch.Pet.name
                Couldn't pick a random value from "CONFIG.patch.Pet.name" that was not equal to "Tom"
                """.trimIndent())
            }
        }

        @Test
        fun `should be able to load partial example with missing discriminator but has asserts`() {
            val specFile = File("src/test/resources/openapi/partial_with_discriminator/openapi.yaml")
            val examplesDir = specFile.parentFile.resolve("example_with_asserts")

            Flags.using(EXAMPLE_DIRECTORIES to examplesDir.canonicalPath) {
                val feature = parseContractFileToFeature(specFile).copy(strictMode = true).loadExternalisedExamples()
                feature.validateExamplesOrException()

                var entity = JSONObjectValue()
                val results = feature.executeTests(object: TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        return when(request.method) {
                            "GET" -> HttpResponse(status = 200, body = JSONArrayValue(listOf(entity)))
                            "POST" -> {
                                assertThat(request.body).isEqualTo(parsedJSONObject("""{"petType": "cat", "color": "black"}"""))
                                val withId = JSONObjectValue((request.body as JSONObjectValue).jsonObject.plus("id" to NumberValue(1)))
                                HttpResponse(status = 201, body = withId).also { entity = withId }
                            }
                            else ->  throw Exception("Unknown method ${request.method}")
                        }.also { println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n")) }
                    }
                }).results

                assertThat(results).isNotEmpty.hasOnlyElementsOfType(Result.Success::class.java).hasSize(2)
            }
        }

        @Test
        fun `should complain when response doesn't match the asserts in the example`() {
            val specFile = File("src/test/resources/openapi/partial_with_discriminator/openapi.yaml")
            val examplesDir = specFile.parentFile.resolve("example_with_asserts")

            Flags.using(EXAMPLE_DIRECTORIES to examplesDir.canonicalPath) {
                val feature = parseContractFileToFeature(specFile).copy(strictMode = true).loadExternalisedExamples()
                feature.validateExamplesOrException()

                val invalidEntity = parsedJSONObject("""{ "id": 1, "color": "white", "petType": "dog" }""")
                val results = feature.executeTests(object: TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        return when(request.method) {
                            "GET" -> HttpResponse(status = 200, body = JSONArrayValue(listOf(invalidEntity)))
                            "POST" -> {
                                assertThat(request.body).isEqualTo(parsedJSONObject("""{"petType": "cat", "color": "black"}"""))
                                val withId = JSONObjectValue((request.body as JSONObjectValue).jsonObject.plus("id" to NumberValue(1)))
                                HttpResponse(status = 201, body = withId)
                            }
                            else ->  throw Exception("Unknown method ${request.method}")
                        }.also { println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n")) }
                    }
                }).results

                assertThat(results).hasSize(2)
                assertThat(results.filterIsInstance<Result.Failure>()).hasSize(1)

                val failure = results.filterIsInstance<Result.Failure>().first()
                assertThat(failure.reportString()).containsIgnoringWhitespaces("""
                In scenario "List all pets. Response: A list of pets"
                API: GET /pets -> 200
                >> RESPONSE.BODY[0].petType
                Expected "dog" to equal "cat"
                >> RESPONSE.BODY[0].color
                Expected "white" to equal "black"
                """.trimIndent())
            }
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
            assertThat(exception.report()).isEqualToNormalizingWhitespace("""
            Error loading example for PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201 from ${invalidExamplesDir.resolve("pets_post.json").canonicalPath}

            >> REQUEST.PATH.creatorId  
            Expected number as per the specification, but the example pets_post had "abc".
            >> REQUEST.PATH.petId  
            Expected number as per the specification, but the example pets_post had string.
            
            >> REQUEST.QUERY-PARAMS.creatorId
            Expected number as per the specification, but the example pets_post had "abc".
            >> REQUEST.QUERY-PARAMS.petId
            Expected number as per the specification, but the example pets_post had string.

            >> REQUEST.HEADERS.CREATOR-ID
            Expected number as per the specification, but the example pets_post had "abc".
            >> REQUEST.HEADERS.PET-ID  
            Expected number as per the specification, but the example pets_post had string.

            >> REQUEST.BODY.creatorId  
            Expected number as per the specification, but the example pets_post had "123".  
            >> REQUEST.BODY.petId  
            Expected number as per the specification, but the example pets_post had string.

            >> RESPONSE.BODY.id  
            Expected number as per the specification, but the example pets_post had string.
            >> RESPONSE.BODY.traceId  
            Expected string as per the specification, but the example pets_post had number.
            >> RESPONSE.BODY.creatorId  
            Expected number as per the specification, but the example pets_post had "123".  
            """.trimIndent())
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

                val expectedGoodRequest = HttpRequest(
                    path = "/creators/123/pets/999",
                    method = "PATCH",
                    queryParams = QueryParameters(mapOf("creatorId" to "123", "petId" to "999")),
                    headers = mapOf("Content-Type" to "application/json", "CREATOR-ID" to "123", "PET-ID" to "999", "Specmatic-Response-Code" to "201"),
                    body = JSONObjectValue(mapOf("creatorId" to NumberValue(123), "petId" to NumberValue(999))),
                )

                val results = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        return if (request.headers["Specmatic-Response-Code"] == "400") {
                            HttpResponse(status = 400, body = parsedJSONObject("""{"code": 400, "message": "BadRequest"}"""))
                        } else {
                            assertThat(request).isEqualTo(expectedGoodRequest)
                            val responseBody = (request.body as JSONObjectValue).jsonObject + mapOf(
                                "id" to NumberValue(999), "traceId" to StringValue("123"),
                            )
                            HttpResponse(status = 201, body = JSONObjectValue(responseBody))
                        }.also {
                            println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n"))
                        }
                    }
                }).results

                println(results.joinToString("\n\n") { it.reportString() })
                assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(23)
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

                val results = feature.executeTests(object: TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        return if (request.headers["Specmatic-Response-Code"] == "400") {
                            assertThat(request.body).isEqualTo(
                                JSONObjectValue(mapOf("creatorId" to StringValue("JohnDoe"), "petId" to NumberValue(999)))
                            )
                            HttpResponse(status = 400, body = parsedJSONObject("""{"code": 400, "message": "BadRequest"}"""))
                        } else {
                            assertThat(request).isEqualTo(expectedGoodRequest)
                            val responseBody = (request.body as JSONObjectValue).jsonObject + mapOf(
                                "id" to NumberValue(999), "traceId" to StringValue("123"),
                            )
                            HttpResponse(status = 201, body = JSONObjectValue(responseBody))
                        }.also {
                            println(listOf(request.toLogString(), it.toLogString()).joinToString(separator = "\n\n"))
                        }
                    }
                }).results

                println(results.joinToString("\n\n") { it.reportString() })
                assertThat(results).hasOnlyElementsOfTypes(Result.Success::class.java).hasSize(2)
            }
        }

        @Nested
        inner class DiscriminatorTests {
            private val discriminatorSpecFile = File("src/test/resources/openapi/partial_with_discriminator/openapi.yaml")
            private val exampleWithDiscriminator= discriminatorSpecFile.parentFile.resolve("example_with_disc")
            private val exampleWithoutDisc = discriminatorSpecFile.parentFile.resolve("example_without_disc")
            private val exampleWithPatternToken = discriminatorSpecFile.parentFile.resolve("example_with_pattern_token")
            private val exampleWithInvalidDisc = discriminatorSpecFile.parentFile.resolve("example_with_invalid_disc")

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
                    val feature = parseContractFileToFeature(discriminatorSpecFile).copy(strictMode = true).loadExternalisedExamples()
                    val filteredFeature = feature.copy(scenarios = feature.scenarios.filter { it.method == "POST" })
                    val exception = assertThrows<ContractException> { filteredFeature.validateExamplesOrException() }

                    assertThat(exception.report()).isEqualToNormalizingWhitespace("""
                    Error loading example for POST /pets -> 201 from ${exampleWithInvalidDisc.resolve("partial_example.json").canonicalPath}
                    >> REQUEST.BODY.petType
                    Expected the value of discriminator property to be one of dog, cat but it was UNKNOWN
                    """.trimIndent())
                }
            }
        }
    }
}
