package io.specmatic.test

import io.specmatic.core.BadRequestOrDefault
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.StringPattern
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScenarioTestGenerationFailureTest {
    @Test
    fun `runTest overloads and testResultRecord should use updated 400 scenario`() {
        val contractTest = ScenarioTestGenerationFailure(
            scenario = negativeScenarioWith400Responses(),
            failure = Result.Failure("generation failed"),
            message = "generation failed",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )

        val withExecutor = contractTest.runTest(noOpExecutor())
        val withBaseUrl = contractTest.runTest("http://localhost:9999", 1000)
        val record = contractTest.testResultRecord(withExecutor)

        val scenarioFromExecutor = withExecutor.result.scenario as Scenario
        val scenarioFromBaseUrl = withBaseUrl.result.scenario as Scenario
        val scenarioFromRecord = record.scenarioResult?.scenario as Scenario

        assertThat(scenarioFromExecutor.statusInDescription).isEqualTo("400")
        assertThat(scenarioFromBaseUrl.statusInDescription).isEqualTo("400")
        assertThat(scenarioFromRecord.statusInDescription).isEqualTo("400")
        assertThat(scenarioFromRecord.httpResponsePattern.headersPattern.contentType).isEqualTo("application/xml")
    }

    private fun negativeScenarioWith400Responses(): Scenario {
        return scenario(status = 200, contentType = "application/json").copy(
            isNegative = true,
            badRequestOrDefault = BadRequestOrDefault(
                badRequestResponses = mapOf(
                    400 to listOf(
                        scenario(status = 400, contentType = "application/xml"),
                        scenario(status = 400, contentType = "application/json")
                    )
                )
            )
        )
    }

    private fun scenario(status: Int, contentType: String): Scenario {
        return Scenario(
            name = "scenario-$status",
            specType = SpecType.OPENAPI,
            protocol = SpecmaticProtocol.HTTP,
            httpRequestPattern = HttpRequestPattern(),
            httpResponsePattern = HttpResponsePattern(status = status, body = StringPattern(), headersPattern = HttpHeadersPattern(contentType = contentType)),
        )
    }

    private fun noOpExecutor(): TestExecutor {
        return object : TestExecutor {
            override fun execute(request: HttpRequest) = throw UnsupportedOperationException("not needed")
        }
    }
}
