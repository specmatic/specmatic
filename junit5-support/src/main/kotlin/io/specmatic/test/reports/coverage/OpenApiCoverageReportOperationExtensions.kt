package io.specmatic.test.reports.coverage

import io.specmatic.core.report.OpenApiCoverageReportOperation

internal fun OpenApiCoverageReportOperation.isExcludedFromRun(): Boolean {
    return reasons.orEmpty().any { reason ->
        reason.title == "Excluded from Run" || reason.summary == "Excluded from Run"
    }
}
