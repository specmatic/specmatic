package io.specmatic.test.reports.coverage

import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

typealias OpenApiCoverageReportOperation =
    CoverageReportOperation<OpenAPIOperation, TestResultRecord>
