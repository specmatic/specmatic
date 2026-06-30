package io.specmatic.core.report

import io.specmatic.reporter.ctrf.model.BaseCoverageReportOperation
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import kotlin.math.roundToInt

fun List<BaseCoverageReportOperation>.coveredOperationsCount(): Int {
    return this.count { operation ->
        operation.eligibleForCoverage && operation.coverageStatus == CoverageStatus.COVERED
    }
}

fun List<BaseCoverageReportOperation>.totalOperationForCoverageIncludingFilters(): List<BaseCoverageReportOperation> {
    return this.filter { it.eligibleForCoverage }
}

fun List<BaseCoverageReportOperation>.totalOperationsForCoverage(): List<BaseCoverageReportOperation> {
    return this.filter { operation ->
        operation.eligibleForCoverage || operation.omittedStatus == OmittedStatus.EXCLUDED
    }
}

fun List<BaseCoverageReportOperation>.calculateCoverage(): Int {
    val coverageReportOperations = this.totalOperationForCoverageIncludingFilters()
    if (coverageReportOperations.isEmpty()) {
        return 0
    }

    val coveredOperationCount = coverageReportOperations.coveredOperationsCount()
    return ((coveredOperationCount.toDouble() / coverageReportOperations.size) * 100).roundToInt()
}

fun List<BaseCoverageReportOperation>.calculateAbsoluteCoverage(): Int {
    val denominatorOperations = this.totalOperationsForCoverage()

    if (denominatorOperations.isEmpty()) {
        return 0
    }

    val coveredOperationCount = denominatorOperations.coveredOperationsCount()

    return ((coveredOperationCount.toDouble() / denominatorOperations.size) * 100).roundToInt()
}
