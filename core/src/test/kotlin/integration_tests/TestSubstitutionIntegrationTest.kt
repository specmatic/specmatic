package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Dictionary
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Results
import io.specmatic.core.Scenario
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.substitution.SubstitutionImpl
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.FixtureExecutionDetails
import io.specmatic.test.TestExecutor
import io.specmatic.test.fixtures.FixtureExecutionMetadata
import io.specmatic.test.fixtures.FixtureScenarioType
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
            updatedFeature.executeTests(SubstitutionTestExecutor())
        }

        assertThat(results.successCount).withFailMessage { results.report() }.isEqualTo(1)
        assertThat(results.failureCount).withFailMessage { results.report() }.isEqualTo(0)

        assertThat(SubstitutionFixtureExecutor.calls).hasSize(2)
        assertThat(SubstitutionFixtureExecutor.calls.count { it == "before" }).isEqualTo(1)
        assertThat(SubstitutionFixtureExecutor.calls.count { it == "after" }).isEqualTo(1)

        assertThat(SubstitutionFixtureExecutor.receivedContexts).hasSize(2)
        assertThat(SubstitutionFixtureExecutor.beforeFixtureRequests).hasSize(2)
        assertThat(SubstitutionFixtureExecutor.afterFixtureRequests).hasSize(2)

        assertThat(
            SubstitutionFixtureExecutor.positiveAfterUpdatedSubstitution.substitute(
                StringValue("$(BEFORE_POSITIVE)"),
                StringPattern(),
            )
        ).isEqualTo(HasValue(StringValue("beforePositive")))

        assertThat(
            SubstitutionFixtureExecutor.positiveAfterUpdatedSubstitution.substitute(
                StringValue("$(AFTER_POSITIVE)"),
                StringPattern(),
            )
        ).isEqualTo(HasValue(StringValue("afterPositive")))

        assertThat(SubstitutionTestExecutor.requestsSeen).hasSize(1).allSatisfy { request ->
            assertThat(request.path).isEqualTo("/test")
            assertThat(request.method).isEqualTo("POST")
        }

        assertThat(SubstitutionTestExecutor.requestsSeen.map { it.body }).containsExactlyElementsOf(
            listOf(
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
        executionMetadata: FixtureExecutionMetadata,
        substitution: Substitution,
        data: JSONObjectValue,
    ): FixtureExecutionDetails {
        val filteredFixtures = fixtures.filterFor(executionMetadata)
        calls.add(fixtureDiscriminatorKey)
        receivedContexts.add(executionMetadata)

        val expectedFixtures = when (fixtureDiscriminatorKey) {
            "before" -> when (executionMetadata.scenarioType) {
                FixtureScenarioType.POSITIVE -> positiveBeforeFixtures
                FixtureScenarioType.NEGATIVE -> negativeBeforeFixtures
            }

            "after" -> when (executionMetadata.scenarioType) {
                FixtureScenarioType.POSITIVE -> positiveAfterFixtures
                FixtureScenarioType.NEGATIVE -> negativeAfterFixtures
            }

            else -> error("Unexpected fixture discriminator key $fixtureDiscriminatorKey for id $id")
        }

        assertThat(filteredFixtures).containsExactlyElementsOf(expectedFixtures)
        when (fixtureDiscriminatorKey) {
            "before" -> beforeFixtureRequests.addAll(filteredFixtures)
            "after" -> afterFixtureRequests.addAll(filteredFixtures)
        }

        var currentSubstitution = substitution
        filteredFixtures.forEach { fixture ->
            currentSubstitution = when (fixture.httpPath()) {
                "/beforeAll" -> {
                    currentSubstitution
                        .upsertStoreUsing(StringValue("(BEFORE_1:string)"), StringValue("before1"))
                        .upsertStoreUsing(StringValue("(BEFORE_2:string)"), StringValue("before2"))
                }

                "/beforePositive" -> {
                    assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                    assertSubstitutes(currentSubstitution, "BEFORE_2", "before2")
                    currentSubstitution.upsertStoreUsing(
                        StringValue("(BEFORE_POSITIVE:string)"),
                        StringValue("beforePositive")
                    )
                }

                "/beforeNegative" -> {
                    assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                    assertSubstitutes(currentSubstitution, "BEFORE_2", "before2")
                    currentSubstitution.upsertStoreUsing(
                        StringValue("(BEFORE_NEGATIVE:string)"),
                        StringValue("beforeNegative")
                    )
                }

                "/afterAll" -> {
                    assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                    assertSubstitutes(currentSubstitution, "BEFORE_2", "before2")
                    currentSubstitution.upsertStoreUsing(
                        StringValue("(AFTER_ALL:string)"),
                        StringValue("afterAll")
                    )
                }

                "/afterPositive" -> {
                    assertSubstitutes(currentSubstitution, "TEST", "test")
                    assertSubstitutes(currentSubstitution, "BEFORE_POSITIVE", "beforePositive")
                    assertSubstitutes(currentSubstitution, "AFTER_ALL", "afterAll")
                    currentSubstitution.upsertStoreUsing(
                        StringValue("(AFTER_POSITIVE:string)"),
                        StringValue("afterPositive")
                    )
                }

                "/afterNegative" -> {
                    assertSubstitutes(currentSubstitution, "BEFORE_NEGATIVE", "beforeNegative")
                    assertSubstitutes(currentSubstitution, "AFTER_ALL", "afterAll")
                    currentSubstitution.upsertStoreUsing(
                        StringValue("(AFTER_NEGATIVE:string)"),
                        StringValue("afterNegative")
                    )
                }

                else -> currentSubstitution
            }
        }

        when (fixtureDiscriminatorKey) {
            "before" -> {
                assertSubstitutes(currentSubstitution, "BEFORE_1", "before1")
                assertSubstitutes(currentSubstitution, "BEFORE_2", "before2")
                when (executionMetadata.scenarioType) {
                    FixtureScenarioType.POSITIVE -> assertSubstitutes(currentSubstitution, "BEFORE_POSITIVE", "beforePositive")
                    FixtureScenarioType.NEGATIVE -> assertSubstitutes(currentSubstitution, "BEFORE_NEGATIVE", "beforeNegative")
                }
            }

            "after" -> {
                assertSubstitutes(currentSubstitution, "AFTER_ALL", "afterAll")
                when (executionMetadata.scenarioType) {
                    FixtureScenarioType.POSITIVE -> assertSubstitutes(currentSubstitution, "AFTER_POSITIVE", "afterPositive")
                    FixtureScenarioType.NEGATIVE -> assertSubstitutes(currentSubstitution, "AFTER_NEGATIVE", "afterNegative")
                }
            }
        }

        updatedSubstitution = currentSubstitution
        if (fixtureDiscriminatorKey == "after") {
            when (executionMetadata.scenarioType) {
                FixtureScenarioType.POSITIVE -> positiveAfterUpdatedSubstitution = currentSubstitution
                FixtureScenarioType.NEGATIVE -> negativeAfterUpdatedSubstitution = currentSubstitution
            }
        }

        return FixtureExecutionDetails(
            combinedResult = Result.Success(),
            updatedSubstitution = currentSubstitution
        )
    }

    companion object {
        val calls = mutableListOf<String>()
        val receivedContexts = mutableListOf<FixtureExecutionMetadata>()

        private val beforeAllFixture = parsedJSONObject("""
            {
              "executeFor": {
                "scenarios": "all"
              },
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/beforeAll",
                "method": "POST",
                "body": {}
              },
              "http-response": {
                "status": 200,
                "body": {
                  "before1": "(BEFORE_1:string)",
                  "before2": "(BEFORE_2:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
        """.trimIndent())
        private val beforePositiveFixture = parsedJSONObject("""
            {
              "executeFor": {
                "scenarios": "positive"
              },
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/beforePositive",
                "method": "POST",
                "body": {
                  "before1": "$(BEFORE_1)",
                  "before2": "$(BEFORE_2)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "beforePositive": "(BEFORE_POSITIVE:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
        """.trimIndent())
        private val beforeNegativeFixture = parsedJSONObject("""
            {
              "executeFor": {
                "scenarios": "negative"
              },
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/beforeNegative",
                "method": "POST",
                "body": {
                  "before1": "$(BEFORE_1)",
                  "before2": "$(BEFORE_2)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "beforeNegative": "(BEFORE_NEGATIVE:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
        """.trimIndent())
        private val afterAllFixture = parsedJSONObject("""
            {
              "executeFor": {
                "scenarios": "all"
              },
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/afterAll",
                "method": "POST",
                "body": {
                  "before1": "$(BEFORE_1)",
                  "before2": "$(BEFORE_2)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "afterAll": "(AFTER_ALL:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
        """.trimIndent())
        private val afterPositiveFixture = parsedJSONObject("""
            {
              "executeFor": {
                "scenarios": "positive"
              },
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/afterPositive",
                "method": "POST",
                "body": {
                  "test": "$(TEST)",
                  "beforePositive": "$(BEFORE_POSITIVE)",
                  "afterAll": "$(AFTER_ALL)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "afterPositive": "(AFTER_POSITIVE:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
        """.trimIndent())
        private val afterNegativeFixture = parsedJSONObject("""
            {
              "executeFor": {
                "scenarios": "negative"
              },
              "type": "http",
              "http-request": {
                "baseUrl": "http://localhost:8000",
                "path": "/afterNegative",
                "method": "POST",
                "body": {
                  "beforeNegative": "$(BEFORE_NEGATIVE)",
                  "afterAll": "$(AFTER_ALL)"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "afterNegative": "(AFTER_NEGATIVE:string)"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
        """.trimIndent())

        val positiveBeforeFixtures = listOf(beforeAllFixture, beforePositiveFixture)
        val negativeBeforeFixtures = listOf(beforeAllFixture, beforeNegativeFixture)
        val positiveAfterFixtures = listOf(afterAllFixture, afterPositiveFixture)
        val negativeAfterFixtures = listOf(afterAllFixture, afterNegativeFixture)

        val beforeFixtureRequests = mutableListOf<Value>()
        val afterFixtureRequests = mutableListOf<Value>()
        var updatedSubstitution: Substitution = SubstitutionImpl.empty()
        var positiveAfterUpdatedSubstitution: Substitution = SubstitutionImpl.empty()
        var negativeAfterUpdatedSubstitution: Substitution = SubstitutionImpl.empty()

        fun reset() {
            calls.clear()
            receivedContexts.clear()
            beforeFixtureRequests.clear()
            afterFixtureRequests.clear()
            updatedSubstitution = SubstitutionImpl.empty()
            positiveAfterUpdatedSubstitution = SubstitutionImpl.empty()
            negativeAfterUpdatedSubstitution = SubstitutionImpl.empty()
        }
    }

    private fun assertSubstitutes(substitution: Substitution, lookupKey: String, expectedValue: String) {
        assertThat(
            substitution.substitute(StringValue($$"$($$lookupKey)"), StringPattern()).value
        ).isEqualTo(StringValue(expectedValue))
    }
}

class SubstitutionTestExecutor : TestExecutor {
    override fun execute(request: HttpRequest): HttpResponse {
        requestsSeen.add(request)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/test")

        return if (request.isPositiveRequest()) {
            HttpResponse(
                status = 200,
                body = parsedJSONObject("""{"before1": "before1", "before2": "before2", "test": "test"}"""),
                headers = mapOf("Content-Type" to "application/json")
            )
        } else {
            HttpResponse(
                status = 400,
                body = parsedJSONObject("""{"error": "bad request"}"""),
                headers = mapOf("Content-Type" to "application/json")
            )
        }
    }

    companion object {
        val requestsSeen = mutableListOf<HttpRequest>()
        private val positiveRequestBody = parsedJSONObject("""{"before1": "before1", "before2": "before2"}""")
        private val positiveHeaderPattern = Regex("A+")

        fun reset() {
            requestsSeen.clear()
        }
    }

    private fun HttpRequest.isPositiveRequest(): Boolean {
        val randomHeader = headers["Random-String"]
        val validHeader = randomHeader == null || positiveHeaderPattern.matches(randomHeader)
        return body == positiveRequestBody && validHeader
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

private fun List<Value>.filterFor(context: FixtureExecutionMetadata): List<Value> {
    return filter { fixture ->
        when (fixture.fixtureScenarioSelector()) {
            null, "all" -> true
            "positive" -> context.scenarioType == FixtureScenarioType.POSITIVE
            "negative" -> context.scenarioType == FixtureScenarioType.NEGATIVE
            else -> error("Unexpected executeFor.scenarios value in test fixture")
        }
    }
}

private fun Value.fixtureScenarioSelector(): String? {
    return ((this as? JSONObjectValue)
        ?.jsonObject
        ?.get("executeFor") as? JSONObjectValue)
        ?.jsonObject
        ?.get("scenarios")
        ?.toStringLiteral()
        ?.lowercase()
}

private fun Value.httpPath(): String? {
    return ((this as? JSONObjectValue)
        ?.jsonObject
        ?.get("http-request") as? JSONObjectValue)
        ?.jsonObject
        ?.get("path")
        ?.toStringLiteral()
}
