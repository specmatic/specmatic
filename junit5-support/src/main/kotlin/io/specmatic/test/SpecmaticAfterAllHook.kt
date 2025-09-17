package io.specmatic.test


interface SpecmaticAfterAllHook {
    fun onAfterAllTests(testResultRecords: MutableList<TestResultRecord>?)
}