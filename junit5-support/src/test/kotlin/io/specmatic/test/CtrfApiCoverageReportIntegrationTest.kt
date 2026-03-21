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
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class CtrfApiCoverageReportIntegrationTest {
    @Test
    fun `ctrf html report should include swagger discovered endpoints missing in the contract`() {
        val actualSpec = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val applicationSpec = File("src/test/resources/openapi/api_coverage/app_generated_openapi.json").canonicalFile

        val input = OpenApiCoverageReportInput(
            configFilePath = actualSpec.canonicalPath,
            endpointsAPISet = true,
        )

        val endpoints = endpointsFrom(actualSpec)
        input.addEndpoints(endpoints, endpoints)
        input.addAPIs(applicationApisFrom(applicationSpec))

        endpoints.forEach { endpoint ->
            input.addTestReportRecords(
                TestResultRecord(
                    path = endpoint.path,
                    method = endpoint.method,
                    responseStatus = endpoint.responseStatus,
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
        val executionOperations = reportNode["results"]["summary"]["extra"]["executionDetails"]
            .flatMap { it["operations"].toList() }
        val executionOperationPaths = executionOperations.map { it["path"].asText() }

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
}
