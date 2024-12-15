package integration_tests

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.log.*
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.Value
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*

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

            override fun print(msg: LogMessage) {
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

            assertThat(unusedExamplesFilePaths).hasSize(1)
            assertThat(unusedExamplesFilePaths.first()).endsWith("irrelevant_test.json")
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
        }
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
        }

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
}
