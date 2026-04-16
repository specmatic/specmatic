package io.specmatic.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.SourceProvider
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.CtrfReportGenerator
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReport
import io.specmatic.test.utils.OpenApiCoverageBuilder.Companion.buildCoverage
import io.specmatic.test.utils.OpenApiCoverageVerifier.Companion.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class CtrfApiCoverageReportIntegrationTest {
    @Test
    fun `ctrf html report should include swagger discovered endpoints missing in the contract`() {
        val actualSpec = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val endpoints = endpointsFrom(actualSpec)

        val applicationSpec = File("src/test/resources/openapi/api_coverage/app_generated_openapi.json").canonicalFile
        val applicationApis = applicationApisFrom(applicationSpec)

        val coverage = buildCoverage {
            endpoints.forEach { endpoint -> specEndpoint(endpoint) }
            applicationApis.forEach { api -> applicationApi(method = api.method, path = api.path) }
            endpoints.forEach { endpoint ->
                testResult(
                    path = endpoint.path,
                    method = endpoint.method,
                    result = TestResult.Success,
                    responseCode = endpoint.responseStatus,
                    requestType = endpoint.requestContentType,
                    responseType = endpoint.responseContentType,
                    specification = actualSpec.canonicalPath,
                )
            }
        }

        val report = coverage.generate()
        report.verify {
            assertThat(consoleReport.coverageRows).anyMatch { it.path == "/pets/search" && it.remarks == CoverageStatus.MISSING_IN_SPEC }
            assertThat(consoleReport.coverageRows).anyMatch { it.path == "/pets/find" && it.remarks == CoverageStatus.COVERED }
        }

        val reportNode = ctrfReportNode(report)
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

        assertThat(matchingExecutionDetails.single()["type"].asText()).isEqualTo("filesystem")
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
        val ownersEndpoint = Endpoint(
            path = "/owners/{ownerId}",
            method = "GET",
            responseStatus = 200,
            specification = ownersSpec.canonicalPath,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI,
        )

        val coverage = buildCoverage {
            configFilePath(petsSpec.canonicalPath)
            specEndpoint(
                method = ownersEndpoint.method,
                path = ownersEndpoint.path,
                responseCode = ownersEndpoint.responseStatus,
                specification = ownersSpec.canonicalPath,
            )

            petsEndpoints.forEach { endpoint ->
                specEndpoint(
                    method = endpoint.method,
                    path = endpoint.path,
                    responseCode = endpoint.responseStatus,
                    requestType = endpoint.requestContentType,
                    responseType = endpoint.responseContentType,
                    specification = petsSpec.canonicalPath,
                )
            }

            applicationApi(method = "GET", path = "/pets/search")
            listOf(ownersEndpoint).plus(petsEndpoints).forEach { endpoint ->
                testResult(
                    path = endpoint.path,
                    method = endpoint.method,
                    responseCode = endpoint.responseStatus,
                    requestType = endpoint.requestContentType,
                    responseType = endpoint.responseContentType,
                    result = TestResult.Success,
                    specification = endpoint.specification ?: petsSpec.canonicalPath,
                )
            }
        }

        val report = coverage.generate()
        val reportNode = ctrfReportNode(report)
        val executionDetails = reportNode["results"]["summary"]["extra"]["executionDetails"].toList()
        val petsExecutionDetail = executionDetails.single { it["specification"].asText() == petsSpec.canonicalPath }
        val ownersExecutionDetail = executionDetails.single { it["specification"].asText() == ownersSpec.canonicalPath }
        val petsOperationPaths = petsExecutionDetail["operations"].map { it["path"].asText() }
        val ownersOperationPaths = ownersExecutionDetail["operations"].map { it["path"].asText() }

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
        val coverage = buildCoverage {
            configFilePath(specFile.canonicalPath)
            specEndpoint(method = "GET", path = "/pets/search", responseCode = 200, specification = specFile.canonicalPath)
            applicationApi(method = "GET", path = "/pets")
            testResult(
                path = "/pets/search",
                method = "GET",
                responseCode = 200,
                result = TestResult.Failed,
                actualResponseCode = 422,
                specification = specFile.canonicalPath,
            )
        }

        val report = coverage.generate()
        val reportNode = ctrfReportNode(report)
        assertThat(report.totalCoveragePercentage).isEqualTo(0)
        assertThat(findTextValue(reportNode, "apiCoverage")).isEqualTo("0%")
        assertThat(reportNode["results"]["tests"].map { it["name"].asText() }).anyMatch { it.contains("/pets/search") }
    }

    @Test
    fun `ctrf report should preserve wip coverage semantics from console report`() {
        val specFile = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val coverage = buildCoverage {
            configFilePath(specFile.canonicalPath)
            specEndpoint(method = "GET", path = "/pets/find", responseCode = 200, specification = specFile.canonicalPath)
            testResult(
                path = "/pets/find",
                method = "GET",
                responseCode = 200,
                result = TestResult.Failed,
                isWip = true,
                specification = specFile.canonicalPath,
            )
        }

        val report = coverage.generate()
        val reportNode = ctrfReportNode(report)
        val executionOperations = reportNode["results"]["summary"]["extra"]["executionDetails"].single().get("operations").toList()
        val tests = reportNode["results"]["tests"].toList()

        assertThat(report.totalCoveragePercentage).isEqualTo(100)
        assertThat(findTextValue(reportNode, "apiCoverage")).isEqualTo("100%")
        assertThat(tests.single()["extra"]["wip"].asBoolean()).isTrue()
        assertThat(executionOperations.single()["coverageStatus"].asText()).isEqualTo("covered")
        assertThat(executionOperations.single()["qualifiers"].get(0).asText()).isEqualTo("wip")
    }

    @Test
    fun `ctrf report should include undeclared response qualifier at test level`() {
        val specFile = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val coverage = buildCoverage {
            configFilePath(specFile.canonicalPath)
            specEndpoint(method = "GET", path = "/pets/find", responseCode = 200, specification = specFile.canonicalPath)
            testResult(
                TestResultRecord(
                    path = "/pets/find",
                    method = "GET",
                    responseStatus = 200,
                    request = null,
                    response = null,
                    result = TestResult.Failed,
                    specification = specFile.canonicalPath,
                    specType = SpecType.OPENAPI,
                    actualResponseStatus = 500,
                    isResponseInSpecification = false,
                )
            )
        }

        val reportNode = ctrfReportNode(coverage.generate())
        val testQualifiers = reportNode["results"]["tests"].single()["extra"]["qualifiers"].map { it.asText() }
        val operationQualifiers = reportNode["results"]["summary"]["extra"]["executionDetails"].single()["operations"].single()["qualifiers"].map { it.asText() }
        assertThat(testQualifiers).contains("undeclaredResponse")
        assertThat(operationQualifiers).isEmpty()
    }

    @Test
    fun `operation based ctrf generation should include not covered and missing in spec rows`() {
        val specFile = File("src/test/resources/openapi/api_coverage/openapi.yaml").canonicalFile
        val endpoints = endpointsFrom(specFile)
        val testedEndpoint = endpoints.first { it.path == "/pets/find" }
        val notCoveredEndpoint = endpoints.first { it.path != testedEndpoint.path }

        val coverage = buildCoverage {
            configFilePath(specFile.canonicalPath)
            endpoints.forEach { endpoint -> specEndpoint(endpoint) }
            applicationApi(method = "GET", path = "/pets/unknown")
            testResult(
                path = testedEndpoint.path,
                method = testedEndpoint.method,
                responseCode = testedEndpoint.responseStatus,
                requestType = testedEndpoint.requestContentType,
                responseType = testedEndpoint.responseContentType,
                result = TestResult.Success,
                actualResponseCode = testedEndpoint.responseStatus,
                actualResponseType = testedEndpoint.responseContentType,
                specification = testedEndpoint.specification ?: specFile.canonicalPath,
            )
        }

        val coverageReportOperations = coverage.generate().coverageOperations
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
        val executionOperations = reportNode["results"]["summary"]["extra"]["executionDetails"].single().get("operations").toList()

        assertThat(executionOperations).anyMatch {
            it["path"].asText() == notCoveredEndpoint.path && it["coverageStatus"].asText() == CoverageStatus.NOT_TESTED.value
        }
        assertThat(executionOperations).anyMatch {
            it["path"].asText() == "/pets/unknown" && it["coverageStatus"].asText() == CoverageStatus.MISSING_IN_SPEC.value
        }
    }

    private fun findTextValue(node: JsonNode, fieldName: String): String? {
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

    private fun ctrfReportNode(report: OpenApiCoverageReport): JsonNode =
        ObjectMapper().readTree(
            ObjectMapper().writeValueAsString(
                CtrfReportGenerator.generate(
                    endTime = 0L,
                    startTime = 0L,
                    toolName = "Specmatic test",
                    specConfig = report.getSpecConfigs(),
                    testResultRecords = report.testResultRecords,
                    coverageReportOperations = report.coverageOperations,
                    extra = mapOf("apiCoverage" to "${report.totalCoveragePercentage}%"),
                )
            )
        )

    private fun endpointsFrom(specFile: File): List<Endpoint> {
        val feature = parseContractFileToFeature(specFile, sourceProvider = SourceProvider.filesystem.name)
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
            API(method = scenario.method, path = convertPathParameterStyle(scenario.path))
        }.distinct()
    }
}
