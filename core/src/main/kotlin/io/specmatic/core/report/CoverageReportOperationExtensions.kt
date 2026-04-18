package io.specmatic.core.report

import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord
import kotlin.math.roundToInt

fun List<CoverageReportOperation<OpenAPIOperation, TestResultRecord>>.calculateCoverage(): Int {
    val coverageReportOperations = this.filter { it.eligibleForCoverage }
    if (coverageReportOperations.isEmpty()) {
        return 0
    }

    val coveredOperationCount = coverageReportOperations.count { it.coverageStatus == CoverageStatus.COVERED }
    return ((coveredOperationCount.toDouble() / coverageReportOperations.size) * 100).roundToInt()
}

fun List<CoverageReportOperation<OpenAPIOperation, TestResultRecord>>.calculateAbsoluteCoverage(): Int {
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
