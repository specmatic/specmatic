package io.specmatic.core

import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.function.Consumer

internal class ResultKtTest {
    private val scenario = object : ScenarioDetailsForResult {
        override val status: Int = 200
        override val ignoreFailure: Boolean = false
        override val name: String = "scenario"
        override val method: String = "GET"
        override val path: String = "/route"
        override fun testDescription(): String = "scenario description"
    }

    @Test
    fun `add response body to result variables`() {
        val result = Success().withBindings(mapOf("data" to "response-body"), HttpResponse.ok("10")) as Success
        assertThat(result.variables).isEqualTo(mapOf("data" to "10"))
    }

    @Test
    fun `result report for failure with multiple causes`() {
        val result = Failure(
            causes = listOf(
                Result.FailureCause("", cause = Failure("Failure 1", breadCrumb = "id")),
                Result.FailureCause("", cause = Failure("Failure 2", breadCrumb = "height"))
            ), breadCrumb = "person"
        )

        assertThat(result.reportString()).satisfies(Consumer {
            assertThat(it).contains("person.id")
            assertThat(it).contains("person.height")
            assertThat(it).contains("Failure 1")
            assertThat(it).contains("Failure 2")

            println(result.reportString())
        })
    }

    @Test
    fun `failure copy operation should preserve metadata`() {
        val baseFailure = Failure(message = "error").updateScenario(scenario).updatePath("contract-path-failure")
        val transformedFailure = baseFailure.copy(breadCrumb = "REQUEST")
        assertMetadata(transformedFailure, scenario, "contract-path-failure")
    }

    @Test
    fun `failure nesting operations should preserve metadata`() {
        val baseFailure = Failure(message = "error").updateScenario(scenario).updatePath("contract-path-failure")
        assertMetadata(baseFailure.reason("nested"), scenario, "contract-path-failure")
        assertMetadata(baseFailure.breadCrumb("REQUEST"), scenario, "contract-path-failure")
    }

    @Test
    fun `success copy operation should preserve metadata`() {
        val baseSuccess = Success().updateScenario(scenario).updatePath("contract-path-success") as Success
        val transformedSuccess = baseSuccess.copy(variables = mapOf("x" to "1"))
        assertMetadata(transformedSuccess, scenario, "contract-path-success")
    }

    @Test
    fun `failure aggregation operation should not use metadata from one of the failures`() {
        val first = Failure("error-1").updateScenario(scenario).updatePath("contract-path-1")
        val second = Failure("error-2").updateScenario(scenario).updatePath("contract-path-2")
        val result = Result.fromFailures(listOf(first, second))
        assertThat(result.scenario).isNull()
        assertThat(result.contractPath).isNull()
    }

    private fun assertMetadata(result: Result, scenario: ScenarioDetailsForResult?, contractPath: String?) {
        assertThat(result.scenario).isEqualTo(scenario)
        assertThat(result.contractPath).isEqualTo(contractPath)
    }
}