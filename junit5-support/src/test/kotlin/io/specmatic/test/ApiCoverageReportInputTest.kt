package io.specmatic.test

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.SpecmaticConfig
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.SpecmaticJUnitSupport.Companion.FILTER
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.renderers.CoverageReportTextRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiCoverageReportInputTest {
    companion object {
        const val CONFIG_FILE_PATH = "./specmatic.json"
        val specmaticConfig = SpecmaticConfig()
    }

    @Test
    fun `test generates api coverage report when all endpoints are covered`() {
        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 401, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2")
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 401, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route1", "200", "1", 100, CoverageStatus.COVERED, showPath=false, showMethod=true),
                    OpenApiCoverageConsoleRow("POST", "/route1", "401", "1", 100, CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 100, CoverageStatus.COVERED)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 2, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `test generates api coverage report when some endpoints are partially covered`() {
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2"),
            API("POST", "/route2"),
            API("GET", "/route3"),
            API("POST", "/route3")
        )

        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 401, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP)
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 401, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route1", 401, 1, 100, CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route2", 0, 0, 50, CoverageStatus.MISSING_IN_SPEC, showPath = false),
                    OpenApiCoverageConsoleRow("GET", "/route3", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("POST", "/route3", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC, showPath = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 3, missedEndpointsCount = 1, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `test generates api coverage report when some endpoints are marked as excluded`() {
        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 401, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP)
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2"),
            API("POST", "/route2"),
            API("GET", "/healthCheck"),
            API("GET", "/heartbeat")
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 401, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "POST", 200, protocol = SpecmaticProtocol.HTTP),
        )

        val excludedAPIs = mutableListOf(
            "/healthCheck",
            "/heartbeat"
        )

        System.setProperty(FILTER, "PATH!='/healthCheck, /heartbeat'")
        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, excludedAPIs, endpointsInSpec,true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100,  CoverageStatus.COVERED, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route1", 401, 1, 100,  CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 100,  CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route2", 200, 1, 100,  CoverageStatus.COVERED, showPath = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 2,  missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `test generates empty api coverage report when all endpoints are marked as excluded`() {
        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 401, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP)
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2"),
            API("POST", "/route2"),
            API("GET", "/healthCheck"),
            API("GET", "/heartbeat")
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 401, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "POST", 200, protocol = SpecmaticProtocol.HTTP),
        )

        val excludedAPIs = mutableListOf(
            "/route1",
            "/route2",
            "/healthCheck",
            "/heartbeat"
        )
        System.setProperty(FILTER, "PATH!='/healthCheck, /heartbeat, /route1, /route2'")
        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, excludedAPIs, endpointsInSpec,true).generate()
        assertThat(apiCoverageReport.coverageRows).isEmpty()
        assertThat(apiCoverageReport.totalCoveragePercentage).isEqualTo(0)
    }

    @Test
    fun `test generates empty api coverage report when no paths are documented in the open api spec and endpoints api is not defined`() {
        val testReportRecords = mutableListOf<TestResultRecord>()
        val applicationAPIs = mutableListOf<API>()
        val excludedAPIs = mutableListOf<String>()
        val specEndpoints = mutableListOf<Endpoint>()

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, excludedAPIs, specEndpoints, false).generate()
        assertThat(apiCoverageReport.coverageRows).isEmpty()
    }

    @Test
    fun `test generates coverage report when some endpoints or operations are present in spec, but not implemented`() {
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1")
        )

        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "POST", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, protocol = SpecmaticProtocol.HTTP)
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "POST", 200, protocol = SpecmaticProtocol.HTTP),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport,specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED, showPath = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 50, CoverageStatus.NOT_IMPLEMENTED),
                    OpenApiCoverageConsoleRow("GET", "/route2", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 200, 1, 50, CoverageStatus.NOT_IMPLEMENTED, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC, showPath = false, showMethod = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 2, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 1
            )
        )
    }

    @Test
    fun `test generates api coverage report when partially covered and partially implemented endpoints`() {
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2"),
            API("GET", "/route3/{route_id}"),
            API("POST", "/route3/{route_id}")
        )

        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "POST", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, protocol = SpecmaticProtocol.HTTP)
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "POST", 200, protocol = SpecmaticProtocol.HTTP),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100, CoverageStatus.COVERED, showPath = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 67, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route2", 200, 1, 67, CoverageStatus.NOT_IMPLEMENTED, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 404, 0, 67, CoverageStatus.MISSING_IN_SPEC, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route3/{route_id}", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("POST", "/route3/{route_id}", 0, 0, 0, CoverageStatus.MISSING_IN_SPEC, showPath = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 3, missedEndpointsCount = 1, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 1
            )
        )
    }

    @Test
    fun `test generates api coverage json report with partially covered and partially implemented endpoints`() {
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2"),
            API("GET", "/route3/{route_id}"),
            API("POST", "/route3/{route_id}")
        )

        val testReportRecords = mutableListOf(
            TestResultRecord(
                "/route1",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Success,
                "git",
                "https://github.com/specmatic/specmatic-order-contracts.git",
                "main",
                "in/specmatic/examples/store/route1.yaml",
                protocol = SpecmaticProtocol.HTTP
            ),
            TestResultRecord(
                "/route1",
                "POST",
                200,
                request = null,
                response = null,
                result = TestResult.Success,
                "git",
                "https://github.com/specmatic/specmatic-order-contracts.git",
                "main",
                "in/specmatic/examples/store/route1.yaml",
                protocol = SpecmaticProtocol.HTTP
            ),
            TestResultRecord(
                "/route2",
                "GET",
                200,
                request = null,
                response = null,
                result = TestResult.Success,
                "git",
                "https://github.com/specmatic/specmatic-order-contracts.git",
                "main",
                "in/specmatic/examples/store/route2.yaml",
                protocol = SpecmaticProtocol.HTTP
            ),
            TestResultRecord(
                "/route2",
                "POST",
                200,
                request = null,
                response = null,
                result = TestResult.Failed,
                "git",
                "https://github.com/specmatic/specmatic-order-contracts.git",
                "main",
                "in/specmatic/examples/store/route2.yaml",
                protocol = SpecmaticProtocol.HTTP,
                actualResponseStatus = 404
            )
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "POST", 200, protocol = SpecmaticProtocol.HTTP),
        )

        val openApiCoverageJsonReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generateJsonReport()
        val reportJson = ObjectMapper().writeValueAsString(openApiCoverageJsonReport)
        assertThat(reportJson.trimIndent()).isEqualTo(
            """{"specmaticConfigPath":"./specmatic.json","apiCoverage":[{"specification":"in/specmatic/examples/store/route1.yaml","type":"git","repository":"https://github.com/specmatic/specmatic-order-contracts.git","branch":"main","serviceType":"HTTP","specType":"OPENAPI","operations":[{"path":"/route1","method":"GET","responseCode":200,"coverageStatus":"covered","count":1},{"path":"/route1","method":"POST","responseCode":200,"coverageStatus":"covered","count":1}]},{"specification":"in/specmatic/examples/store/route2.yaml","type":"git","repository":"https://github.com/specmatic/specmatic-order-contracts.git","branch":"main","serviceType":"HTTP","specType":"OPENAPI","operations":[{"path":"/route2","method":"GET","responseCode":200,"coverageStatus":"covered","count":1},{"path":"/route2","method":"POST","responseCode":404,"coverageStatus":"missing in spec","count":0},{"path":"/route2","method":"POST","responseCode":200,"coverageStatus":"not implemented","count":1}]},{"type":"git","repository":"","branch":"","serviceType":"HTTP","specType":"OPENAPI","operations":[{"path":"/route3/{route_id}","method":"GET","responseCode":0,"coverageStatus":"missing in spec","count":0},{"path":"/route3/{route_id}","method":"POST","responseCode":0,"coverageStatus":"missing in spec","count":0}]}]}"""
        )
    }

    @Test
    fun `test generates api coverage report with endpoints present in spec but not tested`() {
        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route1", "POST", 401, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 200, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "GET", 404, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
            TestResultRecord("/route2", "POST", 500, request = null, response = null, result = TestResult.Success, protocol = SpecmaticProtocol.HTTP),
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2")
        )

        val allEndpoints = mutableListOf(
            Endpoint("/route1", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route1", "POST", 401, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 200, protocol = SpecmaticProtocol.HTTP),
            Endpoint("/route2", "GET", 400, protocol = SpecmaticProtocol.HTTP)
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = allEndpoints, filteredEndpoints = allEndpoints, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/route1", "200", "1", 100, CoverageStatus.COVERED, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route1", "401", "1", 100, CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 75, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/route2", 400, 0, 75, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 404, 1, 75, CoverageStatus.INVALID, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 500, 1, 75, CoverageStatus.COVERED, showPath = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 2, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }
}