package io.specmatic.test


interface SpecmaticAfterAllHook {
    fun onAfterAllTests(testResultRecords: List<TestResultRecord>?)
}