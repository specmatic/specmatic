package io.specmatic.core.report

import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.test.TestResultRecord

interface SpecmaticAfterAllHook {
    fun generateReport(
        testResultRecords: List<TestResultRecord>?,
        coverage: Int,
        startTime: Long,
        endTime: Long,
        specConfigs: List<CtrfSpecConfig>,
        reportFilePath: String
    )
}