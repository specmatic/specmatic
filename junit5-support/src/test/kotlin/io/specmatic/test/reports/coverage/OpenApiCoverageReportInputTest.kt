package io.specmatic.test.reports.coverage

import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.ContractTest
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestSkipReason
import io.specmatic.test.reports.OpenApiCoverageReportProcessor
import io.specmatic.test.reports.TestExecutionResult
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.utils.OpenApiCoverageBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenApiCoverageReportInputTest {
    @Test
    fun `should generate report with only current records when no previous results are provided`() {
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            result = TestResult.Success,
             specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()
        assertThat(report.coverageRows).anyMatch { row -> row.path == "/current" && row.method == "GET" && row.responseStatus == "200" }
        assertThat(report.coverageRows).noneMatch { row -> row.path == "/previous" }
    }

    @Test
    fun `should include previous test results in the coverage report generation`() {
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            result = TestResult.Success,
             specType = SpecType.OPENAPI
        )

        val previousRecord = TestResultRecord(
            path = "/previous",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            specEndpoint(method = "POST", path = "/previous", responseCode = 201)
            specEndpoint(method = "GET", path = "/current", responseCode = 200)
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
            previousTestResult(previousRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()
        assertThat(report.coverageRows).anyMatch { row -> row.path == "/current" && row.method == "GET" && row.responseStatus == "200" }
        assertThat(report.coverageRows).anyMatch { row -> row.path == "/previous" && row.method == "POST" && row.responseStatus == "201" }
    }

    @Test
    fun `should associate missing in spec endpoint with closest matching spec based on path`() {
        val petsEndpoint = Endpoint(
            path = "/pets/{petId}",
            method = "GET",
            responseStatus = 200,
            specification = "pets.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
        val ownersEndpoint = Endpoint(
            path = "/owners/{ownerId}",
            method = "GET",
            responseStatus = 200,
            specification = "owners.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            applicationApi(method = "GET", path = "/pets/search")
            specEndpoint(ownersEndpoint)
            specEndpoint(petsEndpoint)
            setEndpointsAPIFlag(true)
        }

        val missingInSpecOperation = input.generate().coverageOperations.single { it.coverageStatus == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecOperation.operation.path).isEqualTo("/pets/search")
        assertThat(missingInSpecOperation.specConfig.specification).isEqualTo("pets.yaml")
    }

    @Test
    fun `should prefer the spec with the deeper shared path prefix`() {
        val genericPetsEndpoint = Endpoint(
            path = "/pets/{petId}",
            method = "GET",
            responseStatus = 200,
            specification = "pets.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
        val searchEndpoint = Endpoint(
            path = "/pets/search/{query}",
            method = "GET",
            responseStatus = 200,
            specification = "pets-search.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            applicationApi(method = "GET", path = "/pets/search/advanced")
            specEndpoint(genericPetsEndpoint)
            specEndpoint(searchEndpoint)
            setEndpointsAPIFlag(true)
        }

        val missingInSpecOperation = input.generate().coverageOperations.single { it.coverageStatus == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecOperation.specConfig.specification).isEqualTo("pets-search.yaml")
    }

    @Test
    fun `should prefer matching http method before path depth`() {
        val deeperGetEndpoint = Endpoint(
            path = "/pets/search/{query}",
            method = "GET",
            responseStatus = 200,
            specification = "pets-get.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
        val shallowerPostEndpoint = Endpoint(
            path = "/pets/{petId}",
            method = "POST",
            responseStatus = 202,
            specification = "pets-post.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            applicationApi(method = "POST", path = "/pets/search/advanced")
            specEndpoint(deeperGetEndpoint)
            specEndpoint(shallowerPostEndpoint)
            setEndpointsAPIFlag(true)
        }

        val missingInSpecOperation = input.generate().coverageOperations.single { it.coverageStatus == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecOperation.specConfig.specification).isEqualTo("pets-post.yaml")
    }

    @Test
    fun `should not generate missing in spec operation when no endpoints carry specification metadata`() {
        val petsEndpoint = Endpoint(
            path = "/pets/{petId}",
            method = "GET",
            responseStatus = 200,
            specification = null,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
        val ownersEndpoint = Endpoint(
            path = "/owners/{ownerId}",
            method = "GET",
            responseStatus = 200,
            specification = null,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            applicationApi(method = "GET", path = "/pets/search")
            specEndpoint(ownersEndpoint)
            specEndpoint(petsEndpoint)
            setEndpointsAPIFlag(true)
        }

        assertThat(input.generate().coverageOperations).noneMatch { it.coverageStatus == CoverageStatus.MISSING_IN_SPEC }
    }

    @Test
    fun `should use deterministic fallback when no candidate shares a path prefix`() {
        val invoicesEndpoint = Endpoint(
            path = "/invoices/{invoiceId}",
            method = "GET",
            responseStatus = 200,
            specification = "invoices.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
        val ownersEndpoint = Endpoint(
            path = "/owners/{ownerId}",
            method = "GET",
            responseStatus = 200,
            specification = "owners.yaml",
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            applicationApi(method = "GET", path = "/pets/search")
            specEndpoint(invoicesEndpoint)
            specEndpoint(ownersEndpoint)
            setEndpointsAPIFlag(true)
        }

        val missingInSpecOperation = input.generate().coverageOperations.single { it.coverageStatus == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecOperation.specConfig.specification).isEqualTo("invoices.yaml")
    }

    @Test
    fun `should treat wip as not implemented in path and total coverage calculations`() {
        val wipEndpoint = Endpoint(
            path = "/wip", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val uncoveredEndpoint = Endpoint(
            path = "/uncovered", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            specEndpoint(wipEndpoint)
            specEndpoint(uncoveredEndpoint)
            testResult(
                TestResultRecord(
                    path = "/wip",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    isWip = true,
                    specType = SpecType.OPENAPI,
                    specification = "specs/openapi.yaml"
                )
            )
        }

        val report = input.generate().toConsoleReport()

        assertThat(report.coverageRows).anyMatch {
            it.path == "/wip" && it.remarks == CoverageStatus.NOT_IMPLEMENTED && it.coveragePercentage == 0
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/uncovered" && it.remarks == CoverageStatus.NOT_TESTED && it.coveragePercentage == 0
        }
        assertThat(report.coveragePercentage).isEqualTo(0)
    }

    @Test
    fun `should calculate coverage percentage with only current results`() {
        val endpoint1 = Endpoint(
            path = "/current", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint2 = Endpoint(
            path = "/previous", method = "POST", responseStatus = 201,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint3 = Endpoint(
            path = "/uncovered", method = "GET", responseStatus = 404,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            actualResponseStatus = 200,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()
        assertThat(report.coveragePercentage).isEqualTo(33)
        assertThat(report.coverageRows).anyMatch { it.path == "/current" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/previous" && it.remarks.toString() == "not tested" && it.coveragePercentage == 0 }
        assertThat(report.coverageRows).anyMatch { it.path == "/uncovered" && it.remarks.toString() == "not tested" && it.coveragePercentage == 0 }
    }

    @Test
    fun `should calculate coverage percentage including previous test results and uncovered endpoints`() {
        val endpoint1 = Endpoint(
            path = "/current", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint2 = Endpoint(
            path = "/previous", method = "POST", responseStatus = 201,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint3 = Endpoint(
            path = "/not-possible", method = "GET", responseStatus = 404,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            actualResponseStatus = 200,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val previousRecord = TestResultRecord(
            path = "/previous",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
            actualResponseStatus = 201,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val notPossibleButCoveredRecord = TestResultRecord(
            path = "/not-possible",
            method = "GET",
            responseStatus = 404,
            request = null,
            response = null,
            actualResponseStatus = 404,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
            testResult(notPossibleButCoveredRecord.copy(specification = "specs/openapi.yaml"))
            previousTestResult(previousRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()
        assertThat(report.coveragePercentage).isEqualTo(100)
        assertThat(report.coverageRows).anyMatch { it.path == "/current" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/previous" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/not-possible" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
    }

    @Test
    fun `should calculate mixed path coverage with covered not implemented and not tested responses`() {
        val allEndpoints = mutableListOf(
            Endpoint("/mixed", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/mixed", "GET", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/mixed", "GET", 500, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            applicationApi("GET", "/other")
            setEndpointsAPIFlag(true)
            testResult(
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    actualResponseStatus = 200,
                    result = TestResult.Success,
                    specType = SpecType.OPENAPI,
                    specification = "specs/openapi.yaml"
                )
            )
            testResult(
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 400,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI,
                    specification = "specs/openapi.yaml"
                )
            )
        }

        val report = input.generate().toConsoleReport()

        assertThat(report.coverageRows.filter { it.path == "/mixed" }).containsExactly(
            OpenApiCoverageConsoleRow("GET", "/mixed", 200, 1, 33, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow(method = "GET", path = "/mixed", responseStatus = "400", exercisedCount = 1, result = "1f", coveragePercentage = 33, remarks = CoverageStatus.NOT_IMPLEMENTED, eligibleForCoverage = true, showPath = false, showMethod = false, requestContentType = null, showRequestContentType = false, responseContentType = null),
            OpenApiCoverageConsoleRow("GET", "/mixed", 500, 0, 33, CoverageStatus.NOT_TESTED, showPath = false, showMethod = false, showRequestContentType = false, omittedStatus = OmittedStatus.SKIPPED),
        )
        assertThat(report.coveragePercentage).isEqualTo(33)
    }

    @Test
    fun `should calculate mixed path coverage with not implemented responses for wip and non-wip`() {
        val allEndpoints = mutableListOf(
            Endpoint("/mixed", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/mixed", "GET", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            applicationApi("GET", "/other")
            setEndpointsAPIFlag(true)
            testResult(
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    isWip = true,
                    specType = SpecType.OPENAPI,
                    specification = "specs/openapi.yaml"
                )
            )
            testResult(
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 400,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI,
                    specification = "specs/openapi.yaml"
                )
            )
        }

        val report = input.generate().toConsoleReport()

        assertThat(report.coverageRows.filter { it.path == "/mixed" }).containsExactly(
            OpenApiCoverageConsoleRow(
                method = "GET",
                path = "/mixed",
                responseStatus = "200",
                exercisedCount = 1,
                result = "1f",
                coveragePercentage = 0,
                remarks = CoverageStatus.NOT_IMPLEMENTED,
                eligibleForCoverage = true,
                showPath = true,
                showMethod = true,
                requestContentType = null,
                showRequestContentType = true,
                responseContentType = null,
            ),
            OpenApiCoverageConsoleRow(
                method = "GET",
                path = "/mixed",
                responseStatus = "400",
                exercisedCount = 1,
                result = "1f",
                coveragePercentage = 0,
                remarks = CoverageStatus.NOT_IMPLEMENTED,
                eligibleForCoverage = true,
                showPath = false,
                showMethod = false,
                requestContentType = null,
                showRequestContentType = false,
                responseContentType = null,
            ),
        )
        assertThat(report.coveragePercentage).isEqualTo(0)
    }

    @Test
    fun `should calculate coverage for a single path with mixed results when no previous runs`() {
        val endpoint1 = Endpoint(
            path = "/resource", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint2 = Endpoint(
            path = "/resource", method = "POST", responseStatus = 201,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint3 = Endpoint(
            path = "/resource", method = "DELETE", responseStatus = 204,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/resource",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            actualResponseStatus = 200,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()
        assertThat(report.coveragePercentage).isEqualTo(33)
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "GET" && it.remarks.toString() == "covered" && it.coveragePercentage == 33
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "POST" && it.remarks.toString() == "not tested" && it.coveragePercentage == 33
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "DELETE" && it.remarks.toString() == "not tested" && it.coveragePercentage == 33
        }
    }

    @Test
    fun `should calculate coverage for a single path with mixed results including previous runs when provided`() {
        val endpoint1 = Endpoint(
            path = "/resource", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint2 = Endpoint(
            path = "/resource", method = "POST", responseStatus = 201,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint3 = Endpoint(
            path = "/resource", method = "DELETE", responseStatus = 204,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val allEndpoints = mutableListOf(endpoint1, endpoint2, endpoint3)
        val currentRecord = TestResultRecord(
            path = "/resource",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            actualResponseStatus = 200,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val previousRecord = TestResultRecord(
            path = "/resource",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
            actualResponseStatus = 201,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
            previousTestResult(previousRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()
        assertThat(report.coveragePercentage).isEqualTo(67)
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "GET" && it.remarks.toString() == "covered" && it.coveragePercentage == 67
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "POST" && it.remarks.toString() == "covered" && it.coveragePercentage == 67
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "DELETE" && it.remarks.toString() == "not tested" && it.coveragePercentage == 67
        }
    }

    @Test
    fun `endpoint should not be counted towards the final report if it has been filtered out`() {
        val outOfScope = mutableListOf(
            Endpoint(
                "/filtered", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val filtered = mutableListOf(
            Endpoint(
                "/test", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/test",
                method = "POST",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 200,
                specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            filtered.forEach(::specEndpoint)
            outOfScope.forEach(::outOfScopeSpecEndpoint)
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
            decisionSkip("/filtered", "POST", 200, reasoning = Reasoning(mainReason = TestSkipReason.EXCLUDED))
        }

        val report = reportInput.generate().toConsoleReport()
        assertThat(report.coveragePercentage).isEqualTo(100)
    }

    @Test
    fun `should not be marked missing-in-spec if the endpoint is filtered out but actuator shows it`() {
        val outOfScope = mutableListOf(
            Endpoint(
                "/filtered", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val filtered = mutableListOf(
            Endpoint(
                "/test", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val applicationAPIs = mutableListOf(API("POST", "/test"), API("POST", "/filtered"))
        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/test",
                method = "POST",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 200,
                specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            filtered.forEach(::specEndpoint)
            outOfScope.forEach(::outOfScopeSpecEndpoint)
            applicationAPIs.forEach { applicationApi(it.method, it.path) }
            decisionSkip("/filtered", "GET", 200, reasoning = Reasoning(mainReason = TestSkipReason.EXCLUDED))
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }

        val report = reportInput.generate().toConsoleReport()
        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coverageRows.first { it.path == "/filtered" }.remarks).isNotEqualTo(CoverageStatus.NOT_IMPLEMENTED)
    }

    @Test
    fun `not-implemented endpoints should be identified using filtered endpoints instead of all endpoints`() {
        val outOfScope = mutableListOf(
            Endpoint(
                "/filtered", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val filtered = mutableListOf(
            Endpoint(
                "/test", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/test",
                method = "POST",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 0,
                specType = SpecType.OPENAPI
            )
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            filtered.forEach(::specEndpoint)
            outOfScope.forEach(::outOfScopeSpecEndpoint)
            applicationApi("POST", "/other")
            setEndpointsAPIFlag(true)
            decisionSkip("/filtered", "POST", 200, reasoning = Reasoning(mainReason = TestSkipReason.EXCLUDED))
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }

        val report = reportInput.generate().toConsoleReport()
        assertThat(report.coverageRows).anyMatch { it.path == "/test" && it.remarks == CoverageStatus.NOT_IMPLEMENTED }
        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coveragePercentage).isEqualTo(0)
    }

    @Test
    fun `should calculate zero coverage for path with multiple not implemented responses`() {
        val allEndpoints = mutableListOf(
            Endpoint("/pets/search", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/pets/search", "GET", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
        )

        val report = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            applicationApi("GET", "/pets")
            setEndpointsAPIFlag(true)
            testResult(
                TestResultRecord(
                    path = "/pets/search",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI,
                    specification = "specs/openapi.yaml"
                )
            )
            testResult(
                TestResultRecord(
                    path = "/pets/search",
                    method = "GET",
                    responseStatus = 404,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI,
                    specification = "specs/openapi.yaml"
                )
            )
        }.generate().toConsoleReport()

        assertThat(report.coverageRows.filter { it.path == "/pets/search" }).containsExactly(
            OpenApiCoverageConsoleRow(
                method = "GET",
                path = "/pets/search",
                responseStatus = "200",
                exercisedCount = 1,
                result = "1f",
                coveragePercentage = 0,
                remarks = CoverageStatus.NOT_IMPLEMENTED,
                eligibleForCoverage = true,
                showPath = true,
                showMethod = true,
                requestContentType = null,
                showRequestContentType = true,
                responseContentType = null,
            ),
            OpenApiCoverageConsoleRow(
                method = "GET",
                path = "/pets/search",
                responseStatus = "404",
                exercisedCount = 1,
                result = "1f",
                coveragePercentage = 0,
                remarks = CoverageStatus.NOT_IMPLEMENTED,
                eligibleForCoverage = true,
                showPath = false,
                showMethod = false,
                requestContentType = null,
                showRequestContentType = false,
                responseContentType = null,
            ),
        )
        assertThat(report.coveragePercentage).isEqualTo(0)
    }

    @Test
    fun `should report coverage status as 'missing in spec' for failed tests whose operations do not exit in the spec and actualResponseStatus`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/current", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/current",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 200,
                specType = SpecType.OPENAPI
            ),
            TestResultRecord(
                path = "/current",
                method = "GET",
                responseStatus = 400,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 400,
                specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            allEndpoints.forEach(::specEndpoint)
            applicationApi("GET", "/current")
            setEndpointsAPIFlag(true)
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }
        val report = reportInput.generate().toConsoleReport()

        assertThat(report.coverageRows).size().isEqualTo(2)
        val coveredRows = report.coverageRows.filter { it.remarks == CoverageStatus.COVERED }
        assertThat(coveredRows).size().isEqualTo(1)
        val coveredRow = coveredRows.first()
        assertThat(coveredRow.path).isEqualTo("/current")
        assertThat(coveredRow.method).isEqualTo("GET")
        assertThat(coveredRow.responseStatus).isEqualTo("200")

        val missingInSpecRows = report.coverageRows.filter { it.remarks == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecRows).size().isEqualTo(1)
        val missingInSpecRow = missingInSpecRows.first()
        assertThat(missingInSpecRow.path).isEqualTo("/current")
        assertThat(missingInSpecRow.method).isEqualTo("GET")
        assertThat(missingInSpecRow.responseStatus).isEqualTo("400")
    }

    @Test
    fun `should report coverage status as 'missing in spec' for successful tests whose operations do not exit in the spec and actualResponseStatus`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/current", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/current",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 200,
                specType = SpecType.OPENAPI
            ),
            TestResultRecord(
                path = "/current",
                method = "GET",
                responseStatus = 400,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 400,
                specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            allEndpoints.forEach(::specEndpoint)
            setEndpointsAPIFlag(true)
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }
        val report = reportInput.generate().toConsoleReport()

        assertThat(report.coverageRows).size().isEqualTo(2)
        val coveredRows = report.coverageRows.filter { it.remarks == CoverageStatus.COVERED }
        assertThat(coveredRows).size().isEqualTo(1)
        val coveredRow = coveredRows.first()
        assertThat(coveredRow.path).isEqualTo("/current")
        assertThat(coveredRow.method).isEqualTo("GET")
        assertThat(coveredRow.responseStatus).isEqualTo("200")

        val missingInSpecRows = report.coverageRows.filter { it.remarks == CoverageStatus.MISSING_IN_SPEC }
        assertThat(missingInSpecRows).size().isEqualTo(1)
        val missingInSpecRow = missingInSpecRows.first()
        assertThat(missingInSpecRow.path).isEqualTo("/current")
        assertThat(missingInSpecRow.method).isEqualTo("GET")
        assertThat(missingInSpecRow.responseStatus).isEqualTo("400")
    }

    @Test
    fun `should keep rows for same path contiguous when missing in spec operation is generated later`() {
        val report = OpenApiCoverageBuilder.buildCoverage {
            setEndpointsAPIFlag(true)
            specEndpoint(method = "GET", path = "/orders", responseCode = 200, responseType = "application/json")
            specEndpoint(method = "GET", path = "/monitor/{id}", responseCode = 200, responseType = "application/json")
            testResult(path = "/orders", method = "GET", responseCode = 200, result = TestResult.Success, responseType = "application/json", actualResponseCode = 200)
            testResult(path = "/orders", method = "GET", responseCode = 400, result = TestResult.Failed, responseType = "application/json", actualResponseCode = 400)
        }.generate().toConsoleReport()

        val rows = report.coverageRows
        val orderRowIndexes = rows.withIndex().filter { row -> row.value.path == "/orders" }
        val monitorRowIndexes = rows.withIndex().filter { row -> row.value.path == "/monitor/{id}" }

        assertThat(orderRowIndexes).isNotEmpty()
        assertThat(monitorRowIndexes).isNotEmpty()
        assertThat(orderRowIndexes.last().index).isLessThan(monitorRowIndexes.first().index)
        assertThat(orderRowIndexes.last().value.remarks).isEqualTo(CoverageStatus.MISSING_IN_SPEC)
        assertThat(orderRowIndexes.last().index - orderRowIndexes.first().index + 1).isEqualTo(orderRowIndexes.size)
    }

    @Test
    fun `should not create synthetic wip missing in spec operations`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/orders", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/orders",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 405,
                specType = SpecType.OPENAPI,
                isWip = true
            )
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            allEndpoints.forEach(::specEndpoint)
            applicationApi("GET", "/orders")
            setEndpointsAPIFlag(true)
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }

        val report = reportInput.generate().toConsoleReport()
        val exercisedRow = report.coverageRows.single { it.responseStatus == "200" }
        assertThat(report.missedOperations).isEqualTo(0)
        assertThat(report.coverageRows).noneMatch { it.responseStatus == "405" }
        assertThat(exercisedRow.remarks).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
        assertThat(exercisedRow.exercisedCount).isEqualTo(1)
    }

    @Test
    fun `should not add synthetic missing in spec record for failed tests classified as not implemented`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/pets/search", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            ),
            Endpoint(
                path = "/pets", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/pets/search",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 422,
                specType = SpecType.OPENAPI
            )
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            specEndpoint(allEndpoints.first())
            outOfScopeSpecEndpoint(allEndpoints.last())
            applicationApi("GET", "/pets")
            setEndpointsAPIFlag(true)
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }
        val report = reportInput.generate().toConsoleReport()

        assertThat(report.coverageRows).anyMatch {
            it.path == "/pets/search" && it.responseStatus == "200" && it.remarks == CoverageStatus.NOT_IMPLEMENTED
        }
        val resultRecord = report.testResultRecords.single()
        assertThat(resultRecord.path).isEqualTo("/pets/search")
        assertThat(resultRecord.method).isEqualTo("GET")
        assertThat(resultRecord.responseStatus).isEqualTo(200)
        assertThat(resultRecord.result).isEqualTo(TestResult.Failed)
        assertThat(report.testResultRecords).noneMatch { it.result == TestResult.MissingInSpec }
    }

    @Test
    fun `should count wip not implemented operations as not implemented operations`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/pets/search", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/pets/search",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 0,
                specType = SpecType.OPENAPI,
                isWip = true
            )
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            specEndpoint(allEndpoints.first())
            outOfScopeSpecEndpoint(allEndpoints.last())
            applicationApi("GET", "/pets")
            setEndpointsAPIFlag(true)
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }

        val report = reportInput.generate().toConsoleReport()
        val row = report.coverageRows.single { it.path == "/pets/search" }

        assertThat(report.notImplementedOperations).isEqualTo(1)
        assertThat(row.path).isEqualTo("/pets/search")
        assertThat(row.method).isEqualTo("GET")
        assertThat(row.responseStatus).isEqualTo("200")
        assertThat(row.remarks).isEqualTo(CoverageStatus.NOT_IMPLEMENTED)
        assertThat(row.exercisedCount).isEqualTo(1)
    }

    @Test
    fun `should not add synthetic missing in spec record for connection refused when test is classified as not implemented`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/pets/search", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            ),
            Endpoint(
                path = "/pets", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                path = "/pets/search",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 0,
                specType = SpecType.OPENAPI
            )
        )

        val reportInput = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("")
            specEndpoint(allEndpoints.first())
            applicationApi("GET", "/pets")
            setEndpointsAPIFlag(true)
            testResultRecords.forEach { testResult(it.copy(specification = "specs/openapi.yaml")) }
        }
        val report = reportInput.generate().toConsoleReport()

        assertThat(report.coverageRows).containsExactly(
            OpenApiCoverageConsoleRow(
                method = "GET",
                path = "/pets/search",
                responseStatus = "200",
                exercisedCount = 1,
                result = "1f",
                coveragePercentage = 0,
                remarks = CoverageStatus.NOT_IMPLEMENTED,
                eligibleForCoverage = true,
                showPath = true,
                showMethod = true,
                requestContentType = null,
                showRequestContentType = true,
                responseContentType = null,
            )
        )
        val resultRecord = report.testResultRecords.single()
        assertThat(resultRecord.path).isEqualTo("/pets/search")
        assertThat(resultRecord.method).isEqualTo("GET")
        assertThat(resultRecord.responseStatus).isEqualTo(200)
        assertThat(resultRecord.result).isEqualTo(TestResult.Failed)
        assertThat(resultRecord.actualResponseStatus).isEqualTo(0)
        assertThat(report.testResultRecords).noneMatch { it.result == TestResult.MissingInSpec }
    }

    @Test
    fun `should not create extra not tested record when endpoint not in test operations`() {
        val endpoint1 = Endpoint(
            path = "/current", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val endpoint2 = Endpoint(
            path = "/previous", method = "POST", responseStatus = 201, requestContentType = "application/json",
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val allEndpoints = mutableListOf(endpoint1, endpoint2)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()

        assertThat(report.testResultRecords.count()).isEqualTo(1)
    }

    @Test
    fun `should report requestContentTypes as null wherever it is not applicable or available`() {
        val endpoint1 = Endpoint(
            path = "/current", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val allEndpoints = mutableListOf(endpoint1)
        val currentRecord = TestResultRecord(
            path = "/current",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            result = TestResult.Success,
             specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageBuilder.buildCoverage {
            configFilePath("specmatic.yaml")
            allEndpoints.forEach(::specEndpoint)
            testResult(currentRecord.copy(specification = "specs/openapi.yaml"))
        }

        val report = input.generate().toConsoleReport()

        assertThat(report.testResultRecords.count()).isEqualTo(1)
        val notCoveredRecord = report.testResultRecords.first { it.path == "/current" }
        assertThat(notCoveredRecord.requestContentType).isNull()
    }

    @Test
    fun shouldNotifyHooksWithIncludedAndExcludedActuatorApis() {
        val listener = RecordingCoverageListener()
        val input = OpenApiCoverage(configFilePath = "", coverageHooks = listOf(listener), filterExpression = "PATH = '/include'")
        val includedApi = API("GET", "/include")
        val excludedApi = API("POST", "/exclude")
        input.addAPIs(listOf(includedApi, excludedApi))

        assertThat(listener.actuatorApiCalls).hasSize(1)
        val (notExcluded, excluded) = listener.actuatorApiCalls.single()
        assertThat(notExcluded).containsExactly(includedApi)
        assertThat(excluded).containsExactlyInAnyOrder(excludedApi)
    }

    @Test
    fun shouldNotifyHooksWithIncludedAndExcludedEndpoints() {
        val listener = RecordingCoverageListener()
        val input = OpenApiCoverage(configFilePath = "", coverageHooks = listOf(listener))

        val includedEndpoint = Endpoint(
            path = "/include", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val excludedEndpoint = Endpoint(
            path = "/exclude", method = "POST", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        input.addEndpoints(listOf(includedEndpoint, excludedEndpoint), listOf(includedEndpoint))
        assertThat(listener.endpointApiCalls).hasSize(1)
        val (notExcludedEndpoints, excludedEndpoints) = listener.endpointApiCalls.single()
        assertThat(notExcludedEndpoints).containsExactly(includedEndpoint)
        assertThat(excludedEndpoints).containsExactlyInAnyOrder(excludedEndpoint)
    }

    @Test
    fun shouldNotifyHooksWithPathCoverageAndOverallCoverageAndGovernanceResult(@TempDir tempDir: File) {
        val listener = RecordingCoverageListener()
        val configFile = tempDir.resolve("specmatic.yaml")
        configFile.writeText("""
        version: 2
        report:
          types:
            APICoverage:
              OpenAPI:
                successCriteria:
                  minThresholdPercentage: 70
                  maxMissedEndpointsInSpec: 0
                  enforce: true
        """.trimIndent())

        val coverage = OpenApiCoverageBuilder.buildCoverage {
            addListener(listener)
            applicationApi("DELETE", "/undocumented")
            specEndpoint("GET", "/pets", 200)
            specEndpoint("GET", "/pets", 404)
            specEndpoint("POST", "/orders", 201)
            testResult(path = "/pets", method = "GET", responseCode = 200, result = TestResult.Success)
            testResult(path = "/orders", method = "POST", responseCode = 201, result = TestResult.Success)
        }

        val report = coverage.generate().toConsoleReport()
        assertThat(listener.pathCoverageCalls.toMap()).containsExactlyInAnyOrderEntriesOf(mapOf("/pets" to 50, "/orders" to 100, "/undocumented" to 0))
        assertThat(listener.totalCoverageCalls).containsExactly(67)
        assertThat(report.coveragePercentage).isEqualTo(67)
        assertThat(report.missedOperations).isEqualTo(1)

        val processor = OpenApiCoverageReportProcessor(coverage.generate(), tempDir.absolutePath)
        val reportConfiguration = loadSpecmaticConfig(configFile.absolutePath).getReport()!!
        assertThatThrownBy { processor.assertSuccessCriteria(reportConfiguration, report) }.isInstanceOf(AssertionError::class.java)

        assertThat(listener.governanceCalls).hasSize(1)
        assertThat(listener.governanceCalls.single()).isInstanceOf(Result.Failure::class.java)
        assertThat(listener.governanceCalls.single().reportString())
            .containsIgnoringWhitespaces("Total API coverage: 67% is less than the specified minimum threshold of 70%")
            .containsIgnoringWhitespaces("Total missed operations: 1 is greater than the maximum threshold of 0")
    }

    private class RecordingCoverageListener : TestReportListener {
        val actuatorApiCalls = mutableListOf<Pair<List<API>, List<API>>>()
        val endpointApiCalls = mutableListOf<Pair<List<Endpoint>, List<Endpoint>>>()
        val totalCoverageCalls = mutableListOf<Int>()
        val pathCoverageCalls = mutableListOf<Pair<String, Int>>()
        val governanceCalls = mutableListOf<Result>()

        override fun onActuatorApis(apisNotExcluded: List<API>, apisExcluded: List<API>) {
            actuatorApiCalls.add(apisNotExcluded to apisExcluded)
        }

        override fun onEndpointApis(endpointsNotExcluded: List<Endpoint>, endpointsExcluded: List<Endpoint>) {
            endpointApiCalls.add(endpointsNotExcluded to endpointsExcluded)
        }

        override fun onCoverageCalculated(coverage: Int, absoluteCoverage: Int) {
            totalCoverageCalls.add(coverage)
        }

        override fun onPathCoverageCalculated(path: String, pathCoverage: Int) {
            pathCoverageCalls.add(path to pathCoverage)
        }

        override fun onGovernance(result: Result) {
            governanceCalls.add(result)
        }

        override fun onActuator(enabled: Boolean) = Unit
        override fun onTestResult(result: TestExecutionResult) = Unit
        override fun onTestsComplete() = Unit
        override fun onExampleErrors(resultsBySpecFile: Map<String, Result>) = Unit
        override fun onEnd() = Unit
        override fun onTestDecision(decision: Decision<ContractTest, Scenario>) = Unit
    }
}
