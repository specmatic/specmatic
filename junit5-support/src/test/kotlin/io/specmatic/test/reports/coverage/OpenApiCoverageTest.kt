package io.specmatic.test.reports.coverage

import io.specmatic.reporter.ctrf.model.CtrfTestQualifiers
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.utils.CoverageBuilder.Companion.coverage
import io.specmatic.test.utils.endpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiCoverageTest {
    @Test
    fun `should expose the snapshot to operation to report operation flow`() = coverage {
        applicationApi(method = "POST", path = "/payments")
        inScope(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
        inScope(method = "POST", path = "/orders", requestType = "application/json", responseCode = 200, responseType = "application/json")
        observed(
            method = "POST",
            path = "/orders",
            requestType = "application/json",
            responseCode = 200,
            responseType = "application/json",
            result = TestResult.Success,
            actualResponseCode = 200,
            actualResponseType = "application/json",
        )

        verify {
            assertThat(operations).hasSize(3)
            assertThat(context.specEndpointsInScope).containsExactlyInAnyOrder(
                endpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 200, responseType = "application/json"),
                endpoint(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json"),
            )
        }

        verify {
            assertThat(matching(method = "GET", path = "/orders").single().report.coverageStatus).isEqualTo(CoverageStatus.NOT_TESTED)
            assertThat(matching(method = "POST", path = "/payments").single().report.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
            assertThat(matching(method = "POST", path = "/orders", responseCode = 200).single().report.coverageStatus).isEqualTo(CoverageStatus.COVERED)
        }
    }

    @Test
    fun `should not create synthetic row and report response identifiers mismatch under the expected identifiers`() = coverage {
        inScope(method = "POST", path = "/orders", requestType = "application/json", responseCode = 201, responseType = "application/json")
        repeat(10) {
            observed(
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

        verify {
            val expectedEndpoint = endpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 201, responseType = "application/json")
            assertThat(context.specEndpointsInScope).containsExactlyInAnyOrder(expectedEndpoint)
        }

        verify {
            val operationView = operations.single()
            assertThat(operationView.report.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
            assertThat(operationView.report.eligibleForCoverage).isEqualTo(true)

            assertThat(operationView.operation).satisfies(
                { assertThat(it.path).isEqualTo("/orders") },
                { assertThat(it.method).isEqualTo("POST") },
                { assertThat(it.contentType).isEqualTo("application/json") },
                { assertThat(it.responseCode).isEqualTo(201) },
                { assertThat(it.responseContentType).isEqualTo("application/json") },
            )

            assertThat(operationView.tests).hasSize(10).allSatisfy { test ->
                assertThat(test.actualResponseStatus).isEqualTo(400)
                assertThat(test.actualResponseContentType).isEqualTo("application/json")
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isFalse },
                    { assertThat(it.attempt).isTrue },
                    { assertThat(it.qualifiers).isEmpty() },
                    { assertThat(it.reasons).isEmpty() },
                )
            }
        }
    }

    @Test // TODO: Needs fixing
    fun `should not create duplicate tests count when one expected operation returns another expected operation`() = coverage {
        inScope(method = "POST", path = "/orders", requestType = "application/json", responseCode = 201, responseType = "application/json")
        inScope(method = "POST", path = "/orders", requestType = "application/json", responseCode = 400, responseType = "application/json")

        observed(
            method = "POST",
            path = "/orders",
            requestType = "application/json",
            responseCode = 400,
            responseType = "application/json",
            result = TestResult.Success,
            actualResponseCode = 400,
            actualResponseType = "application/json"
        )

        repeat(10) {
            observed(
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

        verify {
            assertThat(context.specEndpointsInScope).containsExactlyInAnyOrder(
                endpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 201, responseType = "application/json"),
                endpoint(method = "POST", path = "/orders", requestType = "application/json", responseCode = 400, responseType = "application/json"),
            )
        }

        verify {
            val successView = matching("POST", "/orders", 201)
            assertThat(successView).allSatisfy { view ->
                assertThat(view.report.eligibleForCoverage).isEqualTo(true)
                assertThat(view.report.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
                assertThat(view.tests).hasSize(10).allSatisfy { test ->
                    assertThat(test.actualResponseStatus).isEqualTo(400)
                    assertThat(test.result).isEqualTo(TestResult.Failed)
                }
            }
        }

        verify {
            val badRequestView = matching("POST", "/orders", 400)
            assertThat(badRequestView).allSatisfy { view ->
                // assertThat(view.tests).hasSize(1) TODO: Should it not have only 1
                assertThat(view.tests).hasSize(11).allSatisfy { test ->
                    assertThat(test.actualResponseStatus).isEqualTo(400)
                    // TODO: Fix with hasSize(1), add extraFields assertions
                    // assertThat(test.result).isEqualTo(TestResult.Success)
                }

                assertThat(view.report.coverageStatus).isEqualTo(CoverageStatus.COVERED)
                assertThat(view.report.eligibleForCoverage).isEqualTo(true)
            }
        }
    }

    @Test
    fun `should add qualifier tag WIP for operations marked as WIP`() = coverage {
        inScope(method = "POST", path = "/orders/{id}", requestType = "application/json", responseCode = 201, responseType = "application/json")
        observed(
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

        verify {
            val expectedEndpoint = endpoint(method = "POST", path = "/orders/{id}", requestType = "application/json", responseCode = 201, responseType = "application/json")
            assertThat(context.specEndpointsInScope).containsExactlyInAnyOrder(expectedEndpoint)
            assertThat(operations).hasSize(1)
        }

        verify {
            val wipView = matching("POST", "/orders{id}", 201)
            assertThat(wipView).allSatisfy { view ->
                assertThat(view.report.coverageStatus).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
                assertThat(view.report.eligibleForCoverage).isEqualTo(true)
                assertThat(view.tests).hasSize(1).allSatisfy { test ->
                    assertThat(test.extraFields()).satisfies(
                        { assertThat(it.match).isFalse },
                        { assertThat(it.attempt).isTrue },
                        { assertThat(it.qualifiers).contains(CtrfTestQualifiers.WIP) },
                        { assertThat(it.reasons).isEmpty() },
                    )
                }
            }
        }
    }

    @Test
    fun `should include zero response missing in spec operation from actuator when endpoint absent from spec`() = coverage {
        inScope(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
        applicationApi(method = "GET", path = "/orders/{id}")

        verify {
            val didNotRunView = matching(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json").single()
            assertThat(didNotRunView.tests).isEmpty()
            assertThat(didNotRunView.report.coverageStatus).isEqualTo(CoverageStatus.NOT_TESTED)
        }

        verify {
            val missingInSpecView = matching(method = "GET", path = "/orders/{id}", responseCode = 0).single()
            assertThat(missingInSpecView.tests).isEmpty()
            assertThat(missingInSpecView.operation.contentType).isNull()
            assertThat(missingInSpecView.report.eligibleForCoverage).isFalse
            assertThat(missingInSpecView.operation.responseCode).isEqualTo(0)
            assertThat(missingInSpecView.operation.responseContentType).isNull()
            assertThat(missingInSpecView.report.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        }
    }

    @Test
    fun `should create missing in spec row when -ve generated tests operation is absent from spec`() = coverage {
        inScope(method = "POST", path = "/order", requestType = "application/json", responseCode = 201, responseType = "application/json")

        repeat(10) {
            observed(
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
            observed(
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

        verify {
            assertThat(report).hasSize(2)
            assertThat(operations).hasSize(2)
        }

        verify {
            val okResponseView = matching(method = "POST", path = "/order", responseCode = 201).single()
            assertThat(okResponseView.report.eligibleForCoverage).isTrue
            assertThat(okResponseView.report.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(okResponseView.tests).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                    { assertThat(it.attempt).isTrue },
                )
            }
        }

        verify {
            val badRequestView = matching(method = "POST", path = "/order", responseCode = 400).single()
            assertThat(badRequestView.report.eligibleForCoverage).isFalse
            assertThat(badRequestView.report.coverageStatus).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
            assertThat(badRequestView.tests).hasSize(20).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                    { assertThat(it.attempt).isTrue },
                )
            }
        }
    }

    @Test
    fun `should no create missing in spec row when -ve generated tests operation is present from spec`() = coverage {
        inScope(method = "POST", path = "/order", requestType = "application/json", responseCode = 201, responseType = "application/json")
        inScope(method = "POST", path = "/order", requestType = "application/json", responseCode = 400, responseType = "application/json")

        repeat(10) {
            observed(
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

        repeat(10) {
            observed(
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
            observed(
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

        verify {
            assertThat(report).hasSize(2)
            assertThat(operations).hasSize(2)
        }

        verify {
            val okResponseView = matching(method = "POST", path = "/order", responseCode = 201).single()
            assertThat(okResponseView.report.eligibleForCoverage).isTrue
            assertThat(okResponseView.report.coverageStatus).isEqualTo(CoverageStatus.COVERED)
            assertThat(okResponseView.tests).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                    { assertThat(it.attempt).isTrue },
                )
            }
        }

        verify {
            val badRequestView = matching(method = "POST", path = "/order", responseCode = 400).single()
            assertThat(badRequestView.report.eligibleForCoverage).isTrue
            assertThat(badRequestView.report.coverageStatus).isEqualTo(CoverageStatus.COVERED)

            assertThat(badRequestView.tests.filter { it.actualResponseContentType == "application/json" }).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isTrue },
                    { assertThat(it.attempt).isTrue },
                )
            }

            assertThat(badRequestView.tests.filterNot { it.actualResponseContentType == "application/json" }).hasSize(10).allSatisfy { test ->
                assertThat(test.extraFields()).satisfies(
                    { assertThat(it.match).isFalse },
                    { assertThat(it.attempt).isTrue },
                )
            }
        }
    }
}
