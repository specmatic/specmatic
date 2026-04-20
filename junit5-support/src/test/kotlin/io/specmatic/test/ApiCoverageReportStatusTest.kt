package io.specmatic.test

import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.utils.OpenApiCoverageBuilder
import io.specmatic.test.utils.OpenApiCoverageVerifier.Companion.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiCoverageReportStatusTest {
    companion object {
        private const val CONFIG_FILE_PATH = "./specmatic.json"
    }

    @Test
    fun `identifies endpoint as 'covered' when contract test passes and route+method is present in actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            configFilePath(CONFIG_FILE_PATH)
            applicationApi(method = "GET", path = "/route1")
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
        }
    }

    @Test
    fun `identifies endpoint as 'covered' when contract test passes and route+method is not present in actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            configFilePath(CONFIG_FILE_PATH)
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
        }
    }

    @Test
    fun `identifies endpoint as 'not implemented' when contract test fails and route+method is present in actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            configFilePath(CONFIG_FILE_PATH)
            applicationApi(method = "GET", path = "/route1")
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 400)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/route1", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        }
    }

    @Test
    fun `identifies endpoint as 'not implemented' when contract test fails and actuator is not available`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            configFilePath(CONFIG_FILE_PATH)
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 400)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/route1", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        }
    }

    @Test
    fun `identifies endpoint as 'not implemented' when contract test fails, and route+method is not present in actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            configFilePath(CONFIG_FILE_PATH)
            applicationApi(method = "GET", path = "/route1")
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        }
    }

    @Test
    fun `identifies endpoint as not implemented when endpoints api is enabled but discovered endpoint list is empty`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            setEndpointsAPIFlag(true)
            configFilePath(CONFIG_FILE_PATH)
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/route1", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        }
    }

    @Test
    fun `identifies endpoint as not implemented when endpoints api is enabled but discovered endpoint list is empty and connection is refused`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            setEndpointsAPIFlag(true)
            configFilePath(CONFIG_FILE_PATH)
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 0)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/route1", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        }
    }

    @Test
    fun `identifies endpoint as 'missing in spec' when route+method is present in actuator but not present in the spec`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            configFilePath(CONFIG_FILE_PATH)
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "GET", path = "/route2")
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
        }
    }

    @Test
    fun `identifies endpoint as 'Not Tested' when contract test is not generated for an endpoint present in the spec`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            configFilePath(CONFIG_FILE_PATH)
            applicationApi(method = "GET", path = "/route1")
            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route1", responseCode = 400)
            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/route1", 200, 1, 50, CoverageStatus.COVERED)
            assertRow("GET", "/route1", 400, 0, 50, CoverageStatus.NOT_TESTED)
        }
    }
}
