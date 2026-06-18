package io.specmatic.core.report

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.BaseCoverageReportOperation
import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestMetadata
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.internal.dto.operation.APIOperation
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

private class TestCtrfTestResultRecord(
    override val id: UUID = UUID.randomUUID(),
    override val duration: Long = 100,
    override val specification: String? = "spec1.yaml",
    override val rawStatus: String? = "PASSED",
    override val testType: String = "unit",
    override val result: TestResult = TestResult.Success,
    override val isWip: Boolean = false,
    override val repository: String? = "https://github.com/example/repo.git",
    override val branch: String? = "main",
    override val operations: Set<APIOperation>,
    override val specType: SpecType = SpecType.OPENAPI,
) : CtrfTestResultRecord {
    override fun testName() = "test"

    override fun testMessage() = ""

    override fun tags() = emptyList<String>()

    override fun extraFields() = CtrfTestMetadata(input = "", inputTime = 0L)
}

class CtrfSpecExecutionDetailExtensionsTest {
    @Test
    fun `should calculate contract metrics per spec`() {
        val specConfig = CtrfSpecConfig(
            protocol = SpecmaticProtocol.HTTP.key,
            specType = SpecType.OPENAPI.value,
            specification = "specs/order.yaml",
        )
        val coveredOperation = openApiOperation(path = "/orders", method = "GET")
        val excludedOperation = openApiOperation(path = "/internal/orders", method = "GET")
        val testRecord = TestCtrfTestResultRecord(
            specification = "specs/order.yaml",
            operations = setOf(coveredOperation),
        )

        val coverageReportSpecifications = listOf<BaseCoverageReportOperation>(
            CoverageReportOperation(
                operation = coveredOperation,
                specConfig = specConfig,
                tests = listOf(testRecord),
                coverageStatus = CoverageStatus.COVERED,
                eligibleForCoverage = true,
            ),
            CoverageReportOperation(
                operation = excludedOperation,
                specConfig = specConfig,
                coverageStatus = CoverageStatus.NOT_COVERED,
                eligibleForCoverage = false,
                omittedStatus = OmittedStatus.EXCLUDED,
            )
        ).toCoverageReportSpecifications(listOf(specConfig))

        assertThat(coverageReportSpecifications.single().coverageMetrics?.apiCoverage).isEqualTo(100)
        assertThat(coverageReportSpecifications.single().coverageMetrics?.absoluteCoverage).isEqualTo(50)
    }

    @Test
    fun `should calculate coverage metrics per spec for stub reports`() {
        val specConfig = CtrfSpecConfig(
            protocol = SpecmaticProtocol.HTTP.key,
            specType = SpecType.OPENAPI.value,
            specification = "specs/order.yaml",
        )
        val coveredOperation = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val uncoveredOperation = openApiOperation(path = "/orders", method = "GET")
        val stubTestRecord = TestCtrfTestResultRecord(
            specification = "specs/order.yaml",
            testType = "Stub",
            operations = setOf(coveredOperation),
        )

        val coverageReportSpecifications = listOf<BaseCoverageReportOperation>(
            CoverageReportOperation(
                operation = coveredOperation,
                specConfig = specConfig,
                tests = listOf(stubTestRecord),
                coverageStatus = CoverageStatus.COVERED,
                eligibleForCoverage = true,
            ),
            CoverageReportOperation(
                operation = uncoveredOperation,
                specConfig = specConfig,
                coverageStatus = CoverageStatus.NOT_COVERED,
                eligibleForCoverage = true,
            )
        ).toCoverageReportSpecifications(listOf(specConfig))

        assertThat(coverageReportSpecifications.single().coverageMetrics?.apiCoverage).isEqualTo(50)
        assertThat(coverageReportSpecifications.single().coverageMetrics?.absoluteCoverage).isEqualTo(50)
    }

    @Test
    fun `should group operations by spec and calculate metrics independently`() {
        val ordersSpecConfig = CtrfSpecConfig(
            protocol = SpecmaticProtocol.HTTP.key,
            specType = SpecType.OPENAPI.value,
            specification = "specs/orders.yaml",
        )
        val paymentsSpecConfig = CtrfSpecConfig(
            protocol = SpecmaticProtocol.HTTP.key,
            specType = SpecType.OPENAPI.value,
            specification = "specs/payments.yaml",
        )

        val ordersCoveredOperation = openApiOperation(path = "/orders", method = "GET")
        val ordersUncoveredOperation = openApiOperation(path = "/orders/{id}", method = "GET")
        val paymentsCoveredOperation = openApiOperation(path = "/payments", method = "POST", responseCode = 201)
        val paymentsExcludedOperation = openApiOperation(path = "/internal/payments", method = "GET")

        val ordersTestRecord = TestCtrfTestResultRecord(
            specification = "specs/orders.yaml",
            operations = setOf(ordersCoveredOperation),
        )
        val paymentsTestRecord = TestCtrfTestResultRecord(
            specification = "specs/payments.yaml",
            operations = setOf(paymentsCoveredOperation),
        )

        val coverageReportSpecifications = listOf<BaseCoverageReportOperation>(
            CoverageReportOperation(
                operation = ordersCoveredOperation,
                specConfig = ordersSpecConfig,
                tests = listOf(ordersTestRecord),
                coverageStatus = CoverageStatus.COVERED,
                eligibleForCoverage = true,
            ),
            CoverageReportOperation(
                operation = ordersUncoveredOperation,
                specConfig = ordersSpecConfig,
                coverageStatus = CoverageStatus.NOT_COVERED,
                eligibleForCoverage = true,
            ),
            CoverageReportOperation(
                operation = paymentsCoveredOperation,
                specConfig = paymentsSpecConfig,
                tests = listOf(paymentsTestRecord),
                coverageStatus = CoverageStatus.COVERED,
                eligibleForCoverage = true,
            ),
            CoverageReportOperation(
                operation = paymentsExcludedOperation,
                specConfig = paymentsSpecConfig,
                coverageStatus = CoverageStatus.NOT_COVERED,
                eligibleForCoverage = false,
                omittedStatus = OmittedStatus.EXCLUDED,
            ),
        ).toCoverageReportSpecifications(listOf(ordersSpecConfig, paymentsSpecConfig))

        val groupedBySpec = coverageReportSpecifications.associateBy { it.specConfig.specification }
        val ordersMetrics = groupedBySpec.getValue("specs/orders.yaml")
        val paymentsMetrics = groupedBySpec.getValue("specs/payments.yaml")

        assertThat(ordersMetrics.coverageReportOperations).hasSize(2)
        assertThat(ordersMetrics.coverageReportOperations.map { it.specConfig.specification }).containsOnly("specs/orders.yaml")
        assertThat(ordersMetrics.coverageMetrics?.apiCoverage).isEqualTo(50)
        assertThat(ordersMetrics.coverageMetrics?.absoluteCoverage).isEqualTo(50)

        assertThat(paymentsMetrics.coverageReportOperations).hasSize(2)
        assertThat(paymentsMetrics.coverageReportOperations.map { it.specConfig.specification }).containsOnly("specs/payments.yaml")
        assertThat(paymentsMetrics.coverageMetrics?.apiCoverage).isEqualTo(100)
        assertThat(paymentsMetrics.coverageMetrics?.absoluteCoverage).isEqualTo(50)
    }

    @Test
    fun `should leave coverage metrics null when a spec has no matching operations`() {
        val ordersSpecConfig = CtrfSpecConfig(
            protocol = SpecmaticProtocol.HTTP.key,
            specType = SpecType.OPENAPI.value,
            specification = "specs/orders.yaml",
        )
        val paymentsSpecConfig = CtrfSpecConfig(
            protocol = SpecmaticProtocol.HTTP.key,
            specType = SpecType.OPENAPI.value,
            specification = "specs/payments.yaml",
        )

        val ordersCoveredOperation = openApiOperation(path = "/orders", method = "GET")
        val ordersTestRecord = TestCtrfTestResultRecord(
            specification = "specs/orders.yaml",
            operations = setOf(ordersCoveredOperation),
        )

        val coverageReportSpecifications = listOf<BaseCoverageReportOperation>(
            CoverageReportOperation(
                operation = ordersCoveredOperation,
                specConfig = ordersSpecConfig,
                tests = listOf(ordersTestRecord),
                coverageStatus = CoverageStatus.COVERED,
                eligibleForCoverage = true,
            )
        ).toCoverageReportSpecifications(listOf(ordersSpecConfig, paymentsSpecConfig))

        val groupedBySpec = coverageReportSpecifications.associateBy { it.specConfig.specification }
        val ordersMetrics = groupedBySpec.getValue("specs/orders.yaml")
        val paymentsMetrics = groupedBySpec.getValue("specs/payments.yaml")

        assertThat(ordersMetrics.coverageReportOperations).hasSize(1)
        assertThat(ordersMetrics.coverageMetrics?.apiCoverage).isEqualTo(100)
        assertThat(ordersMetrics.coverageMetrics?.absoluteCoverage).isEqualTo(100)

        assertThat(paymentsMetrics.coverageReportOperations).isEmpty()
        assertThat(paymentsMetrics.coverageMetrics).isNull()
    }

    private fun openApiOperation(
        path: String,
        method: String,
        responseCode: Int = 200,
    ) = OpenAPIOperation(
        path = path,
        method = method,
        responseCode = responseCode,
        protocol = SpecmaticProtocol.HTTP,
    )
}
