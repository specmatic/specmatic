package io.specmatic.core.report

import io.specmatic.reporter.ctrf.model.BaseCoverageReportOperation
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import kotlin.math.roundToInt

fun List<BaseCoverageReportOperation>.calculateCoverage(): Int {
    val coverageReportOperations = this.filter { it.eligibleForCoverage }
    if (coverageReportOperations.isEmpty()) {
        return 0
    }

    val coveredOperationCount = coverageReportOperations.count { it.coverageStatus == CoverageStatus.COVERED }
    return ((coveredOperationCount.toDouble() / coverageReportOperations.size) * 100).roundToInt()
}

fun List<BaseCoverageReportOperation>.calculateAbsoluteCoverage(): Int {
    val denominatorOperations = this.filter { operation ->
        operation.eligibleForCoverage || operation.omittedStatus == OmittedStatus.EXCLUDED
    }

    if (denominatorOperations.isEmpty()) {
        return 0
    }

    val coveredOperationCount = denominatorOperations.count { operation ->
        operation.eligibleForCoverage && operation.coverageStatus == CoverageStatus.COVERED
    }

    return ((coveredOperationCount.toDouble() / denominatorOperations.size) * 100).roundToInt()
}
