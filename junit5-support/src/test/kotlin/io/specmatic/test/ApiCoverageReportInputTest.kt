package io.specmatic.test

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.TestResult
import io.specmatic.test.SpecmaticJUnitSupport.Companion.FILTER
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.coverage.console.Remarks
import io.specmatic.test.reports.renderers.CoverageReportTextRenderer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            TestResultRecord("/route1", "GET", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 401, TestResult.Success),
            TestResultRecord("/route2", "GET", 200, TestResult.Success),
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2")
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route1", "POST", 401),
            Endpoint("/route2", "GET", 200),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route1", "200", "1", 100, Remarks.Covered, showPath=false, showMethod=true),
                    OpenApiCoverageConsoleRow("POST", "/route1", "401", "1", 100, Remarks.Covered, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 100, Remarks.Covered)
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
            TestResultRecord("/route1", "GET", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 401, TestResult.Success),
            TestResultRecord("/route2", "GET", 200, TestResult.Success)
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route1", "POST", 401),
            Endpoint("/route2", "GET", 200),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100, Remarks.Covered, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route1", 401, 1, 100, Remarks.Covered, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 50, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route2", 0, 0, 50, Remarks.Missed, showPath = false),
                    OpenApiCoverageConsoleRow("GET", "/route3", 0, 0, 0, Remarks.Missed),
                    OpenApiCoverageConsoleRow("POST", "/route3", 0, 0, 0, Remarks.Missed, showPath = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 3, missedEndpointsCount = 1, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `test generates api coverage report when some endpoints are marked as excluded`() {
        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 401, TestResult.Success),
            TestResultRecord("/route2", "GET", 200, TestResult.Success),
            TestResultRecord("/route2", "POST", 200, TestResult.Success)
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
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route1", "POST", 401),
            Endpoint("/route2", "GET", 200),
            Endpoint("/route2", "POST", 200),
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
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100,  Remarks.Covered, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route1", 401, 1, 100,  Remarks.Covered, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 100,  Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route2", 200, 1, 100,  Remarks.Covered, showPath = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 2,  missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `test generates empty api coverage report when all endpoints are marked as excluded`() {
        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 401, TestResult.Success),
            TestResultRecord("/route2", "GET", 200, TestResult.Success),
            TestResultRecord("/route2", "POST", 200, TestResult.Success)
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
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route1", "POST", 401),
            Endpoint("/route2", "GET", 200),
            Endpoint("/route2", "POST", 200),
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
            TestResultRecord("/route1", "GET", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 200, TestResult.Success),
            TestResultRecord("/route2", "GET", 200, TestResult.Failed, actualResponseStatus = 404),
            TestResultRecord("/route2", "POST", 200, TestResult.Failed, actualResponseStatus = 404)
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route2", "GET", 200),
            Endpoint("/route2", "POST", 200),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport,specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100, Remarks.Covered, showPath = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 50, Remarks.NotImplemented),
                    OpenApiCoverageConsoleRow("GET", "/route2", 404, 0, 50, Remarks.Missed, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 200, 1, 50, Remarks.NotImplemented, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 404, 0, 50, Remarks.Missed, showPath = false, showMethod = false)
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
            TestResultRecord("/route1", "GET", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 200, TestResult.Success),
            TestResultRecord("/route2", "GET", 200, TestResult.Success),
            TestResultRecord("/route2", "POST", 200, TestResult.Failed, actualResponseStatus = 404)
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route2", "GET", 200),
            Endpoint("/route2", "POST", 200),
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route1", 200, 1, 100, Remarks.Covered, showPath = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 67, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route2", 200, 1, 67, Remarks.NotImplemented, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 404, 0, 67, Remarks.Missed, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route3/{route_id}", 0, 0, 0, Remarks.Missed),
                    OpenApiCoverageConsoleRow("POST", "/route3/{route_id}", 0, 0, 0, Remarks.Missed, showPath = false)
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
            TestResultRecord("/route1", "GET", 200, TestResult.Success, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            TestResultRecord("/route1", "POST", 200, TestResult.Success, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route1.yaml", "HTTP"),
            TestResultRecord("/route2", "GET", 200, TestResult.Success, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP"),
            TestResultRecord("/route2", "POST", 200, TestResult.Failed, "git", "https://github.com/specmatic/specmatic-order-contracts.git", "main", "in/specmatic/examples/store/route2.yaml", "HTTP", actualResponseStatus = 404)
        )

        val endpointsInSpec = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route2", "GET", 200),
            Endpoint("/route2", "POST", 200),
        )

        val openApiCoverageJsonReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = endpointsInSpec, endpointsAPISet = true).generateJsonReport()
        val json = Json {
            encodeDefaults = false
        }
        val reportJson = json.encodeToString(openApiCoverageJsonReport)
        assertThat(reportJson.trimIndent()).isEqualTo(
            """{"specmaticConfigPath":"./specmatic.json","apiCoverage":[{"type":"git","repository":"https://github.com/specmatic/specmatic-order-contracts.git","branch":"main","specification":"in/specmatic/examples/store/route1.yaml","serviceType":"HTTP","operations":[{"path":"/route1","method":"GET","responseCode":200,"count":1,"coverageStatus":"covered"},{"path":"/route1","method":"POST","responseCode":200,"count":1,"coverageStatus":"covered"}]},{"type":"git","repository":"https://github.com/specmatic/specmatic-order-contracts.git","branch":"main","specification":"in/specmatic/examples/store/route2.yaml","serviceType":"HTTP","operations":[{"path":"/route2","method":"GET","responseCode":200,"count":1,"coverageStatus":"covered"},{"path":"/route2","method":"POST","responseCode":404,"coverageStatus":"missing in spec"},{"path":"/route2","method":"POST","responseCode":200,"count":1,"coverageStatus":"not implemented"}]},{"type":null,"repository":null,"branch":null,"specification":null,"serviceType":"HTTP","operations":[{"path":"/route3/{route_id}","method":"GET","coverageStatus":"missing in spec"},{"path":"/route3/{route_id}","method":"POST","coverageStatus":"missing in spec"}]}]}"""
        )
    }

    @Test
    fun `test generates api coverage report with endpoints present in spec but not tested`() {
        val testReportRecords = mutableListOf(
            TestResultRecord("/route1", "GET", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 200, TestResult.Success),
            TestResultRecord("/route1", "POST", 401, TestResult.Success),
            TestResultRecord("/route2", "GET", 200, TestResult.Success),
            TestResultRecord("/route2", "GET", 404, TestResult.Success),
            TestResultRecord("/route2", "POST", 500, TestResult.Success),
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/route1"),
            API("POST", "/route1"),
            API("GET", "/route2")
        )

        val allEndpoints = mutableListOf(
            Endpoint("/route1", "GET", 200),
            Endpoint("/route1", "POST", 200),
            Endpoint("/route1", "POST", 401),
            Endpoint("/route2", "GET", 200),
            Endpoint("/route2", "GET", 400)
        )

        val apiCoverageReport = OpenApiCoverageReportInput(CONFIG_FILE_PATH, testReportRecords, applicationAPIs, allEndpoints = allEndpoints, endpointsAPISet = true).generate()
        println(CoverageReportTextRenderer().render(apiCoverageReport, specmaticConfig))
        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/route1", 200, 1, 100, Remarks.Covered),
                    OpenApiCoverageConsoleRow("POST", "/route1", "200", "1", 100, Remarks.Covered, showPath = false),
                    OpenApiCoverageConsoleRow("POST", "/route1", "401", "1", 100, Remarks.Covered, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 200, 1, 75, Remarks.Covered),
                    OpenApiCoverageConsoleRow("GET", "/route2", 400, 0, 75, Remarks.NotCovered, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("GET", "/route2", 404, 1, 75, Remarks.Invalid, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/route2", 500, 1, 75, Remarks.Covered, showPath = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 2, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }
}