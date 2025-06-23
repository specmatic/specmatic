package io.specmatic.test

import io.specmatic.core.Result
import io.specmatic.core.TestResult

data class TestResultRecord(
    val path: String,
    val method: String,
    val responseStatus: Int,
    val result: TestResult,
    val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val serviceType: String? = null,
    val actualResponseStatus: Int = 0,
    val scenarioResult: Result? = null,
    val isValid: Boolean = true,
    val isWip: Boolean = false,
    val requestContentType: String? = null,
    val soapAction: String? = null,
    val isGherkin: Boolean = false
) {
    val isExercised = result !in setOf(TestResult.MissingInSpec, TestResult.NotCovered)
    val isCovered = result !in setOf(TestResult.MissingInSpec, TestResult.NotCovered)

    fun isConnectionRefused() = actualResponseStatus == 0

}
