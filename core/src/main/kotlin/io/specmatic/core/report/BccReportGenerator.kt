package io.specmatic.core.report

import io.specmatic.reporter.ctrf.model.BccCoverageReportOperation
import io.specmatic.reporter.ctrf.model.BaseBccCoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.bcc.ChangeStatus
import io.specmatic.reporter.internal.dto.operation.APIOperation
import io.specmatic.reporter.model.BackwardCompatibilityResult

class BccReportGenerator {
    fun generateReportOperations(tests: List<CtrfBackwardCompatibilityRecord>): List<BaseBccCoverageReportOperation> {
        return tests.operationSources().groupByOperation().map { (groupKey, sources) ->
            reportOperationFrom(groupKey, sources)
        }
    }

    private fun reportOperationFrom(groupKey: BccOperationGroupKey, sources: List<BccOperationSource>): BaseBccCoverageReportOperation {
        val tests = sources.map { it.test }
        return BccCoverageReportOperation(
            tests = tests,
            operation = groupKey.operation,
            specConfig = groupKey.specConfig,
            qualifiers = operationQualifiersFrom(tests),
            changeStatus = operationChangeStatus(tests),
            backwardCompatibilityResult = backwardCompatibilityResultFrom(tests),
        )
    }

    private fun operationQualifiersFrom(tests: List<CtrfBackwardCompatibilityRecord>): List<CtrfOperationQualifiers> {
        return tests.flatMap { it.operationQualifiers }.distinct()
    }

    private fun operationChangeStatus(tests: List<CtrfBackwardCompatibilityRecord>): ChangeStatus {
        return when {
            tests.any { it.changeStatus == ChangeStatus.CHANGED } -> ChangeStatus.CHANGED
            else -> ChangeStatus.UNCHANGED
        }
    }

    private fun backwardCompatibilityResultFrom(tests: List<CtrfBackwardCompatibilityRecord>): BackwardCompatibilityResult {
        return when {
            tests.any { it.result == BackwardCompatibilityResult.Incompatible } -> BackwardCompatibilityResult.Incompatible
            else -> BackwardCompatibilityResult.Compatible
        }
    }

    private fun List<CtrfBackwardCompatibilityRecord>.operationSources(): List<BccOperationSource> {
        return flatMap { test ->
            test.operations.map { operation ->
                val operationGroup = BccOperationGroupKey(specConfig = test.toCtrfSpecConfig(), operation = operation)
                BccOperationSource(group = operationGroup, test = test)
            }
        }
    }

    private fun List<BccOperationSource>.groupByOperation(): Map<BccOperationGroupKey, List<BccOperationSource>> {
        return groupBy { source ->
            BccOperationGroupKey(specConfig = source.group.specConfig, operation = source.group.operation)
        }
    }

    private fun CtrfBackwardCompatibilityRecord.toCtrfSpecConfig(): CtrfSpecConfig {
        return CtrfSpecConfig(
            repository = repository,
            specType = specType.value,
            branch = branch ?: "main",
            specification = specification,
            protocol = operations.first().protocol.key,
        )
    }

    private data class BccOperationGroupKey(val specConfig: CtrfSpecConfig, val operation: APIOperation)
    private data class BccOperationSource(val group: BccOperationGroupKey, val test: CtrfBackwardCompatibilityRecord)
}
