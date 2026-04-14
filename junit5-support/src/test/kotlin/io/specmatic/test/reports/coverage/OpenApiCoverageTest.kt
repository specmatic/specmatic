package io.specmatic.test.reports.coverage

import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfTestQualifiers
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.utils.OpenApiCoverageBuilder
import io.specmatic.test.utils.OpenApiCoverageVerifier.Companion.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiCoverageTest {
    @Test
    fun `should expose the snapshot to operation to report operation flow`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "POST", path = "/payments")
            specEndpoint(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
            specEndpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 200, responseType = "application/json")
            testResult(
                method = "POST",
                path = "/orders",
                requestType = "application/json",
                responseCode = 200,
                responseType = "application/json",
                result = TestResult.Success,
                actualResponseCode = 200,
                actualResponseType = "application/json",
            )
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            assertThat(single(method = "GET", path = "/orders").operation.coverageStatus).isEqualTo(CoverageStatus.NOT_TESTED)
            assertThat(single(method = "POST", path = "/payments").operation.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
            assertThat(single(method = "POST", path = "/orders", responseCode = 200).operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
        }
    }

    @Test
    fun `should not create synthetic row and report response identifiers mismatch under the expected identifiers`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 201, responseType = "application/json")
            repeat(10) {
                testResult(
                    method = "POST",
                    path = "/orders",
                    requestType = "application/json",
                    responseCode = 201,
                    responseType = "application/json",
                    result = TestResult.Failed,
                    actualResponseCode = 400,
                    actualResponseType = "application/json",
                )
            }
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            val ordersView = single("POST", "/orders", 201, requestType = "application/json", responseType = "application/json")
            assertThat(ordersView.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(ordersView.operation.eligibleForCoverage).isEqualTo(true)
            assertThat(ordersView.operation.metrics?.attempts).isEqualTo(10)
            assertThat(ordersView.operation.metrics?.matches).isEqualTo(0)
            assertThat(ordersView.tests).hasSize(10).allSatisfy { test ->
                assertThat(test.actualResponseStatus).isEqualTo(400)
                assertThat(test.actualResponseContentType).isEqualTo("application/json")
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isFalse },
                    { assertThat(it.reasons).isEmpty() },
                )
            }
        }
    }

    @Test
    fun `should not create duplicate tests count when one expected operation returns another expected operation`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 201, responseType = "application/json")
            repeat(10) {
                testResult(
                    method = "POST",
                    path = "/orders",
                    requestType = "application/json",
                    responseCode = 201,
                    responseType = "application/json",
                    result = TestResult.Failed,
                    actualResponseCode = 400,
                    actualResponseType = "application/json",
                )
            }

            specEndpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 400, responseType = "application/json")
            testResult(
                method = "POST",
                path = "/orders",
                requestType = "application/json",
                responseCode = 400,
                responseType = "application/json",
                result = TestResult.Success,
                actualResponseCode = 400,
                actualResponseType = "application/json"
            )
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            val successView = single("POST", "/orders", 201)
            assertThat(successView.operation.metrics?.matches).isEqualTo(0)
            assertThat(successView.operation.metrics?.attempts).isEqualTo(10)
            assertThat(successView.operation.eligibleForCoverage).isEqualTo(true)
            assertThat(successView.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(successView.tests).hasSize(10).allSatisfy { test ->
                assertThat(test.actualResponseStatus).isEqualTo(400)
                assertThat(test.result).isEqualTo(TestResult.Failed)
            }
        }

        report.verify {
            val badRequestView = single("POST", "/orders", 400)
            assertThat(badRequestView.operation.metrics?.matches).isEqualTo(1)
            assertThat(badRequestView.operation.metrics?.attempts).isEqualTo(1)
            assertThat(badRequestView.operation.eligibleForCoverage).isEqualTo(true)
            assertThat(badRequestView.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(badRequestView.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.actualResponseStatus).isEqualTo(400)
                assertThat(test.result).isEqualTo(TestResult.Success)
            }
        }
    }

    @Test
    fun `should add qualifier tag WIP for operations marked as WIP`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(method = "POST", path = "/orders/{id}", requestType = "application/json", responseCode = 201, responseType = "application/json")
            testResult(
                isWip = true,
                method = "POST",
                path = "/orders/{id}",
                requestType = "application/json",
                responseCode = 201,
                responseType = "application/json",
                result = TestResult.Failed,
                actualResponseCode = 200,
                actualResponseType = "application/json"
            )
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            val wipView = single("POST", "/orders/{id}", 201)
            assertThat(wipView.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(wipView.operation.qualifiers).contains(CtrfTestQualifiers.WIP)
            assertThat(wipView.operation.eligibleForCoverage).isEqualTo(true)
            assertThat(wipView.operation.metrics?.attempts).isEqualTo(1)
            assertThat(wipView.operation.metrics?.matches).isEqualTo(0)
            assertThat(wipView.tests).hasSize(1).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isFalse },
                    { assertThat(it.reasons).isEmpty() },
                )
            }
        }
    }

    @Test
    fun `should include zero response missing in spec operation from actuator when endpoint absent from spec`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/orders/{id}")
            specEndpoint(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            val didNotRunView = single(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
            assertThat(didNotRunView.tests).isEmpty()
            assertThat(didNotRunView.operation.coverageStatus).isEqualTo(CoverageStatus.NOT_TESTED)
        }

        report.verify {
            val missingInSpecView = single(method = "GET", path = "/orders/{id}", responseCode = 0)
            assertThat(missingInSpecView.tests).isEmpty()
            assertThat(missingInSpecView.apiOperation.contentType).isNull()
            assertThat(missingInSpecView.operation.eligibleForCoverage).isFalse
            assertThat(missingInSpecView.apiOperation.responseCode).isEqualTo(0)
            assertThat(missingInSpecView.operation.metrics?.matches).isEqualTo(0)
            assertThat(missingInSpecView.operation.metrics?.attempts).isEqualTo(0)
            assertThat(missingInSpecView.apiOperation.responseContentType).isNull()
            assertThat(missingInSpecView.operation.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        }
    }

    @Test
    fun `should create missing in spec row when -ve generated tests operation is absent from spec`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(method = "POST", path = "/order", requestType = "application/json", responseCode = 201, responseType = "application/json")
            repeat(10) {
                testResult(
                    method = "POST",
                    path = "/order",
                    requestType = "application/json",
                    responseCode = 201,
                    responseType = "application/json",
                    result = TestResult.Success,
                    actualResponseCode = 201,
                    actualResponseType = "application/json",
                )
            }

            repeat(20) {
                testResult(
                    method = "POST",
                    path = "/order",
                    requestType = "application/json",
                    responseCode = 400,
                    responseType = "application/json",
                    result = TestResult.Failed,
                    actualResponseCode = 400,
                    actualResponseType = "application/json",
                )
            }
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            val okResponseView = single(method = "POST", path = "/order", responseCode = 201)
            assertThat(okResponseView.operation.eligibleForCoverage).isTrue
            assertThat(okResponseView.operation.metrics?.matches).isEqualTo(10)
            assertThat(okResponseView.operation.metrics?.attempts).isEqualTo(10)
            assertThat(okResponseView.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(okResponseView.tests).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                )
            }
        }

        report.verify {
            val badRequestView = single(method = "POST", path = "/order", responseCode = 400)
            assertThat(badRequestView.operation.eligibleForCoverage).isFalse
            assertThat(badRequestView.operation.metrics?.matches).isEqualTo(20)
            assertThat(badRequestView.operation.metrics?.attempts).isEqualTo(20)
            assertThat(badRequestView.operation.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
            assertThat(badRequestView.tests).hasSize(20).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                )
            }
        }
    }

    @Test
    fun `should no create missing in spec row when -ve generated tests operation is present from spec`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(method = "POST", path = "/order", requestType = "application/json", responseCode = 201, responseType = "application/json")
            repeat(10) {
                testResult(
                    method = "POST",
                    path = "/order",
                    requestType = "application/json",
                    responseCode = 201,
                    responseType = "application/json",
                    result = TestResult.Success,
                    actualResponseCode = 201,
                    actualResponseType = "application/json",
                )
            }

            specEndpoint(method = "POST", path = "/order", requestType = "application/json", responseCode = 400, responseType = "application/json")
            repeat(10) {
                testResult(
                    method = "POST",
                    path = "/order",
                    requestType = "application/json",
                    responseCode = 400,
                    responseType = "application/json",
                    result = TestResult.Success,
                    actualResponseCode = 400,
                    actualResponseType = "application/json",
                )
            }
            repeat(10) {
                testResult(
                    method = "POST",
                    path = "/order",
                    requestType = "application/json",
                    responseCode = 400,
                    responseType = "application/json",
                    result = TestResult.Failed,
                    actualResponseCode = 400,
                    actualResponseType = "text/plain",
                )
            }
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            val okResponseView = single(method = "POST", path = "/order", responseCode = 201)
            assertThat(okResponseView.operation.eligibleForCoverage).isTrue
            assertThat(okResponseView.operation.metrics?.matches).isEqualTo(10)
            assertThat(okResponseView.operation.metrics?.attempts).isEqualTo(10)
            assertThat(okResponseView.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(okResponseView.tests).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                )
            }
        }

        report.verify {
            val badRequestView = single(method = "POST", path = "/order", responseCode = 400)
            assertThat(badRequestView.operation.eligibleForCoverage).isTrue
            assertThat(badRequestView.operation.metrics?.matches).isEqualTo(10)
            assertThat(badRequestView.operation.metrics?.attempts).isEqualTo(20)
            assertThat(badRequestView.operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)

            assertThat(badRequestView.tests.filter { it.actualResponseContentType == "application/json" }).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                )
            }

            assertThat(badRequestView.tests.filterNot { it.actualResponseContentType == "application/json" }).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isFalse },
                )
            }
        }
    }

    @Test
    fun `should build coverage from in-scope endpoints and include actuator-only operations`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "POST", path = "/payments")
            specEndpoint(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
            outOfScopeSpecEndpoint(method = "GET", path = "/internal/orders", responseCode = 200, responseType = "application/json")
            testResult(
                method = "GET",
                path = "/orders",
                responseCode = 200,
                responseType = "application/json",
                result = TestResult.Success,
                actualResponseCode = 200,
                actualResponseType = "application/json",
            )
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            assertThat(operations.map { it.apiOperation.path }).containsExactlyInAnyOrder("/orders", "/payments")
            assertThat(single(method = "GET", path = "/orders", responseCode = 200).operation.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(single(method = "POST", path = "/payments", responseCode = 0).operation.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        }
    }

    @Test
    fun `should not add missing in spec operations when endpoint api is unavailable`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
            applicationApisUnavailable()
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            assertThat(operations).hasSize(1)
            assertThat(single(method = "GET", path = "/orders", responseCode = 200).apiOperation.path).isEqualTo("/orders")
        }
    }

    @Test
    fun `should not include excluded actuator operations in coverage report`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
            applicationApi(method = "POST", path = "/payments")
            excludeApplicationPath("/payments")
        }

        val report: List<OpenApiCoverageReportOperation> = coverage.generateReportOperations()
        report.verify {
            assertThat(operations).hasSize(1)
            assertThat(single(method = "GET", path = "/orders", responseCode = 200).apiOperation.path).isEqualTo("/orders")
        }
    }
}
