package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Dictionary
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.NoBodyValue
import io.specmatic.core.Result
import io.specmatic.core.Results
import io.specmatic.core.Resolver
import io.specmatic.core.Scenario
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.substitution.SubstitutionImpl
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.test.FixtureExecutionDetails
import io.specmatic.test.TestExecutor
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import io.specmatic.test.interceptor.ContractTestInterceptor
import io.specmatic.test.interceptor.InterceptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

class TestSubstitutionIntegrationTest {
    @BeforeEach
    fun reset() {
        SubstitutionFixtureExecutor.reset()
        SubstitutionTestExecutor.reset()
    }

    @Test
    fun `substitution flow keeps substitution chain across before test and after fixtures`() {
        val specFile = File("src/test/resources/openapi/test_substitution/openapi.yaml")
        val valueDictionary = Dictionary(mapOf("(number)" to NumberValue(123), "(boolean)" to BooleanValue(false)))
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath)
            .toFeature()
            .loadExternalisedExamples()

        feature.validateExamplesOrException()
        val results = withServiceLoaderEntries(
            mapOf(
                OpenAPIFixtureExecutor::class.java to SubstitutionFixtureExecutor::class.java.name,
                ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name
            )
        ) {
            val updatedFeature = feature.copy(scenarios = feature.scenarios.map { it.copy(dictionary = valueDictionary) })
            updatedFeature.enableGenerativeTesting().executeTests(SubstitutionTestExecutor())
        }

        assertThat(results.successCount).withFailMessage { results.report() }.isEqualTo(4)
        assertThat(results.failureCount).withFailMessage { results.report() }.isEqualTo(12)
        assertThat(SubstitutionFixtureExecutor.calls).containsExactly("before", "after", "before", "after", "before", "after", "before", "after")

        assertThat(SubstitutionFixtureExecutor.beforeFixtureRequests).hasSize(8)
        assertThat(SubstitutionFixtureExecutor.afterFixtureRequests).hasSize(8)
        assertThat(
            SubstitutionFixtureExecutor.updatedSubstitution.substitute(
                StringValue(string = "$(AFTER_2)"),
                pattern = StringPattern(),
                key = null
            )
        ).isEqualTo(HasValue(StringValue("after2")))

        assertThat(SubstitutionTestExecutor.requestsSeen).hasSize(16).allSatisfy { request ->
            assertThat(request.path).isEqualTo("/test")
            assertThat(request.method).isEqualTo("POST")
        }

        assertThat(SubstitutionTestExecutor.requestsSeen.map { it.body }).containsExactlyElementsOf(
            listOf(
                // 1. The happy path payload and Positive scenarios for 'Random-String'
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),

                // 2. The empty body test case
                NoBodyValue,

                // 3. Negative scenarios for 'before1'
                parsedJSONObject("""{ "before1": null, "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": 123, "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": false, "before2": "before2" }"""),

                // 4. Negative scenarios for 'before2'
                parsedJSONObject("""{ "before1": "before1", "before2": null }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": 123 }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": false }"""),

                // 5. Negative scenarios for 'Random-String'
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
                parsedJSONObject("""{ "before1": "before1", "before2": "before2" }"""),
            )
        )
    }

    private fun withServiceLoaderEntries(entries: Map<Class<*>, String>, block: () -> Results): Results {
        val previousContextClassLoader = Thread.currentThread().contextClassLoader
        val tempDir = Files.createTempDirectory("specmatic-service-loader-test")
        val servicesDir = tempDir.resolve("META-INF/services")
        Files.createDirectories(servicesDir)

        entries.forEach { (service, implementationClassName) ->
            val serviceFile = servicesDir.resolve(service.name)
            Files.writeString(serviceFile, "$implementationClassName\n")
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
}

class SubstitutionFixtureExecutor : OpenAPIFixtureExecutor {
    override fun execute(
        id: String,
        fixtures: List<Value>,
        fixtureDiscriminatorKey: String,
        substitution: Substitution
    ): FixtureExecutionDetails {
        calls.add(fixtureDiscriminatorKey)
        val expectedFixtures = when (fixtureDiscriminatorKey) {
            "before" -> beforeFixtures
            "after" -> afterFixtures
            else -> error("Unexpected fixture discriminator key $fixtureDiscriminatorKey for id $id")
        }

        assertThat(fixtures).containsExactlyElementsOf(expectedFixtures)
        when (fixtureDiscriminatorKey) {
            "before" -> beforeFixtureRequests.addAll(fixtures)
            "after" -> afterFixtureRequests.addAll(fixtures)
        }

        var currentSubstitution = substitution
        fixtures.forEachIndexed { index, _ ->
            when (fixtureDiscriminatorKey) {
                "before" -> {
                    if (index == 1) {
                        assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                    }
                    currentSubstitution = when (index) {
                        0 -> currentSubstitution.upsertStoreUsing(
                            originalValue = StringValue("(BEFORE_1:string)"),
                            runningValue = StringValue("before1")
                        )
                        1 -> currentSubstitution.upsertStoreUsing(
                            originalValue = StringValue("(BEFORE_2:string)"),
                            runningValue = StringValue("before2")
                        )
                        else -> currentSubstitution
                    }
                }
                "after" -> {
                    if (index == 0) {
                        assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                        assertSubstitutes(currentSubstitution, "BEFORE_2", "before2")
                        assertSubstitutes(currentSubstitution, "TEST", "test")
                    }
                    if (index == 1) {
                        assertSubstitutes(currentSubstitution, "AFTER_1", "after1")
                    }
                    currentSubstitution = when (index) {
                        0 -> currentSubstitution.upsertStoreUsing(
                            originalValue = StringValue("(AFTER_1:string)"),
                            runningValue = StringValue("after1")
                        )
                        1 -> currentSubstitution.upsertStoreUsing(
                            originalValue = StringValue("(AFTER_2:string)"),
                            runningValue = StringValue("after2")
                        )
                        else -> currentSubstitution
                    }
                }
            }
        }

        when (fixtureDiscriminatorKey) {
            "before" -> {
                assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                assertSubstitutes(currentSubstitution, "BEFORE_2", "before2")
            }
            "after" -> {
                assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                assertSubstitutes(currentSubstitution, "BEFORE_2", "before2")
                assertSubstitutes(currentSubstitution, "TEST", "test")
                assertSubstitutes(currentSubstitution, "AFTER_1", "after1")
            }
        }

        updatedSubstitution = currentSubstitution
        return FixtureExecutionDetails(
            combinedResult = Result.Success(),
            updatedSubstitution = updatedSubstitution
        )
    }

    companion object {
        val calls: MutableList<String> = mutableListOf()
        val beforeFixtures = listOf(
            parsedJSONObject("""
            {
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/before1",
                "method": "POST",
                "body": {}
              },
              "http-response": {
                "status": 200,
                "body": {
                  "before1": "(BEFORE_1:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
            """.trimIndent()),
            parsedJSONObject("""
            {
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/before2",
                "method": "POST",
                "body": {
                  "before1": "$(BEFORE_1)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "before2": "(BEFORE_2:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
            """.trimIndent())
        )
        val afterFixtures = listOf(
            parsedJSONObject("""
            {
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/after1",
                "method": "POST",
                "body": {
                  "before1": "$(BEFORE_1)",
                  "before2": "$(BEFORE_2)",
                  "test": "$(TEST)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "after1": "(AFTER_1:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }""".trimIndent()),
            parsedJSONObject(
                $$"""
            {
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/after2",
                "method": "POST",
                "body": {
                  "before1": "$(BEFORE_1)",
                  "before2": "$(BEFORE_2)",
                  "test": "$(TEST)",
                  "after1": "$(AFTER_1)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "after2": "$match(exact:after2)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
            """.trimIndent())
        )
        val beforeFixtureRequests = mutableListOf<Value>()
        val afterFixtureRequests = mutableListOf<Value>()
        var updatedSubstitution: Substitution = SubstitutionImpl.empty(Resolver())

        fun reset() {
            calls.clear()
            beforeFixtureRequests.clear()
            afterFixtureRequests.clear()
            updatedSubstitution = SubstitutionImpl.empty(Resolver())
        }
    }

    private fun assertSubstitutes(substitution: Substitution, lookupKey: String, expectedValue: String) {
        assertThat(
            substitution.substitute(StringValue($$"$($$lookupKey)"), StringPattern(), null).value
        ).isEqualTo(StringValue(expectedValue))
    }
}

class SubstitutionTestExecutor : TestExecutor {
    override fun execute(request: HttpRequest): HttpResponse {
        requestsSeen.add(request)

        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/test")
        assertThat(request.body).isEqualTo(
            parsedJSONObject("""
            {
              "before1": "before1",
              "before2": "before2"
            }
            """.trimIndent())
        )

        return HttpResponse(
            status = 200,
            body = parsedJSONObject("""
            {
              "before1": "before1",
              "before2": "before2",
              "test": "test"
            }
            """.trimIndent()),
            headers = mapOf("Content-Type" to "application/json")
        )
    }

    companion object {
        val requestsSeen: MutableList<HttpRequest> = mutableListOf()

        fun reset() {
            requestsSeen.clear()
        }
    }
}

class ServiceLoaderTestInterceptor : ContractTestInterceptor {
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
