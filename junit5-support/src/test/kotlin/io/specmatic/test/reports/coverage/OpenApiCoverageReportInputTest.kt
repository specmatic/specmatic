package io.specmatic.test.reports.coverage

import io.specmatic.test.API
import io.specmatic.test.reports.TestExecutionResult
import io.specmatic.test.reports.TestReportListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test

class OpenApiCoverageReportInputTest {
    private class TestListener : TestReportListener {
        val actuatorApisReceived = mutableListOf<List<API>>()
        var actuatorEnabled: Boolean? = null
        val endpointApisReceived = mutableListOf<List<Endpoint>>()
        val testResults = mutableListOf<TestExecutionResult>()
        var testsCompleteCount = 0
        var coverageValue: Int? = null
        val pathCoverages = mutableMapOf<String, Int>()

        override fun onActuator(enabled: Boolean) {
            actuatorEnabled = enabled
        }

        override fun onActuatorApis(apis: List<API>) {
            actuatorApisReceived.add(apis)
        }

        override fun onEndpointApis(apis: List<Endpoint>) {
            endpointApisReceived.add(apis)
        }

        override fun onTestResult(result: TestExecutionResult) {
            testResults.add(result)
        }

        override fun onTestsComplete() {
            testsCompleteCount++
        }

        override fun onCoverageCalculated(coverage: Int) {
            coverageValue = coverage
        }

        override fun onPathCoverageCalculated(path: String, pathCoverage: Int) {
            pathCoverages[path] = pathCoverage
        }
    }

    @Test
    fun `should not call coverage-hooks with excluded application-apis`() {
        val listener = TestListener()
        val excludedPath = "/actuator/health"
        val api1 = API("GET", excludedPath)
        val api2 = API("POST", "/api/users")
        val reportInput = OpenApiCoverageReportInput(
            configFilePath = "config.yaml",
            excludedAPIs = mutableListOf(excludedPath),
            coverageHooks = listOf(listener),
        )

        reportInput.addAPIs(listOf(api1, api2))

        assertThat(listener.actuatorApisReceived).hasSize(1)
        val receivedApis = listener.actuatorApisReceived.first()
        assertThat(receivedApis)
            .hasSize(1)
            .extracting("path", "method")
            .containsExactly(tuple("/api/users", "POST"))
    }

    @Test
    fun `should not call coverage-hooks with filtered-out application-apis`() {
        val listener = TestListener()
        val excludedPath = "/actuator/health"
        val api1 = API("GET", excludedPath)
        val api2 = API("POST", "/api/users")
        val reportInput = OpenApiCoverageReportInput(
            configFilePath = "config.yaml",
            coverageHooks = listOf(listener),
            filterExpression = "PATH!='$excludedPath'",
        )

        reportInput.addAPIs(listOf(api1, api2))

        assertThat(listener.actuatorApisReceived).hasSize(1)
        val receivedApis = listener.actuatorApisReceived.first()
        assertThat(receivedApis)
            .hasSize(1)
            .extracting("path", "method")
            .containsExactly(tuple("/api/users", "POST"))
    }
}
