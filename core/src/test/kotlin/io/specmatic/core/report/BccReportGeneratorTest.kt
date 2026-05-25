package io.specmatic.core.report

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.internal.dto.bcc.ChangeStatus
import io.specmatic.reporter.internal.dto.operation.APIOperation
import io.specmatic.reporter.model.BackwardCompatibilityResult
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.collections.single

class BccReportGeneratorTest {
    private val generator = BccReportGenerator()

    @Test
    fun `should return empty list when there are no records`() {
        val reportOperations = generator.generateReportOperations(emptyList())
        assertThat(reportOperations).isEmpty()
    }

    @Test
    fun `should group records by openapi operation and spec config`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val getOrders = openApiOperation(path = "/orders", method = "GET", responseCode = 200)

        val firstRecord = testRecord(
            specification = "specs/orders.yaml",
            operations = setOf(createOrder, getOrders),
            result = BackwardCompatibilityResult.Compatible,
            operationQualifiers = listOf(CtrfOperationQualifiers.WIP),
        )

        val secondRecord = testRecord(
            specification = "specs/orders.yaml",
            operations = setOf(createOrder),
            result = BackwardCompatibilityResult.Incompatible,
        )

        val thirdRecord = testRecord(
            specification = "specs/payments.yaml",
            operations = setOf(createOrder),
            result = BackwardCompatibilityResult.Compatible,
        )

        val reportOperations = generator.generateReportOperations(listOf(firstRecord, secondRecord, thirdRecord))
        assertThat(reportOperations).hasSize(3)

        val groupedCreateOrder = reportOperations.single { it.operation == createOrder && it.specConfig.specification == "specs/orders.yaml" }
        assertThat(groupedCreateOrder.tests).containsExactly(firstRecord, secondRecord)
        assertThat(groupedCreateOrder.qualifiers).containsExactly(CtrfOperationQualifiers.WIP, CtrfOperationQualifiers.CHANGED)
        assertThat(groupedCreateOrder.status).isEqualTo(BackwardCompatibilityResult.Incompatible)

        val groupedGetOrders = reportOperations.single { it.operation == getOrders && it.specConfig.specification == "specs/orders.yaml" }
        assertThat(groupedGetOrders.tests).containsExactly(firstRecord)
        assertThat(groupedGetOrders.qualifiers).containsExactly(CtrfOperationQualifiers.WIP, CtrfOperationQualifiers.CHANGED)
        assertThat(groupedGetOrders.status).isEqualTo(BackwardCompatibilityResult.Compatible)

        val groupedPayments = reportOperations.single { it.operation == createOrder && it.specConfig.specification == "specs/payments.yaml" }
        assertThat(groupedPayments.qualifiers).containsExactly(CtrfOperationQualifiers.CHANGED)
        assertThat(groupedPayments.tests).containsExactly(thirdRecord)
        assertThat(groupedPayments.status).isEqualTo(BackwardCompatibilityResult.Compatible)
    }

    @Test
    fun `should create separate groups when branch repository or specification differ`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)

        val mainBranchRecord = testRecord(
            branch = "main",
            repository = "https://github.com/example/orders.git",
            specification = "specs/orders.yaml",
            operations = setOf(createOrder),
        )

        val featureBranchRecord = testRecord(
            branch = "feature-1",
            repository = "https://github.com/example/orders.git",
            specification = "specs/orders.yaml",
            operations = setOf(createOrder),
        )

        val otherRepoRecord = testRecord(
            branch = "main",
            repository = "https://github.com/example/payments.git",
            specification = "specs/orders.yaml",
            operations = setOf(createOrder),
        )

        val otherSpecRecord = testRecord(
            branch = "main",
            repository = "https://github.com/example/orders.git",
            specification = "specs/orders-v2.yaml",
            operations = setOf(createOrder),
        )

        val reportOperations = generator.generateReportOperations(listOf(mainBranchRecord, featureBranchRecord, otherRepoRecord, otherSpecRecord))
        assertThat(reportOperations).hasSize(4)
        assertThat(reportOperations.map { it.tests.single() }).containsExactly(
            mainBranchRecord,
            featureBranchRecord,
            otherRepoRecord,
            otherSpecRecord,
        )
    }

    @Test
    fun `should merge and deduplicate operation qualifiers across grouped records`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)

        val firstRecord = testRecord(
            operations = setOf(createOrder),
            operationQualifiers = listOf(CtrfOperationQualifiers.WIP, CtrfOperationQualifiers.WIP),
        )

        val secondRecord = testRecord(
            operations = setOf(createOrder),
            operationQualifiers = listOf(CtrfOperationQualifiers.WIP),
        )

        val reportOperation = generator.generateReportOperations(listOf(firstRecord, secondRecord)).single()
        assertThat(reportOperation.qualifiers).containsExactly(CtrfOperationQualifiers.WIP, CtrfOperationQualifiers.CHANGED)
    }

    @Test
    fun `should add changed qualifier when operation has changes`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val reportOperation = generator.generateReportOperations(
            listOf(
                testRecord(operations = setOf(createOrder), changeStatus = ChangeStatus.UNCHANGED),
                testRecord(operations = setOf(createOrder), changeStatus = ChangeStatus.CHANGED),
            )
        ).single()

        assertThat(reportOperation.qualifiers).containsExactly(CtrfOperationQualifiers.CHANGED)
    }

    @Test
    fun `should omit changed qualifier when operation is unchanged`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val reportOperation = generator.generateReportOperations(
            listOf(
                testRecord(
                    operations = setOf(createOrder),
                    changeStatus = ChangeStatus.UNCHANGED,
                    operationQualifiers = listOf(CtrfOperationQualifiers.WIP),
                ),
            )
        ).single()

        assertThat(reportOperation.qualifiers).containsExactly(CtrfOperationQualifiers.WIP)
        assertThat(reportOperation.qualifiers).doesNotContain(CtrfOperationQualifiers.CHANGED)
    }

    @Test
    fun `should mark operation as changed when any grouped record has changes`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val reportOperation = generator.generateReportOperations(
            listOf(
                testRecord(operations = setOf(createOrder), changeStatus = ChangeStatus.UNCHANGED),
                testRecord(operations = setOf(createOrder), changeStatus = ChangeStatus.CHANGED),
            )
        ).single()

        assertThat(reportOperation.qualifiers).contains(CtrfOperationQualifiers.CHANGED)
    }

    @Test
    fun `should mark operation as unchanged when all grouped records have no changes`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val reportOperation = generator.generateReportOperations(
            listOf(
                testRecord(operations = setOf(createOrder), changeStatus = ChangeStatus.UNCHANGED),
                testRecord(operations = setOf(createOrder), changeStatus = ChangeStatus.UNCHANGED),
            )
        ).single()

        assertThat(reportOperation.qualifiers).doesNotContain(CtrfOperationQualifiers.CHANGED)
    }

    @Test
    fun `should evaluate has changes independently per grouped operation`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val getOrders = openApiOperation(path = "/orders", method = "GET", responseCode = 200)
        val secondRecord = testRecord(operations = setOf(getOrders), changeStatus = ChangeStatus.UNCHANGED)
        val thirdRecord = testRecord(operations = setOf(createOrder), changeStatus = ChangeStatus.UNCHANGED)
        val firstRecord = testRecord(operations = setOf(createOrder, getOrders), changeStatus = ChangeStatus.CHANGED)

        val reportOperations = generator.generateReportOperations(listOf(firstRecord, secondRecord, thirdRecord))
        val createOrderReport = reportOperations.single { it.operation == createOrder }
        val getOrdersReport = reportOperations.single { it.operation == getOrders }

        assertThat(createOrderReport.qualifiers).contains(CtrfOperationQualifiers.CHANGED)
        assertThat(getOrdersReport.qualifiers).contains(CtrfOperationQualifiers.CHANGED)
    }

    @Test
    fun `should remain compatible when all grouped records are compatible`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val reportOperation = generator.generateReportOperations(
            listOf(
                testRecord(operations = setOf(createOrder), result = BackwardCompatibilityResult.Compatible),
                testRecord(operations = setOf(createOrder), result = BackwardCompatibilityResult.Compatible),
            )
        )

        assertThat(reportOperation.single().status).isEqualTo(BackwardCompatibilityResult.Compatible)
    }

    @Test
    fun `should become incompatible when any grouped record is incompatible`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val reportOperation = generator.generateReportOperations(
            listOf(
                testRecord(operations = setOf(createOrder), result = BackwardCompatibilityResult.Compatible),
                testRecord(operations = setOf(createOrder), result = BackwardCompatibilityResult.Incompatible),
            )
        )

        assertThat(reportOperation.single().status).isEqualTo(BackwardCompatibilityResult.Incompatible)
    }

    @Test
    fun `should map openapi record fields into spec config`() {
        val createOrder = openApiOperation(path = "/orders", method = "POST", responseCode = 201)
        val reportOperation = generator.generateReportOperations(
            listOf(
                testRecord(
                    branch = null,
                    repository = "https://github.com/example/orders.git",
                    specification = "specs/orders.yaml",
                    specType = SpecType.OPENAPI,
                    operations = setOf(createOrder),
                )
            )
        ).single()

        assertThat(reportOperation.specConfig.protocol).isEqualTo(SpecmaticProtocol.HTTP.key)
        assertThat(reportOperation.specConfig.specType).isEqualTo(SpecType.OPENAPI.value)
        assertThat(reportOperation.specConfig.specification).isEqualTo("specs/orders.yaml")
        assertThat(reportOperation.specConfig.repository).isEqualTo("https://github.com/example/orders.git")
        assertThat(reportOperation.specConfig.branch).isEqualTo("main")
    }

    private fun openApiOperation(
        path: String,
        method: String,
        responseCode: Int,
        contentType: String? = "application/json",
        responseContentType: String? = "application/json",
    ): OpenAPIOperation {
        return OpenAPIOperation(
            path = path,
            method = method,
            contentType = contentType,
            responseCode = responseCode,
            protocol = SpecmaticProtocol.HTTP,
            responseContentType = responseContentType,
        )
    }

    private fun testRecord(
        id: UUID = UUID.randomUUID(),
        branch: String? = "feature-1",
        operations: Set<APIOperation>,
        tags: List<String> = emptyList(),
        specType: SpecType = SpecType.OPENAPI,
        specification: String = "specs/openapi.yaml",
        repository: String? = "https://github.com/example/repo.git",
        changeStatus: ChangeStatus = ChangeStatus.CHANGED,
        operationQualifiers: List<CtrfOperationQualifiers> = emptyList(),
        result: BackwardCompatibilityResult = BackwardCompatibilityResult.Compatible,
    ): CtrfBackwardCompatibilityRecord {
        val effectiveQualifiers = buildList {
            addAll(operationQualifiers)
            if (changeStatus == ChangeStatus.CHANGED) add(CtrfOperationQualifiers.CHANGED)
        }
        return object : CtrfBackwardCompatibilityRecord {
            override val id: UUID = id
            override val duration: Long = 100
            override val message: String = ""
            override val branch: String? = branch
            override val name: String = "bcc-test"
            override val tags: List<String> = tags
            override val specType: SpecType = specType
            override val repository: String? = repository
            override val specification: String = specification
            override val operations: Set<APIOperation> = operations
            override val result: BackwardCompatibilityResult = result
            override val changeStatus: ChangeStatus = changeStatus
            override val operationQualifiers: List<CtrfOperationQualifiers> = effectiveQualifiers
        }
    }
}
