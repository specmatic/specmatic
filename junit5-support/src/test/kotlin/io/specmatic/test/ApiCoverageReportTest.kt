package io.specmatic.test

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.ApiCoverageReportInputTest.Companion.CONFIG_FILE_PATH
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiCoverageReportTest {

    companion object {
        private fun generateCoverageReport(
            testResultRecords: MutableList<TestResultRecord>,
            allEndpoints: MutableList<Endpoint>,
            applicationAPIS: MutableList<API>? = null,
            filteredEndpoints: MutableList<Endpoint> = mutableListOf(),
        ): OpenAPICoverageConsoleReport {
            if (applicationAPIS != null) {
                return OpenApiCoverageReportInput(
                    CONFIG_FILE_PATH,
                    testResultRecords,
                    applicationAPIS,
                    allEndpoints = allEndpoints,
                    filteredEndpoints = filteredEndpoints,
                    endpointsAPISet = true
                ).generate()
            }
            return OpenApiCoverageReportInput(
                CONFIG_FILE_PATH, testResultRecords, allEndpoints = allEndpoints, filteredEndpoints = filteredEndpoints,
            ).generate()
        }
    }

    @Test
    fun `GET 200 in spec not implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
        )
        val applicationAPIs = mutableListOf<API>()
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.NOT_IMPLEMENTED, showPath = false, showMethod = false),
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 1
            )
        )
    }

    @Test
    fun `POST 201 and 400 in spec not implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "POST", 201, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "POST",
                400,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val applicationAPIs = mutableListOf<API>()
        val testResultRecords = mutableListOf(
            TestResultRecord(
                "/order/{id}",
                "POST",
                201,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 404, specType = SpecType.OPENAPI
            ),
            TestResultRecord(
                "/order/{id}",
                "POST",
                400,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 404, specType = SpecType.OPENAPI
            )
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.NOT_IMPLEMENTED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.NOT_IMPLEMENTED, showPath = false, showMethod = false)
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 1
            )
        )

    }

    @Test
    fun `GET 200 in spec not implemented without actuator`() {
        val endpointsInSpec = mutableListOf(Endpoint(
            "/order/{id}",
            "GET",
            200,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        ))
        val testResultRecords =
            mutableListOf(TestResultRecord("/order/{id}", "GET", 200,  request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, specType = SpecType.OPENAPI))
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED, showPath = false, showMethod = false),
                ),
                apiCoverageReport.testResultRecords,
                totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `POST 201 and 400 in spec not implemented without actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "POST", 201, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "POST",
                400,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "POST", 201,  request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, specType = SpecType.OPENAPI),
            TestResultRecord("/order/{id}", "POST", 400,   request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `GET 200 and 404 in spec not implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "GET",
                404,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val applicationAPIs = mutableListOf<API>()
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200,   request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.NOT_IMPLEMENTED),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 1
            )
        )
    }

    @Test
    fun `POST 201, 400 and 404 in spec not implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "POST", 201, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val applicationAPIS = mutableListOf<API>()
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "POST", 201,   request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, specType = SpecType.OPENAPI),
            TestResultRecord("/order/{id}", "POST", 400,   request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404, specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIS, filteredEndpoints = endpointsInSpec)


        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.NOT_IMPLEMENTED),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.NOT_IMPLEMENTED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 1
            )
        )
    }

    @Test
    fun `GET 200 and 404 in spec not implemented without actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "GET",
                404,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200,   request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount =  0, notImplementedAPICount =  0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount =  0
            )
        )
    }

    @Test
    fun `POST 201, 400 and 404 in spec not implemented without actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "POST", 201, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "POST", 201,   request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI),
            TestResultRecord("/order/{id}", "POST", 400,   request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount =  0
            )
        )
    }

    @Test
    fun `GET 200 and 404 in spec implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "GET",
                404,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/order/{id}")
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200,   request = null, response = null, result = TestResult.Success, actualResponseStatus = 200,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs, filteredEndpoints = endpointsInSpec)


        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `POST 201, 400 and 404 in spec implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "POST", 201, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val applicationAPIs = mutableListOf(
            API("POST", "/order/{id}")
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "POST", 201,   request = null, response = null, result = TestResult.Success, actualResponseStatus = 201,  specType = SpecType.OPENAPI),
            TestResultRecord("/order/{id}", "POST", 400,   request = null, response = null, result = TestResult.Success, actualResponseStatus = 400,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )

    }

    @Test
    fun `GET 200 and 400 in spec implemented without actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "GET",
                400,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200,   request = null, response = null, result = TestResult.Success, actualResponseStatus = 200,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 400, 0, 50, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `POST 201, 400 and 404 in spec implemented without actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "POST", 201, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 400, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI),
            Endpoint("/order/{id}", "POST", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "POST", 201,   request = null, response = null, result = TestResult.Success, actualResponseStatus = 201,  specType = SpecType.OPENAPI),
            TestResultRecord("/order/{id}", "POST", 400,  request = null, response = null, result = TestResult.Success, actualResponseStatus = 400,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 201, 1, 67, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 400, 1, 67, CoverageStatus.COVERED, showPath = false, showMethod = false),
                    OpenApiCoverageConsoleRow("POST", "/order/{id}", 404, 0, 67, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    // FOLLOWING TESTS ARE BAD REQUEST TESTS FOR GET ENDPOINT

    @Test
    fun `GET 200 in spec implemented with actuator bad request`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/order/{id}")
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200,  request = null, response = null, result =  TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `GET 200 in spec implemented without actuator bad request`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `GET 200 and 404 in spec implemented with actuator bad request`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "GET",
                404,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/order/{id}")
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `GET 200 and 404 in spec implemented without actuator bad request`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/order/{id}", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI), Endpoint(
                "/order/{id}",
                "GET",
                404,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
        val testResultRecords = mutableListOf(
            TestResultRecord("/order/{id}", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, filteredEndpoints = endpointsInSpec)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/order/{id}", 404, 0, 50, CoverageStatus.NOT_COVERED, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    // FOLLOWING TESTS ARE FOR INVALID REMARK (Actuator Doesn't Matter)

    @Test
    fun `No Param GET 200 in spec not implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint("/orders", "GET", 200, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val applicationAPIs = mutableListOf<API>()

        val testResultRecords = mutableListOf(
            TestResultRecord("/orders", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs)

        assertThat(apiCoverageReport).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/orders", 200, 1, 50, CoverageStatus.NOT_IMPLEMENTED),
                    OpenApiCoverageConsoleRow("GET", "/orders", 404, 0, 50, CoverageStatus.MISSING_IN_SPEC, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 1, partiallyNotImplementedAPICount = 1
            )
        )
    }

    @Test
    fun `No Param GET 200 and 404 in spec not implemented without actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint(
                "/orders",
                "GET",
                200,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            ), Endpoint("/orders", "GET", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )

        val testResultRecords = mutableListOf(
            TestResultRecord("/orders", "GET", 200, request = null, response = null, result = TestResult.Failed, actualResponseStatus = 404,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, filteredEndpoints = endpointsInSpec)

        assertThat(
            apiCoverageReport
        ).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/orders", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/orders", 404, 0, 50, CoverageStatus.INVALID, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }

    @Test
    fun `No Param GET 200 and 404 in spec implemented with actuator`() {
        val endpointsInSpec = mutableListOf(
            Endpoint(
                "/orders",
                "GET",
                200,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            ), Endpoint("/orders", "GET", 404, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        )
        val applicationAPIs = mutableListOf(
            API("GET", "/orders")
        )

        val testResultRecords = mutableListOf(
            TestResultRecord("/orders", "GET", 200, request = null, response = null, result = TestResult.Success, actualResponseStatus = 200,  specType = SpecType.OPENAPI)
        )
        val apiCoverageReport = generateCoverageReport(testResultRecords, endpointsInSpec, applicationAPIs, filteredEndpoints = endpointsInSpec)

        assertThat(
            apiCoverageReport
        ).isEqualTo(
            OpenAPICoverageConsoleReport(
                listOf(
                    OpenApiCoverageConsoleRow("GET", "/orders", 200, 1, 50, CoverageStatus.COVERED),
                    OpenApiCoverageConsoleRow("GET", "/orders", 404, 0, 50, CoverageStatus.INVALID, showPath = false, showMethod = false),
                ), apiCoverageReport.testResultRecords, totalEndpointsCount = 1, missedEndpointsCount = 0, notImplementedAPICount = 0, partiallyMissedEndpointsCount = 0, partiallyNotImplementedAPICount = 0
            )
        )
    }
}
