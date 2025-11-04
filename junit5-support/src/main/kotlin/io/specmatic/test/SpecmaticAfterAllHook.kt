package io.specmatic.test


interface SpecmaticAfterAllHook {
    fun onAfterAllTests(testResultRecords: List<TestResultRecord>?, startTime: Long, endTime: Long, coverage: Int)
}