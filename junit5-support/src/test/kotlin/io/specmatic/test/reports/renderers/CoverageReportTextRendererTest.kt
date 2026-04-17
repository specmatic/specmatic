package io.specmatic.test.reports.renderers

import io.specmatic.core.SpecmaticConfig
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfRuleSnapshot
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.reports.coverage.OpenApiCoverageReport
import io.specmatic.test.utils.OpenApiCoverageBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoverageReportTextRendererTest {
    @Test
    fun `renders text report with request and response content type columns`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(path = "/orders", method = "POST", responseCode = 200, requestType = "application/json", responseType = "application/json")
            specEndpoint(path = "/orders", method = "POST", responseCode = 400, responseType = "application/json")
            testResult(
                path = "/orders",
                method = "POST",
                requestType = "application/json",
                responseCode = 200,
                responseType = "application/json",
                result = TestResult.Success,
                actualResponseCode = 200,
                actualResponseType = "application/json"
            )
        }


        val report = coverage.generate().toConsoleReport()
        val lines = CoverageReportTextRenderer().render(report, SpecmaticConfig()).trim().lines()
        assertThat(lines).contains("| coverage | path    | method | requestContentType | response | responseContentType | remarks    | result |")
        assertThat(lines).contains("| 50%      | /orders | POST   | application/json   | 200      | application/json    | covered    | 1p     |")
        assertThat(lines).contains("|          |         |        | NA                 | 400      | application/json    | not tested |        |")
        assertThat(lines).anySatisfy { assertThat(it).contains("50% API Coverage reported from 2 operations eligible for coverage") }
        assertThat(lines).anySatisfy { assertThat(it).contains("50% Absolute Coverage (includes excluded operations that were not tested)") }
        assertThat(lines).anySatisfy { assertThat(it).contains("* = Operation not eligible for coverage") }
        assertThat(lines).anySatisfy { assertThat(it).contains("I = Operation excluded from run by the filter expression") }
    }

    @Test
    fun `renders gherkin text report with soapAction and without content-types and response status column`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(
                path = "/soap",
                method = "urn:getOrder",
                responseCode = 200,
                requestType = "text/xml",
                responseType = "text/xml",
                specType = SpecType.WSDL,
                protocol = SpecmaticProtocol.SOAP
            )

            testResult(
                path = "/soap",
                method = "urn:getOrder",
                requestType = "text/xml",
                responseCode = 200,
                responseType = "text/xml",
                specType = SpecType.WSDL,
                result = TestResult.Success,
                protocol = SpecmaticProtocol.SOAP,
                actualResponseCode = 200,
                actualResponseType = "text/xml",
            )
        }

        val report = coverage.generate().toConsoleReport()
        val lines = CoverageReportTextRenderer().render(report, SpecmaticConfig()).trim().lines()
        assertThat(lines).contains("| coverage | port  | soapAction   | remarks | result |")
        assertThat(lines).contains("| 100%     | /soap | urn:getOrder | covered | 1p     |")
        assertThat(lines).anySatisfy { assertThat(it).contains("100% API Coverage reported from 1 operations eligible for coverage") }
        assertThat(lines).anySatisfy { assertThat(it).contains("100% Absolute Coverage (includes excluded operations that were not tested)") }
        assertThat(lines).anySatisfy { assertThat(it).contains("* = Operation not eligible for coverage") }
        assertThat(lines).anySatisfy { assertThat(it).contains("I = Operation excluded from run by the filter expression") }
    }

    @Test
    fun `renders request content type for first row of each method on same path in console report`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(path = "/orders", method = "POST", responseCode = 200, requestType = "application/json", responseType = "application/json")
            testResult(
                path = "/orders",
                method = "POST",
                requestType = "application/json",
                responseCode = 200,
                responseType = "application/json",
                actualResponseCode = 200,
                actualResponseType = "application/json",
                result = TestResult.Success
            )

            specEndpoint(path = "/orders", method = "PATCH", responseCode = 200, requestType = "application/json", responseType = "application/json")
            testResult(
                path = "/orders",
                method = "PATCH",
                requestType = "application/json",
                responseCode = 200,
                responseType = "application/json",
                actualResponseCode = 200,
                actualResponseType = "application/json",
                result = TestResult.Success
            )
        }

        val report = coverage.generate().toConsoleReport()
        val lines = CoverageReportTextRenderer().render(report, SpecmaticConfig()).trim().lines()
        assertThat(lines).contains("| 100%     | /orders | POST   | application/json   | 200      | application/json    | covered | 1p     |")
        assertThat(lines).contains("|          |         | PATCH  | application/json   | 200      | application/json    | covered | 1p     |")
    }

    @Test
    fun `renders missing in spec and not implemented operations summary`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            applicationApi(method = "GET", path = "/inventory")
            applicationApi(method = "DELETE", path = "/inventory")

            specEndpoint(path = "/orders", method = "POST", responseCode = 201, requestType = "application/json", responseType = "application/json")
            testResult(
                path = "/orders",
                method = "POST",
                requestType = "application/json",
                responseCode = 201,
                responseType = "application/json",
                result = TestResult.Failed,
                actualResponseCode = 400,
                actualResponseType = "application/json"
            )

            specEndpoint(path = "/orders", method = "POST", responseCode = 202, requestType = "application/json", responseType = "application/json")
            testResult(
                path = "/orders",
                method = "POST",
                requestType = "application/json",
                responseCode = 202,
                responseType = "application/json",
                result = TestResult.Failed,
                actualResponseCode = 400,
                actualResponseType = "application/json"
            )
        }

        val report = coverage.generate().toConsoleReport()
        val rendered = CoverageReportTextRenderer().render(report, SpecmaticConfig())
        assertThat(rendered).contains("2 operations found in the service are not documented in the spec.")
        assertThat(rendered).contains("2 operations found in the spec are not implemented.")
    }

    @Test
    fun `does not render missing in spec or not implemented summary when counts are zero`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage {
            specEndpoint(path = "/orders", method = "POST", responseCode = 200, requestType = "application/json", responseType = "application/json")
            testResult(
                path = "/orders",
                method = "POST",
                requestType = "application/json",
                responseCode = 200,
                responseType = "application/json",
                result = TestResult.Success,
                actualResponseCode = 200,
                actualResponseType = "application/json"
            )
        }

        val report = coverage.generate().toConsoleReport()
        val rendered = CoverageReportTextRenderer().render(report, SpecmaticConfig())
        assertThat(rendered).doesNotContain("found in the service")
        assertThat(rendered).doesNotContain("found in the spec")
    }

    @Test
    fun `renders NA when content types are missing`() {
        val coverage = OpenApiCoverageBuilder.buildCoverage { specEndpoint(path = "/orders", method = "GET", responseCode = 200) }
        val report = coverage.generate().toConsoleReport()
        val lines = CoverageReportTextRenderer().render(report, SpecmaticConfig()).trim().lines()
        assertThat(lines).contains("| 0%       | /orders | GET    | NA                 | 200      | NA                  | not tested |        |")
        assertThat(lines).anySatisfy { assertThat(it).contains("0% Absolute Coverage (includes excluded operations that were not tested)") }
    }

    @Test
    fun `renders excluded not tested operations with I marker`() {
        val specConfig = CtrfSpecConfig(
            protocol = "http",
            specType = "openapi",
            specification = "specs/openapi.yaml",
        )
        val excludedFromRunReason = CtrfRuleSnapshot(
            id = "R1",
            title = "Excluded from Run",
            documentationUrl = "https://example.com/rules/excluded-from-run",
            summary = "Excluded from Run",
        )
        val report = OpenApiCoverageReport(
            configFilePath = "specmatic.yaml",
            coverageOperations = listOf(
                io.specmatic.core.report.OpenApiCoverageReportOperation(
                    operation = OpenAPIOperation(
                        path = "/orders",
                        method = "GET",
                        responseCode = 200,
                        protocol = SpecmaticProtocol.HTTP,
                    ),
                    specConfig = specConfig,
                    tests = emptyList(),
                    coverageStatus = CoverageStatus.NOT_TESTED,
                    eligibleForCoverage = false,
                    excludedFromRun = true,
                    reasons = listOf(excludedFromRunReason),
                )
            )
        ).toConsoleReport()

        val lines = CoverageReportTextRenderer().render(report, SpecmaticConfig()).trim().lines()

        assertThat(lines).contains("| 0%       | /orders | GET    | NA                 | 200      | NA                  | not testedI |        |")
        assertThat(lines).anySatisfy { assertThat(it).contains("* = Operation not eligible for coverage") }
        assertThat(lines).anySatisfy { assertThat(it).contains("I = Operation excluded from run by the filter expression") }
    }

    @Test
    fun `renders ineligible operations with star marker`() {
        val specConfig = CtrfSpecConfig(
            protocol = "http",
            specType = "openapi",
            specification = "specs/openapi.yaml",
        )
        val report = OpenApiCoverageReport(
            configFilePath = "specmatic.yaml",
            coverageOperations = listOf(
                io.specmatic.core.report.OpenApiCoverageReportOperation(
                    operation = OpenAPIOperation(
                        path = "/orders",
                        method = "GET",
                        responseCode = 200,
                        protocol = SpecmaticProtocol.HTTP,
                    ),
                    specConfig = specConfig,
                    tests = emptyList(),
                    coverageStatus = CoverageStatus.NOT_TESTED,
                    eligibleForCoverage = false,
                    reasons = emptyList(),
                )
            )
        ).toConsoleReport()

        val lines = CoverageReportTextRenderer().render(report, SpecmaticConfig()).trim().lines()

        assertThat(lines).contains("| 0%       | /orders | GET    | NA                 | 200      | NA                  | not tested* |        |")
    }
}
