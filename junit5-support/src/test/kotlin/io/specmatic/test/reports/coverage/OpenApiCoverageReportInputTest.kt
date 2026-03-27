package io.specmatic.test.reports.coverage

import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.utilities.Decision
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.ContractTest
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.OpenApiCoverageReportProcessor
import io.specmatic.test.reports.TestExecutionResult
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList()
        )

        val report = input.generate()
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = listOf(previousRecord)
        )

        val report = input.generate()
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            applicationAPIs = mutableListOf(API(method = "GET", path = "/pets/search")),
            allEndpoints = mutableListOf(ownersEndpoint, petsEndpoint),
            filteredEndpoints = mutableListOf(ownersEndpoint, petsEndpoint),
            endpointsAPISet = true,
        )

        val missingInSpecEndpoint = input.missingInSpecEndpoints().single()

        assertThat(missingInSpecEndpoint.path).isEqualTo("/pets/search")
        assertThat(missingInSpecEndpoint.specification).isEqualTo("pets.yaml")
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            applicationAPIs = mutableListOf(API(method = "GET", path = "/pets/search/advanced")),
            allEndpoints = mutableListOf(genericPetsEndpoint, searchEndpoint),
            filteredEndpoints = mutableListOf(genericPetsEndpoint, searchEndpoint),
            endpointsAPISet = true,
        )

        val missingInSpecEndpoint = input.missingInSpecEndpoints().single()

        assertThat(missingInSpecEndpoint.specification).isEqualTo("pets-search.yaml")
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            applicationAPIs = mutableListOf(API(method = "POST", path = "/pets/search/advanced")),
            allEndpoints = mutableListOf(deeperGetEndpoint, shallowerPostEndpoint),
            filteredEndpoints = mutableListOf(deeperGetEndpoint, shallowerPostEndpoint),
            endpointsAPISet = true,
        )

        val missingInSpecEndpoint = input.missingInSpecEndpoints().single()

        assertThat(missingInSpecEndpoint.specification).isEqualTo("pets-post.yaml")
    }

    @Test
    fun `should not associate a spec when no endpoints carry specification metadata`() {
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            applicationAPIs = mutableListOf(API(method = "GET", path = "/pets/search")),
            allEndpoints = mutableListOf(ownersEndpoint, petsEndpoint),
            filteredEndpoints = mutableListOf(ownersEndpoint, petsEndpoint),
            endpointsAPISet = true,
        )

        val missingInSpecEndpoint = input.missingInSpecEndpoints().single()

        assertThat(missingInSpecEndpoint.specification).isNull()
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            applicationAPIs = mutableListOf(API(method = "GET", path = "/pets/search")),
            allEndpoints = mutableListOf(invoicesEndpoint, ownersEndpoint),
            filteredEndpoints = mutableListOf(invoicesEndpoint, ownersEndpoint),
            endpointsAPISet = true,
        )

        val missingInSpecEndpoint = input.missingInSpecEndpoints().single()

        assertThat(missingInSpecEndpoint.specification).isEqualTo("invoices.yaml")
    }

    @Test
    fun `should count wip as covered in path and total coverage calculations`() {
        val wipEndpoint = Endpoint(
            path = "/wip", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val uncoveredEndpoint = Endpoint(
            path = "/uncovered", method = "GET", responseStatus = 200,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(
                TestResultRecord(
                    path = "/wip",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    isWip = true,
                    specType = SpecType.OPENAPI
                )
            ),
            allEndpoints = mutableListOf(wipEndpoint, uncoveredEndpoint),
            filteredEndpoints = mutableListOf(wipEndpoint, uncoveredEndpoint)
        )

        val report = input.generate()

        assertThat(report.coverageRows).anyMatch {
            it.path == "/wip" && it.remarks == CoverageStatus.WIP && it.coveragePercentage == 100
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/uncovered" && it.remarks == CoverageStatus.NOT_COVERED && it.coveragePercentage == 0
        }
        assertThat(report.totalCoveragePercentage).isEqualTo(50)
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
            result = TestResult.Success,
             specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList(),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(33)
        assertThat(report.coverageRows).anyMatch { it.path == "/current" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/previous" && it.remarks.toString() == "not covered" && it.coveragePercentage == 0 }
        assertThat(report.coverageRows).anyMatch { it.path == "/uncovered" && it.remarks.toString() == "not covered" && it.coveragePercentage == 0 }
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

        val notPossibleButCoveredRecord = TestResultRecord(
            path = "/not-possible",
            method = "GET",
            responseStatus = 404,
            request = null,
            response = null,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord, notPossibleButCoveredRecord),
            previousTestResultRecord = listOf(previousRecord),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(100)
        assertThat(report.coverageRows).anyMatch { it.path == "/current" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/previous" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
        assertThat(report.coverageRows).anyMatch { it.path == "/not-possible" && it.remarks.toString() == "covered" && it.coveragePercentage == 100 }
    }

    @Test
    fun `should calculate mixed path coverage with covered not implemented and not covered responses`() {
        val allEndpoints = mutableListOf(
            Endpoint("/mixed", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/mixed", "GET", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/mixed", "GET", 500, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Success,
                    specType = SpecType.OPENAPI
                ),
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 400,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI
                )
            ),
            applicationAPIs = mutableListOf(API("GET", "/other")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()

        assertThat(report.coverageRows.filter { it.path == "/mixed" }).containsExactly(
            OpenApiCoverageConsoleRow("GET", "/mixed", 200, 1, 33, CoverageStatus.COVERED),
            OpenApiCoverageConsoleRow("GET", "/mixed", 400, 1, 33, CoverageStatus.NOT_IMPLEMENTED, showPath = false, showMethod = false),
            OpenApiCoverageConsoleRow("GET", "/mixed", 500, 0, 33, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
        )
        assertThat(report.totalCoveragePercentage).isEqualTo(33)
    }

    @Test
    fun `should calculate mixed path coverage with wip and not implemented responses`() {
        val allEndpoints = mutableListOf(
            Endpoint("/mixed", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/mixed", "GET", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    isWip = true,
                    specType = SpecType.OPENAPI
                ),
                TestResultRecord(
                    path = "/mixed",
                    method = "GET",
                    responseStatus = 400,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI
                )
            ),
            applicationAPIs = mutableListOf(API("GET", "/other")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()

        assertThat(report.coverageRows.filter { it.path == "/mixed" }).containsExactly(
            OpenApiCoverageConsoleRow("GET", "/mixed", 200, 1, 50, CoverageStatus.WIP),
            OpenApiCoverageConsoleRow("GET", "/mixed", 400, 1, 50, CoverageStatus.NOT_IMPLEMENTED, showPath = false, showMethod = false),
        )
        assertThat(report.totalCoveragePercentage).isEqualTo(50)
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
            result = TestResult.Success,
             specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList(),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(33)
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "GET" && it.remarks.toString() == "covered" && it.coveragePercentage == 33
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "POST" && it.remarks.toString() == "not covered" && it.coveragePercentage == 33
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "DELETE" && it.remarks.toString() == "not covered" && it.coveragePercentage == 33
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
            result = TestResult.Success,
             specType = SpecType.OPENAPI
        )

        val previousRecord = TestResultRecord(
            path = "/resource",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
            result = TestResult.Success,
             specType = SpecType.OPENAPI
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = listOf(previousRecord),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()
        assertThat(report.totalCoveragePercentage).isEqualTo(67)
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "GET" && it.remarks.toString() == "covered" && it.coveragePercentage == 67
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "POST" && it.remarks.toString() == "covered" && it.coveragePercentage == 67
        }
        assertThat(report.coverageRows).anyMatch {
            it.path == "/resource" && it.method == "DELETE" && it.remarks.toString() == "not covered" && it.coveragePercentage == 67
        }
    }

    @Test
    fun `endpoint should not be counted towards the final report if it has been filtered out`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                "/test", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            ), Endpoint(
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
                "/test", "POST", 200,
                request = null, response = null, result = TestResult.Failed, actualResponseStatus = 200,
                 specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, configFilePath = "",
            allEndpoints = allEndpoints, filteredEndpoints = filtered
        )
        val report = reportInput.generate()

        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coverageRows).noneMatch { it.path == "/filtered" }
        assertThat(report.totalCoveragePercentage).isEqualTo(100)
    }

    @Test
    fun `should not be marked missing-in-spec if the endpoint is filtered out but actuator shows it`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                "/test", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            ), Endpoint(
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
                "/test", "POST", 200,
                request = null, response = null, result = TestResult.Failed, actualResponseStatus = 200,
                 specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, applicationAPIs = applicationAPIs,
            allEndpoints = allEndpoints, filteredEndpoints = filtered, configFilePath = ""
        )
        val report = reportInput.generate()

        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coverageRows).noneMatch { it.path == "/filtered" }
    }

    @Test
    fun `not-implemented endpoints should be identified using filtered endpoints instead of all endpoints`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                "/test", "POST", 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            ), Endpoint(
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
                "/test", "POST", 200,
                request = null, response = null, result = TestResult.Failed, actualResponseStatus = 0,
                 specType = SpecType.OPENAPI
            )
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords,
            configFilePath = "",
            applicationAPIs = mutableListOf(API("POST", "/other")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints, filteredEndpoints = filtered
        )
        val report = reportInput.generate()

        assertThat(report.coverageRows).anyMatch { it.path == "/test" && it.remarks == CoverageStatus.NOT_IMPLEMENTED }
        assertThat(report.testResultRecords).noneMatch { it.path == "/filtered" }
        assertThat(report.coverageRows).noneMatch { it.path == "/filtered" }
        assertThat(report.totalCoveragePercentage).isEqualTo(0)
    }

    @Test
    fun `should calculate zero coverage for path with multiple not implemented responses`() {
        val allEndpoints = mutableListOf(
            Endpoint("/pets/search", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/pets/search", "GET", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
        )

        val report = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(
                TestResultRecord(
                    path = "/pets/search",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI
                ),
                TestResultRecord(
                    path = "/pets/search",
                    method = "GET",
                    responseStatus = 404,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    actualResponseStatus = 0,
                    specType = SpecType.OPENAPI
                )
            ),
            applicationAPIs = mutableListOf(API("GET", "/pets")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        ).generate()

        assertThat(report.coverageRows.filter { it.path == "/pets/search" }).containsExactly(
            OpenApiCoverageConsoleRow("GET", "/pets/search", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED),
            OpenApiCoverageConsoleRow("GET", "/pets/search", 404, 1, 0, CoverageStatus.NOT_IMPLEMENTED, showPath = false, showMethod = false),
        )
        assertThat(report.totalCoveragePercentage).isEqualTo(0)
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
                "/current",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 200,
                 specType = SpecType.OPENAPI
            ),
            TestResultRecord(
                "/current",
                "GET",
                400,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 400,
                 specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords,
            configFilePath = "",
            applicationAPIs = mutableListOf(API("GET", "/current")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints
        )
        val report = reportInput.generate()

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

        // Assert that the responseStatus of testResultRecord's operation is updated
        val missingInSpecTestResult = report.testResultRecords.single { it.result == TestResult.MissingInSpec }
        assertThat(missingInSpecTestResult.operations.first()).isEqualTo(OpenAPIOperation("/current", "GET", null, 400, SpecmaticProtocol.HTTP))
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
                "/current",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 200,
                 specType = SpecType.OPENAPI
            ),
            TestResultRecord(
                "/current",
                "GET",
                400,
                request = null,
                response = null,
                result = TestResult.Success,
                actualResponseStatus = 400,
                 specType = SpecType.OPENAPI
            ),
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords, configFilePath = "", endpointsAPISet = true,
            allEndpoints = allEndpoints
        )
        val report = reportInput.generate()

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
    fun `should not count wip missing in spec operations as missed operations`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/orders", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                "/orders",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 405,
                specType = SpecType.OPENAPI,
                isWip = true
            )
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords,
            configFilePath = "",
            endpointsAPISet = true,
            applicationAPIs = mutableListOf(API("GET", "/orders")),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints.toMutableList()
        )

        val report = reportInput.generate()
        val wipRow = report.coverageRows.single { it.responseStatus == "405" }
        val exercisedWipRow = report.coverageRows.single { it.responseStatus == "200" }

        assertThat(report.missedOperations).isEqualTo(0)
        assertThat(exercisedWipRow.path).isEqualTo("/orders")
        assertThat(exercisedWipRow.method).isEqualTo("GET")
        assertThat(exercisedWipRow.remarks).isEqualTo(CoverageStatus.WIP)
        assertThat(exercisedWipRow.count).isEqualTo("1")
        assertThat(wipRow.path).isEqualTo("/orders")
        assertThat(wipRow.method).isEqualTo("GET")
        assertThat(wipRow.responseStatus).isEqualTo("405")
        assertThat(wipRow.remarks).isEqualTo(CoverageStatus.WIP)
        assertThat(wipRow.count).isEqualTo("0")
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
                "/pets/search",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 422,
                specType = SpecType.OPENAPI
            )
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords,
            configFilePath = "",
            applicationAPIs = mutableListOf(API("GET", "/pets")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints,
            filteredEndpoints = mutableListOf(allEndpoints.first())
        )
        val report = reportInput.generate()

        assertThat(report.coverageRows).containsExactly(
            OpenApiCoverageConsoleRow("GET", "/pets/search", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        )
        val resultRecord = report.testResultRecords.single()
        assertThat(resultRecord.path).isEqualTo("/pets/search")
        assertThat(resultRecord.method).isEqualTo("GET")
        assertThat(resultRecord.responseStatus).isEqualTo(200)
        assertThat(resultRecord.result).isEqualTo(TestResult.NotImplemented)
        assertThat(report.testResultRecords).noneMatch { it.result == TestResult.MissingInSpec }
    }

    @Test
    fun `should not count wip not implemented operations as not implemented operations`() {
        val allEndpoints = mutableListOf(
            Endpoint(
                path = "/pets/search", method = "GET", responseStatus = 200,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )

        val testResultRecords = mutableListOf(
            TestResultRecord(
                "/pets/search",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 0,
                specType = SpecType.OPENAPI,
                isWip = true
            )
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords,
            configFilePath = "",
            applicationAPIs = mutableListOf(API("GET", "/pets")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints,
            filteredEndpoints = mutableListOf(allEndpoints.first())
        )

        val report = reportInput.generate()
        val row = report.coverageRows.single { it.path == "/pets/search" }

        assertThat(report.notImplementedOperations).isEqualTo(0)
        assertThat(row.path).isEqualTo("/pets/search")
        assertThat(row.method).isEqualTo("GET")
        assertThat(row.responseStatus).isEqualTo("200")
        assertThat(row.remarks).isEqualTo(CoverageStatus.WIP)
        assertThat(row.count).isEqualTo("1")
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
                "/pets/search",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 0,
                specType = SpecType.OPENAPI
            )
        )

        val reportInput = OpenApiCoverageReportInput(
            testResultRecords = testResultRecords,
            configFilePath = "",
            applicationAPIs = mutableListOf(API("GET", "/pets")),
            endpointsAPISet = true,
            allEndpoints = allEndpoints,
            filteredEndpoints = mutableListOf(allEndpoints.first())
        )
        val report = reportInput.generate()

        assertThat(report.coverageRows).containsExactly(
            OpenApiCoverageConsoleRow("GET", "/pets/search", 200, 1, 0, CoverageStatus.NOT_IMPLEMENTED)
        )
        val resultRecord = report.testResultRecords.single()
        assertThat(resultRecord.path).isEqualTo("/pets/search")
        assertThat(resultRecord.method).isEqualTo("GET")
        assertThat(resultRecord.responseStatus).isEqualTo(200)
        assertThat(resultRecord.result).isEqualTo(TestResult.NotImplemented)
        assertThat(resultRecord.actualResponseStatus).isEqualTo(0)
        assertThat(report.testResultRecords).noneMatch { it.result == TestResult.MissingInSpec }
    }

    @Test
    fun `should retain requestContentTypes for not covered endpoints`() {
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList(),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()

        assertThat(report.testResultRecords.count()).isEqualTo(2)
        val notCoveredRecord = report.testResultRecords.first { it.path == "/previous" }
        assertThat(notCoveredRecord.requestContentType).isEqualTo("application/json")
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

        val input = OpenApiCoverageReportInput(
            configFilePath = "specmatic.yaml",
            testResultRecords = mutableListOf(currentRecord),
            previousTestResultRecord = emptyList(),
            allEndpoints = allEndpoints,
            filteredEndpoints = allEndpoints
        )

        val report = input.generate()

        assertThat(report.testResultRecords.count()).isEqualTo(1)
        val notCoveredRecord = report.testResultRecords.first { it.path == "/current" }
        assertThat(notCoveredRecord.requestContentType).isNull()
    }

    @Test
    fun shouldNotifyHooksWithIncludedAndExcludedActuatorApis() {
        val listener = RecordingCoverageListener()
        val input = OpenApiCoverageReportInput(configFilePath = "", coverageHooks = listOf(listener), filterExpression = "PATH = '/include'")
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
        val input = OpenApiCoverageReportInput(configFilePath = "", coverageHooks = listOf(listener))

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

        val input = OpenApiCoverageReportInput(
            configFilePath = configFile.absolutePath,
            coverageHooks = listOf(listener),
            testResultRecords = mutableListOf(
                TestResultRecord(
                    path = "/pets",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Success,
                    specType = SpecType.OPENAPI
                ),
                TestResultRecord(
                    path = "/pets",
                    method = "GET",
                    responseStatus = 404,
                    request = null,
                    response = null,
                    result = TestResult.NotCovered,
                    specType = SpecType.OPENAPI
                ),
                TestResultRecord(
                    path = "/orders",
                    method = "POST",
                    responseStatus = 201,
                    request = null,
                    response = null,
                    result = TestResult.Success,
                    specType = SpecType.OPENAPI
                ),
                TestResultRecord(
                    path = "/undocumented",
                    method = "DELETE",
                    responseStatus = 418,
                    request = null,
                    response = null,
                    result = TestResult.MissingInSpec,
                    specType = SpecType.OPENAPI
                )
            )
        )

        val report = input.generate()
        assertThat(listener.pathCoverageCalls.toMap()).containsExactlyInAnyOrderEntriesOf(mapOf("/pets" to 50, "/orders" to 100, "/undocumented" to 0))
        assertThat(listener.totalCoverageCalls).containsExactly(67)
        assertThat(report.totalCoveragePercentage).isEqualTo(67)
        assertThat(report.missedOperations).isEqualTo(1)

        val processor = OpenApiCoverageReportProcessor(input, tempDir.absolutePath)
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

        override fun onCoverageCalculated(coverage: Int) {
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
        override fun onTestDecision(decision: Decision<Pair<ContractTest, String>, Scenario>) = Unit
    }
}
