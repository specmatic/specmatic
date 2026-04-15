package io.specmatic.test

import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.utils.OpenApiCoverageBuilder
import io.specmatic.test.utils.OpenApiCoverageVerifier.Companion.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiCoverageReportTest {
    @Test
    fun `GET 200 in spec not implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "POST", path = "/orders")
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/orders", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(1)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `POST 201 and 400 in spec not implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "POST", path = "/orders")
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 201)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 400)
            testResult(path = "/order/{id}", method = "POST", responseCode = 201, result = TestResult.Failed, actualResponseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 400, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(3)
            assertRow("POST", "/order/{id}", 201, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/order/{id}", 400, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/orders", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
            assertThat(totalOperations).isEqualTo(3)
            assertThat(missedOperations).isEqualTo(1)
            assertThat(notImplementedOperations).isEqualTo(2)
        }
    }

    @Test
    fun `GET 200 in spec not implemented without actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertThat(totalOperations).isEqualTo(1)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `POST 201 and 400 in spec not implemented without actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 201)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 400)
            testResult(path = "/order/{id}", method = "POST", responseCode = 201, result = TestResult.Failed, actualResponseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 400, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("POST", "/order/{id}", 201, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/order/{id}", 400, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(2)
        }
    }

    @Test
    fun `GET 200 and 404 in spec not implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/orders")
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(3)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("GET", "/order/{id}", 404, 0, 0, CoverageStatus.NOT_TESTED)
            assertRow("GET", "/orders", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
            assertThat(totalOperations).isEqualTo(3)
            assertThat(missedOperations).isEqualTo(1)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `POST 201, 400 and 404 in spec not implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "POST", path = "/orders")
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 201)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 400)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 201, result = TestResult.Failed, actualResponseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 400, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(4)
            assertRow("POST", "/orders", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC)
            assertRow("POST", "/order/{id}", 404, 0, 0, CoverageStatus.NOT_TESTED)
            assertRow("POST", "/order/{id}", 201, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/order/{id}", 400, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertThat(totalOperations).isEqualTo(4)
            assertThat(missedOperations).isEqualTo(1)
            assertThat(notImplementedOperations).isEqualTo(2)
        }
    }

    @Test
    fun `GET 200 and 404 in spec not implemented without actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("GET", "/order/{id}", 404, 0, 0, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `POST 201, 400 and 404 in spec not implemented without actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 201)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 400)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 201, result = TestResult.Failed, actualResponseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 400, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(3)
            assertRow("POST", "/order/{id}", 201, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/order/{id}", 400, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("POST", "/order/{id}", 404, 0, 0, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(3)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(2)
        }
    }

    @Test
    fun `GET 200 and 404 in spec implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/order/{id}")
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Success, actualResponseCode = 200)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED)
            assertRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
        }
    }

    @Test
    fun `POST 201, 400 and 404 in spec implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "POST", path = "/order/{id}")
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 201)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 400)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 201, result = TestResult.Success, actualResponseCode = 201)
            testResult(path = "/order/{id}", method = "POST", responseCode = 400, result = TestResult.Success, actualResponseCode = 400)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(3)
            assertRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.COVERED)
            assertRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.COVERED)
            assertRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(3)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
        }
    }

    @Test
    fun `GET 200 and 400 in spec implemented without actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 400)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Success, actualResponseCode = 200)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED)
            assertRow("GET", "/order/{id}", 400, 0, 50, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
        }
    }

    @Test
    fun `POST 201, 400 and 404 in spec implemented without actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 201)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 400)
            specEndpoint(method = "POST", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "POST", responseCode = 201, result = TestResult.Success, actualResponseCode = 201)
            testResult(path = "/order/{id}", method = "POST", responseCode = 400, result = TestResult.Success, actualResponseCode = 400)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(3)
            assertRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.COVERED)
            assertRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.COVERED)
            assertRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(3)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
        }
    }

    @Test
    fun `GET 200 in spec implemented with actuator bad request`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/order/{id}")
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertThat(totalOperations).isEqualTo(1)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `GET 200 in spec implemented without actuator bad request`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertThat(totalOperations).isEqualTo(1)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `GET 200 and 404 in spec implemented with actuator bad request`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/order/{id}")
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("GET", "/order/{id}", 404, 0, 0, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `GET 200 and 404 in spec implemented without actuator bad request`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 200)
            specEndpoint(method = "GET", path = "/order/{id}", responseCode = 404)
            testResult(path = "/order/{id}", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/order/{id}", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("GET", "/order/{id}", 404, 0, 0, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `No Param GET 200 in spec not implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/orders")
            specEndpoint(method = "GET", path = "/orders", responseCode = 200)
            testResult(path = "/orders", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(1)
            assertRow("GET", "/orders", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertThat(totalOperations).isEqualTo(1)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `No Param GET 200 and 404 in spec not implemented without actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApisUnavailable()
            specEndpoint(method = "GET", path = "/orders", responseCode = 200)
            specEndpoint(method = "GET", path = "/orders", responseCode = 404)
            testResult(path = "/orders", method = "GET", responseCode = 200, result = TestResult.Failed, actualResponseCode = 404)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/orders", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
            assertRow("GET", "/orders", 404, 0, 0, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(1)
        }
    }

    @Test
    fun `No Param GET 200 and 404 in spec implemented with actuator`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/orders")
            specEndpoint(method = "GET", path = "/orders", responseCode = 200)
            specEndpoint(method = "GET", path = "/orders", responseCode = 404)
            testResult(path = "/orders", method = "GET", responseCode = 200, result = TestResult.Success, actualResponseCode = 200)
        }.generate()

        report.verify {
            assertThat(consoleReport.coverageRows).hasSize(2)
            assertRow("GET", "/orders", 200, 1, 50, CoverageStatus.COVERED)
            assertRow("GET", "/orders", 404, 0, 50, CoverageStatus.NOT_TESTED)
            assertThat(totalOperations).isEqualTo(2)
            assertThat(missedOperations).isEqualTo(0)
            assertThat(notImplementedOperations).isEqualTo(0)
        }
    }
}
