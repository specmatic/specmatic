package io.specmatic.core.report

import io.specmatic.reporter.ctrf.CoverageReportSpecification
import io.specmatic.reporter.ctrf.model.BaseCoverageReportOperation
import io.specmatic.reporter.ctrf.model.CoverageMetrics

fun List<BaseCoverageReportOperation>.toCoverageReportSpecifications(
): List<CoverageReportSpecification> {
    val specConfigs = map { it.specConfig }.distinct()

    return specConfigs.distinct().map { specConfig ->
        val specCoverageOperations = filter { it.specConfig == specConfig }

        CoverageReportSpecification(
            specConfig = specConfig,
            coverageReportOperations = specCoverageOperations,
            coverageMetrics = specCoverageOperations.toCoverageMetrics()
        )
    }
}

fun List<BaseCoverageReportOperation>.toCoverageMetrics(): CoverageMetrics? {
    if (isEmpty()) return null

    val coveredOperations = coveredOperationsCount()
    val totalOperationsWithFilters = totalOperationForCoverageIncludingFilters().size
    val totalOperations = totalOperationsForCoverage().size

    return CoverageMetrics(
        apiCoverage = calculateCoverage(),
        absoluteCoverage = calculateAbsoluteCoverage(),
        coveredOperations = coveredOperations,
        totalOperationsWithFilters = totalOperationsWithFilters,
        totalOperations = totalOperations,
    )
}
