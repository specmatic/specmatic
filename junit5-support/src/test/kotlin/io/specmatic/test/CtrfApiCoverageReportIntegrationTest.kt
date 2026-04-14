package io.specmatic.test

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.reporter.ctrf.CtrfReportGenerator
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.TestResultRecord.Companion.getCoverageStatus
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverage
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class CtrfApiCoverageReportIntegrationTest {
    @Test
    fun `ctrf html report should include swagger discovered endpoints missing in the contract`() {
        val actualSpec = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val applicationSpec = File("src/test/resources/openapi/api_coverage/app_generated_openapi.json").canonicalFile
        val sourceProvider = "filesystem"
        val sourceRepository = ""
        val sourceRepositoryBranch = "main"

        val input = OpenApiCoverageReportInput(
            configFilePath = actualSpec.canonicalPath,
            endpointsAPISet = true,
        )

        val endpoints = endpointsFrom(actualSpec).map { endpoint ->
            endpoint.copy(
                sourceProvider = sourceProvider,
                sourceRepository = sourceRepository,
                sourceRepositoryBranch = sourceRepositoryBranch,
            )
        }
        input.addEndpoints(endpoints, endpoints)
        input.addAPIs(applicationApisFrom(applicationSpec))

        endpoints.forEach { endpoint ->
            input.addTestReportRecords(
                TestResultRecord(
                    path = endpoint.path,
                    method = endpoint.method,
                    responseStatus = endpoint.responseStatus,
                    responseContentType = endpoint.responseContentType,
                    request = null,
                    response = null,
                    result = TestResult.Success,
                    specification = actualSpec.canonicalPath,
                    specType = SpecType.OPENAPI,
                    requestContentType = endpoint.requestContentType,
                )
            )
        }

        val consoleReport = input.generate()

        assertThat(consoleReport.coverageRows).anyMatch {
            it.path == "/pets/search" && it.remarks == CoverageStatus.MISSING_IN_SPEC
        }
        assertThat(consoleReport.coverageRows).anyMatch {
            it.path == "/pets/find" && it.remarks == CoverageStatus.COVERED
        }

        val ctrfReport = CtrfReportGenerator.generate(
            testResultRecords = consoleReport.testResultRecords,
            specConfig = input.ctrfSpecConfigs(),
            startTime = 0L,
            endTime = 0L,
            extra = mapOf("apiCoverage" to "${consoleReport.totalCoveragePercentage}%"),
            toolName = "Specmatic test",
        ) { ctrfTestResultRecords ->
            ctrfTestResultRecords.filterIsInstance<TestResultRecord>().getCoverageStatus()
        }

        val reportJson = ObjectMapper().writeValueAsString(ctrfReport)
        val reportNode = ObjectMapper().readTree(reportJson)

        val testNames = reportNode["results"]["tests"].map { it["name"].asText() }
        val executionDetails = reportNode["results"]["summary"]["extra"]["executionDetails"].toList()
        val matchingExecutionDetails = executionDetails.filter { it["specification"].asText() == actualSpec.canonicalPath }
        val executionOperations = executionDetails.flatMap { it["operations"].toList() }
        val executionOperationPaths = executionOperations.map { it["path"].asText() }

        assertThat(matchingExecutionDetails)
            .withFailMessage(
                "Expected exactly one CTRF execution detail for spec %s, but found: %s",
                actualSpec.canonicalPath,
                matchingExecutionDetails
            )
            .hasSize(1)

        assertThat(matchingExecutionDetails.single()["type"].asText()).isEqualTo(sourceProvider)

        assertThat(testNames)
            .withFailMessage(
                "Expected raw CTRF tests to contain /pets/search, but test names were: %s",
                testNames
            )
            .anyMatch { it.contains("/pets/search") }

        assertThat(executionOperationPaths)
            .withFailMessage(
                "Expected CTRF executionDetails.operations to include /pets/search after propagating missing-in-spec endpoints into CTRF spec configs. " +
                    "The raw CTRF tests already contain /pets/search, and the HTML report reads executionDetails.operations. " +
                    "If this fails, the missing-in-spec endpoint is still being dropped before the HTML summary is built. " +
                    "Raw test names: %s. Summary operation paths: %s",
                testNames,
                executionOperationPaths
            )
            .contains("/pets/search")
    }

    @Test
    fun `ctrf html report should associate missing in spec endpoint with closest matching spec`() {
        val petsSpec = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val ownersSpec = File("src/test/resources/openapi/api_coverage/owners.yaml").canonicalFile

        val petsEndpoints = endpointsFrom(petsSpec)
        val ownersEndpoints = listOf(
            Endpoint(
                path = "/owners/{ownerId}",
                method = "GET",
                responseStatus = 200,
                specification = ownersSpec.canonicalPath,
                protocol = io.specmatic.license.core.SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI,
            )
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = petsSpec.canonicalPath,
            endpointsAPISet = true,
        )

        input.addEndpoints(ownersEndpoints + petsEndpoints, ownersEndpoints + petsEndpoints)
        input.addAPIs(listOf(
            API(method = "GET", path = "/pets/search")
        ))

        (ownersEndpoints + petsEndpoints).forEach { endpoint ->
            input.addTestReportRecords(
                TestResultRecord(
                    path = endpoint.path,
                    method = endpoint.method,
                    responseStatus = endpoint.responseStatus,
                    responseContentType = endpoint.responseContentType,
                    request = null,
                    response = null,
                    result = TestResult.Success,
                    specification = endpoint.specification,
                    specType = SpecType.OPENAPI,
                    requestContentType = endpoint.requestContentType,
                )
            )
        }

        val consoleReport = input.generate()
        val ctrfReport = CtrfReportGenerator.generate(
            testResultRecords = consoleReport.testResultRecords,
            specConfig = input.ctrfSpecConfigs(),
            startTime = 0L,
            endTime = 0L,
            extra = mapOf("apiCoverage" to "${consoleReport.totalCoveragePercentage}%"),
            toolName = "Specmatic test",
        ) { ctrfTestResultRecords ->
            ctrfTestResultRecords.filterIsInstance<TestResultRecord>().getCoverageStatus()
        }

        val reportNode = ObjectMapper().readTree(ObjectMapper().writeValueAsString(ctrfReport))
        val testNames = reportNode["results"]["tests"].map { it["name"].asText() }
        val executionDetails = reportNode["results"]["summary"]["extra"]["executionDetails"].toList()
        val petsExecutionDetail = executionDetails.single { it["specification"].asText() == petsSpec.canonicalPath }
        val ownersExecutionDetail = executionDetails.single { it["specification"].asText() == ownersSpec.canonicalPath }
        val petsOperationPaths = petsExecutionDetail["operations"].map { it["path"].asText() }
        val ownersOperationPaths = ownersExecutionDetail["operations"].map { it["path"].asText() }

        assertThat(testNames)
            .withFailMessage(
                "Expected raw CTRF tests to contain /pets/search, but test names were: %s",
                testNames
            )
            .anyMatch { it.contains("/pets/search") }

        assertThat(petsOperationPaths)
            .withFailMessage(
                "Expected /pets/search to be attached to pets spec execution details. Pets operations: %s. Owners operations: %s",
                petsOperationPaths,
                ownersOperationPaths
            )
            .contains("/pets/search")

        assertThat(ownersOperationPaths)
            .withFailMessage(
                "Expected /pets/search to be absent from owners spec execution details. Pets operations: %s. Owners operations: %s",
                petsOperationPaths,
                ownersOperationPaths
            )
            .doesNotContain("/pets/search")
    }

    @Test
    fun `ctrf report summary coverage should match console coverage for not implemented endpoints`() {
        val specFile = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val endpoint = Endpoint(
            path = "/pets/search",
            method = "GET",
            responseStatus = 200,
            specification = specFile.canonicalPath,
            protocol = io.specmatic.license.core.SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI,
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = specFile.canonicalPath,
            endpointsAPISet = true,
        )
        input.addEndpoints(listOf(endpoint), listOf(endpoint))
        input.addAPIs(listOf(API(method = "GET", path = "/pets")))
        input.addTestReportRecords(
            TestResultRecord(
                path = endpoint.path,
                method = endpoint.method,
                responseStatus = endpoint.responseStatus,
                responseContentType = endpoint.responseContentType,
                request = null,
                response = null,
                result = TestResult.Failed,
                actualResponseStatus = 422,
                specification = endpoint.specification,
                specType = SpecType.OPENAPI,
            )
        )

        val consoleReport = input.generate()
        val reportNode = ctrfReportNode(input, consoleReport)

        assertThat(consoleReport.totalCoveragePercentage).isEqualTo(0)
        assertThat(findTextValue(reportNode, "apiCoverage")).isEqualTo("0%")
        assertThat(reportNode["results"]["tests"].map { it["name"].asText() }).anyMatch { it.contains("/pets/search") }
    }

    @Test
    fun `ctrf report should preserve wip coverage semantics from console report`() {
        val specFile = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val endpoint = Endpoint(
            path = "/pets/find",
            method = "GET",
            responseStatus = 200,
            specification = specFile.canonicalPath,
            protocol = io.specmatic.license.core.SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI,
        )

        val input = OpenApiCoverageReportInput(
            configFilePath = specFile.canonicalPath,
            endpointsAPISet = true,
        )
        input.addEndpoints(listOf(endpoint), listOf(endpoint))
        input.addTestReportRecords(
            TestResultRecord(
                path = endpoint.path,
                method = endpoint.method,
                responseStatus = endpoint.responseStatus,
                responseContentType = endpoint.responseContentType,
                request = null,
                response = null,
                result = TestResult.Failed,
                isWip = true,
                specification = endpoint.specification,
                specType = SpecType.OPENAPI,
            )
        )

        val consoleReport = input.generate()
        val reportNode = ctrfReportNode(input, consoleReport)
        val executionOperations = reportNode["results"]["summary"]["extra"]["executionDetails"]
            .single()
            .get("operations")
            .toList()
        val tests = reportNode["results"]["tests"].toList()

        assertThat(consoleReport.totalCoveragePercentage).isEqualTo(100)
        assertThat(findTextValue(reportNode, "apiCoverage")).isEqualTo("100%")
        assertThat(tests.single()["extra"]["wip"].asBoolean()).isTrue()
        assertThat(executionOperations.single()["coverageStatus"].asText()).isEqualTo("WIP")
    }

    @Test
    fun `operation based ctrf generation should include not covered and missing in spec rows`() {
        val specFile = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val endpoints = endpointsFrom(specFile)
        val testedEndpoint = endpoints.first { it.path == "/pets/find" }
        val notCoveredEndpoint = endpoints.first { it.path != testedEndpoint.path }

        val coverage = OpenApiCoverage()
        coverage.addEndpoints(endpoints, endpoints)
        coverage.setEndpointsAPIFlag(true)
        coverage.addAPIs(listOf(API(method = "GET", path = "/pets/unknown")))
        coverage.addTestReportRecords(
            TestResultRecord(
                path = testedEndpoint.path,
                method = testedEndpoint.method,
                responseStatus = testedEndpoint.responseStatus,
                responseContentType = testedEndpoint.responseContentType,
                request = null,
                response = null,
                result = TestResult.Success,
                specification = testedEndpoint.specification,
                specType = SpecType.OPENAPI,
                requestContentType = testedEndpoint.requestContentType,
                operations = setOf(testedEndpoint.toOpenApiOperation()),
                actualResponseStatus = testedEndpoint.responseStatus,
                actualResponseContentType = testedEndpoint.responseContentType,
            )
        )

        val coverageReportOperations = coverage.generateReportOperations()
        val attemptedTestRecords = coverageReportOperations.flatMap { it.tests }.distinct()
        val specConfigs = coverageReportOperations.map { it.specConfig }.distinct()

        val ctrfReport = CtrfReportGenerator.generate(
            testResultRecords = attemptedTestRecords,
            coverageReportOperations = coverageReportOperations,
            specConfig = specConfigs,
            startTime = 0L,
            endTime = 0L,
            extra = emptyMap(),
            toolName = "Specmatic test",
        )

        val reportNode = ObjectMapper().readTree(ObjectMapper().writeValueAsString(ctrfReport))
        val executionOperations = reportNode["results"]["summary"]["extra"]["executionDetails"]
            .single()
            .get("operations")
            .toList()

        assertThat(executionOperations).anyMatch {
            it["path"].asText() == notCoveredEndpoint.path && it["coverageStatus"].asText() == CoverageStatus.NOT_TESTED.value
        }
        assertThat(executionOperations).anyMatch {
            it["path"].asText() == "/pets/unknown" && it["coverageStatus"].asText() == CoverageStatus.MISSING_IN_SPEC.value
        }
    }

    private fun findTextValue(node: com.fasterxml.jackson.databind.JsonNode, fieldName: String): String? {
        if (node.has(fieldName)) {
            return node[fieldName].asText()
        }

        val fields = node.fields()
        while (fields.hasNext()) {
            val child = fields.next().value
            val result = findTextValue(child, fieldName)
            if (result != null) {
                return result
            }
        }

        if (node.isArray) {
            node.forEach { child ->
                val result = findTextValue(child, fieldName)
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }

    private fun ctrfReportNode(input: OpenApiCoverageReportInput, consoleReport: io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport) =
        ObjectMapper().readTree(
            ObjectMapper().writeValueAsString(
                CtrfReportGenerator.generate(
                    testResultRecords = consoleReport.testResultRecords,
                    specConfig = input.ctrfSpecConfigs(),
                    startTime = 0L,
                    endTime = 0L,
                    extra = mapOf("apiCoverage" to "${consoleReport.totalCoveragePercentage}%"),
                    toolName = "Specmatic test",
                ) { ctrfTestResultRecords ->
                    ctrfTestResultRecords.filterIsInstance<TestResultRecord>().getCoverageStatus()
                }
            )
        )

    private fun endpointsFrom(specFile: File): List<Endpoint> {
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        return feature.scenarios.map { scenario ->
            Endpoint(
                path = convertPathParameterStyle(scenario.path),
                method = scenario.method,
                responseStatus = scenario.httpResponsePattern.status,
                soapAction = scenario.soapActionUnescaped,
                sourceProvider = scenario.sourceProvider,
                sourceRepository = scenario.sourceRepository,
                sourceRepositoryBranch = scenario.sourceRepositoryBranch,
                specification = specFile.canonicalPath,
                requestContentType = scenario.requestContentType,
                responseContentType = scenario.httpResponsePattern.headersPattern.contentType,
                protocol = scenario.protocol,
                specType = scenario.specType,
            )
        }
    }

    private fun applicationApisFrom(specFile: File): List<API> {
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        return feature.scenarios.map { scenario ->
            API(
                method = scenario.method,
                path = convertPathParameterStyle(scenario.path),
            )
        }.distinct()
    }

    private fun Endpoint.toOpenApiOperation() =
        io.specmatic.reporter.model.OpenAPIOperation(
            path = path,
            method = soapAction ?: method,
            contentType = requestContentType,
            responseCode = responseStatus,
            protocol = protocol,
            responseContentType = responseContentType,
        )
}
