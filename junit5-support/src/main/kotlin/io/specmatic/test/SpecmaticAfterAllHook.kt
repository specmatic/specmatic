package io.specmatic.test

import io.specmatic.reporter.ctrf.model.CtrfSpecConfig

interface SpecmaticAfterAllHook {
    fun onAfterAllTests(
        testResultRecords: List<TestResultRecord>?,
        startTime: Long,
        endTime: Long,
        coverage: Int,
        specConfigs: List<CtrfSpecConfig>
    )
}