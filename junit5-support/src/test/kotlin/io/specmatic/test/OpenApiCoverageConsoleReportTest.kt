package io.specmatic.test

import io.specmatic.reporter.model.TestResult
import io.specmatic.test.utils.OpenApiCoverageBuilder.Companion.buildCoverage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiCoverageConsoleReportTest {
    @Test
    fun `test calculates total percentage based on number of exercised endpoints`() {
        val report = buildCoverage {
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 400)
            specEndpoint(method = "POST", path = "/route2", responseCode = 500)
            specEndpoint(method = "GET", path = "/route3", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
        }.generate()

        assertThat(report.totalCoveragePercentage).isEqualTo(29)
    }

    @Test
    fun `should calculate overall coverage percentage based on exercised endpoints with WIP`() {
        val report = buildCoverage {
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route1", responseCode = 400)
            specEndpoint(method = "GET", path = "/route1", responseCode = 503)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 400)
            specEndpoint(method = "POST", path = "/route2", responseCode = 201)
            specEndpoint(method = "POST", path = "/route2", responseCode = 400)
            specEndpoint(method = "POST", path = "/route2", responseCode = 503)
            specEndpoint(method = "POST", path = "/route3", responseCode = 210)
            specEndpoint(method = "POST", path = "/route3", responseCode = 400)
            specEndpoint(method = "POST", path = "/route3", responseCode = 503)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "GET", responseCode = 400, result = TestResult.Success)
            testResult(path = "/route1", method = "GET", responseCode = 503, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success, isWip = true)
            testResult(path = "/route2", method = "GET", responseCode = 400, result = TestResult.Success, isWip = true)
            testResult(path = "/route2", method = "POST", responseCode = 201, result = TestResult.Success)
            testResult(path = "/route2", method = "POST", responseCode = 400, result = TestResult.Success)
            testResult(path = "/route3", method = "POST", responseCode = 210, result = TestResult.Success)
            testResult(path = "/route3", method = "POST", responseCode = 400, result = TestResult.Success)
        }.generate()

        assertThat(report.totalCoveragePercentage).isEqualTo(82)
    }

    @Test
    fun `test does not include endpoints missing in spec when calculating coverage percentage`() {
        val report = buildCoverage {
            applicationApi(method = "GET", path = "/route3")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "POST", responseCode = 200, result = TestResult.Success)
        }.generate()

        assertThat(report.totalCoveragePercentage).isEqualTo(100)
    }

    @Test
    fun `test does not count not implemented endpoints as covered when calculating total percentage`() {
        val report = buildCoverage {
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
        }.generate()

        assertThat(report.totalCoveragePercentage).isEqualTo(50)
    }

    @Test
    fun `test calculates zero total percentage when all exercised endpoints are not implemented`() {
        val report = buildCoverage {
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        assertThat(report.totalCoveragePercentage).isEqualTo(0)
    }
}
