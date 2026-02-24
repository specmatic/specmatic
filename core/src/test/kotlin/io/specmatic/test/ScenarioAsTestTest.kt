package io.specmatic.test

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import io.specmatic.test.matchers.MatcherExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files

class ScenarioAsTestTest {

    @Nested
    inner class MatcherExecutorTest {
        @Test
        fun `calls matcher executor from service loader when present`() {
            ServiceLoaderTestMatcherExecutor.reset()

            val scenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        response = HttpResponse(200, StringValue("expected"))
                    )
                )
            )

            val result = withServiceLoaderEntries(
                mapOf(MatcherExecutor::class.java to ServiceLoaderTestMatcherExecutor::class.java.name)
            ) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor("actual")).result
            }

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(ServiceLoaderTestMatcherExecutor.callCount).isEqualTo(1)
        }

        @Test
        fun `does not call matcher executor when missing from service loader`() {
            ServiceLoaderTestMatcherExecutor.reset()

            val scenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        response = HttpResponse(200, StringValue("expected"))
                    )
                )
            )

            val result = withServiceLoaderEntries(emptyMap()) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor("actual")).result
            }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestMatcherExecutor.callCount).isEqualTo(0)
        }

        @Test
        fun `does not call matcher executor when example row is null`() {
            ServiceLoaderTestMatcherExecutor.reset()

            val scenario = scenario(exampleRow = null)

            val result = withServiceLoaderEntries(
                mapOf(MatcherExecutor::class.java to ServiceLoaderTestMatcherExecutor::class.java.name)
            ) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor("actual")).result
            }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestMatcherExecutor.callCount).isEqualTo(0)
        }
    }

    @Nested
    inner class FixtureExecutorTest {
        @Test
        fun `calls fixture executor from service loader when present`() {
            ServiceLoaderTestFixtureExecutor.reset()

            val scenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        id = "fixture-id",
                        beforeFixtures = listOf(StringValue("before")),
                        afterFixtures = listOf(StringValue("after"))
                    )
                )
            )

            val result = withServiceLoaderEntries(
                mapOf(OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name)
            ) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor("anything")).result
            }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestFixtureExecutor.calls).containsExactly("before", "after")
        }

        @Test
        fun `does not call fixture executor when missing from service loader`() {
            ServiceLoaderTestFixtureExecutor.reset()

            val scenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        id = "fixture-id",
                        beforeFixtures = listOf(StringValue("before")),
                        afterFixtures = listOf(StringValue("after"))
                    )
                )
            )

            val result = withServiceLoaderEntries(emptyMap()) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor("anything")).result
            }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestFixtureExecutor.calls).isEmpty()
        }
    }

    private fun scenario(exampleRow: Row? = null): Scenario {
        return Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = buildHttpPathPattern("/resource"),
                    method = "GET"
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    body = StringPattern()
                ),
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        ).copy(exampleRow = exampleRow)
    }

    private fun scenarioAsTest(scenario: Scenario): ScenarioAsTest {
        val feature = Feature(name = "feature", scenarios = listOf(scenario), protocol = SpecmaticProtocol.HTTP)
        return ScenarioAsTest(
            scenario = scenario,
            feature = feature,
            flagsBased = feature.flagsBased,
            originalScenario = scenario,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
    }

    private fun fixedResponseExecutor(body: String): TestExecutor {
        return object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(200, body)
            }
        }
    }

    private fun <T> withServiceLoaderEntries(entries: Map<Class<*>, String>, block: () -> T): T {
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

class ServiceLoaderTestMatcherExecutor : MatcherExecutor {
    override fun matchesResult(expectedValue: Value, actualValue: Value, resolver: Resolver): Result {
        callCount += 1
        return Result.Failure("matcher executor invoked")
    }

    companion object {
        var callCount: Int = 0

        fun reset() {
            callCount = 0
        }
    }
}

class ServiceLoaderTestFixtureExecutor : OpenAPIFixtureExecutor {
    override fun execute(id: String, fixtures: List<Value>, fixtureDiscriminatorKey: String): Result {
        calls.add(fixtureDiscriminatorKey)
        return Result.Success()
    }

    companion object {
        val calls: MutableList<String> = mutableListOf()

        fun reset() {
            calls.clear()
        }
    }
}
