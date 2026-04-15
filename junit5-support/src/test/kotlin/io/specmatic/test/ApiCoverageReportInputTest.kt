package io.specmatic.test

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.reports.coverage.json.LegacyTestJsonGenerator
import io.specmatic.test.utils.OpenApiCoverageBuilder
import io.specmatic.test.utils.OpenApiCoverageVerifier.Companion.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiCoverageReportInputTest {
    companion object {
        private const val CONFIG_FILE_PATH = "./specmatic.json"
    }

    @Test
    fun `test generates api coverage report when all endpoints are covered`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            configFilePath(CONFIG_FILE_PATH)
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")
            applicationApi(method = "GET", path = "/route2")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 401)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 401, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
        }

        coverage.generate().verify {
            assertThat(operations).hasSize(4)
            assertThat(totalOperations).isEqualTo(4)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 401, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 200, 1, 100, CoverageStatus.COVERED)
        }
    }

    @Test
    fun `test generates api coverage report when some endpoints are partially covered`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")
            applicationApi(method = "GET", path = "/route2")
            applicationApi(method = "POST", path = "/route2")
            applicationApi(method = "GET", path = "/route3")
            applicationApi(method = "POST", path = "/route3")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 401)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 401, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
        }

        coverage.generate().verify {
            assertThat(operations).hasSize(7)
            assertThat(totalOperations).isEqualTo(7)
            assertThat(missedOperations).isEqualTo(3)
            assertThat(notImplementedOperations).isEqualTo(0)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 401, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route2", 0, 0, 100, CoverageStatus.MISSING_IN_SPEC)
            assertRow("GET", "/route3", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
            assertRow("POST", "/route3", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
        }
    }

    @Test
    fun `test generates api coverage report when some endpoints are marked as excluded`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")
            applicationApi(method = "GET", path = "/route2")
            applicationApi(method = "POST", path = "/route2")
            applicationApi(method = "GET", path = "/healthCheck")
            applicationApi(method = "GET", path = "/heartbeat")
            excludeApplicationPath("/healthCheck")
            excludeApplicationPath("/heartbeat")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 401)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 401, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "POST", responseCode = 200, result = TestResult.Success)
        }

        coverage.generate().verify {
            assertThat(operations).hasSize(5)
            assertThat(totalOperations).isEqualTo(5)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 401, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route2", 200, 1, 100, CoverageStatus.COVERED)
        }
    }

    @Test
    fun `test generates empty api coverage report when all endpoints are marked as excluded`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")
            applicationApi(method = "GET", path = "/route2")
            applicationApi(method = "POST", path = "/route2")
            applicationApi(method = "GET", path = "/healthCheck")
            applicationApi(method = "GET", path = "/heartbeat")

            excludeApplicationPath("/route1")
            excludeApplicationPath("/route2")
            excludeApplicationPath("/healthCheck")
            excludeApplicationPath("/heartbeat")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 401)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 401, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "POST", responseCode = 200, result = TestResult.Success)
        }

        coverage.generate().verify {
            assertThat(totalOperations).isEqualTo(5)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
            assertThat(consoleReport.coverageRows).hasSize(5)
            assertThat(consoleReport.totalCoveragePercentage).isEqualTo(0)
            assertThat(consoleReport.coverageRows).allSatisfy {
                assertThat(it.remarks).isEqualTo(CoverageStatus.NOT_TESTED)
                assertThat(it.count).isEqualTo("0")
                assertThat(it.coveragePercentage).isEqualTo(0)
            }
        }
    }

    @Test
    fun `test generates empty api coverage report when no paths are documented in the open api spec and endpoints api is not defined`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage { applicationApisUnavailable() }
        coverage.generate().verify {
            assertThat(totalOperations).isEqualTo(0)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
            assertThat(consoleReport.coverageRows).isEmpty()
        }
    }

    @Test
    fun `test generates coverage report when some endpoints or operations are present in spec, but not implemented`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
            testResult(path = "/route2", method = "POST", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }

        coverage.generate().verify {
            assertThat(operations).hasSize(4)
            assertThat(totalOperations).isEqualTo(4)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(2)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/route2", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        }
    }

    @Test
    fun `test generates api coverage report when partially covered and partially implemented endpoints`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")
            applicationApi(method = "GET", path = "/route2")
            applicationApi(method = "GET", path = "/route3/{route_id}")
            applicationApi(method = "POST", path = "/route3/{route_id}")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "POST", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }

        coverage.generate().verify {
            assertThat(operations).hasSize(6)
            assertThat(totalOperations).isEqualTo(6)
            assertThat(missedOperations).isEqualTo(2)
            assertThat(notImplementedOperations).isEqualTo(1)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 200, 1, 50, CoverageStatus.COVERED)
            assertRow("POST", "/route2", 200, 1, 50, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("GET", "/route3/{route_id}", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
            assertRow("POST", "/route3/{route_id}", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
        }
    }

    @Test
    fun `test generates api coverage json report with partially covered and partially implemented endpoints`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            configFilePath(CONFIG_FILE_PATH)
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")
            applicationApi(method = "GET", path = "/route2")
            applicationApi(method = "GET", path = "/route3/{route_id}")
            applicationApi(method = "POST", path = "/route3/{route_id}")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "POST", path = "/route2", responseCode = 200)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "POST", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }

        val openApiCoverageJsonReport = LegacyTestJsonGenerator(coverage.generate()).generateJsonReport()
        val reportJson = ObjectMapper().writeValueAsString(openApiCoverageJsonReport)
        assertThat(reportJson).contains("\"specmaticConfigPath\":\"./specmatic.json\"")
        assertThat(reportJson).contains("\"path\":\"/route3/{route_id}\",\"method\":\"GET\",\"responseCode\":0,\"coverageStatus\":\"missing in spec\"")
        assertThat(reportJson).contains("\"path\":\"/route2\",\"method\":\"POST\",\"responseCode\":200,\"coverageStatus\":\"not implemented\"")
    }

    @Test
    fun `test generates api coverage report with endpoints present in spec but not tested`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/route1")
            applicationApi(method = "POST", path = "/route1")
            applicationApi(method = "GET", path = "/route2")

            specEndpoint(method = "GET", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 200)
            specEndpoint(method = "POST", path = "/route1", responseCode = 401)
            specEndpoint(method = "GET", path = "/route2", responseCode = 200)
            specEndpoint(method = "GET", path = "/route2", responseCode = 400)

            testResult(path = "/route1", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route1", method = "POST", responseCode = 401, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/route2", method = "GET", responseCode = 404, result = TestResult.Success)
            testResult(path = "/route2", method = "POST", responseCode = 500, result = TestResult.Success)
        }

        coverage.generate().verify {
            assertThat(operations).hasSize(7)
            assertThat(totalOperations).isEqualTo(7)
            assertThat(missedOperations).isEqualTo(2)
            assertThat(notImplementedOperations).isEqualTo(0)
            assertRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED)
            assertRow("POST", "/route1", 401, 1, 100, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 200, 1, 50, CoverageStatus.COVERED)
            assertRow("GET", "/route2", 400, 0, 50, CoverageStatus.NOT_TESTED)
            assertRow("GET", "/route2", 404, 1, 50, CoverageStatus.MISSING_IN_SPEC)
            assertRow("POST", "/route2", 500, 1, 50, CoverageStatus.MISSING_IN_SPEC)
        }
    }
}
