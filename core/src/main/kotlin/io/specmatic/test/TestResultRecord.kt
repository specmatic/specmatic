package io.specmatic.test

import io.specmatic.core.Result
import io.specmatic.reporter.model.TestResult

data class TestResultRecord(
    val path: String,
    val method: String,
    val responseStatus: Int,
    override val result: TestResult,
    val sourceProvider: String? = null,
    override val sourceRepository: String? = null,
    override val sourceRepositoryBranch: String? = null,
    override val specification: String? = null,
    val serviceType: String? = null,
    val actualResponseStatus: Int = 0,
    val scenarioResult: Result? = null,
    val isValid: Boolean = true,
    override val isWip: Boolean = false,
    val requestContentType: String? = null,
    val soapAction: String? = null,
    val isGherkin: Boolean = false,
    override val duration: Long = 0,
    override val rawStatus: String? = result.toString(),
    override val testType: String = "ContractTest"
): io.specmatic.reporter.model.TestResultRecord {
    val isExercised = result !in setOf(TestResult.MissingInSpec, TestResult.NotCovered)
    val isCovered = result !in setOf(TestResult.MissingInSpec, TestResult.NotCovered)

    fun isConnectionRefused() = actualResponseStatus == 0

    override fun extraFields(): Map<String, Any> {
        val extra = mutableMapOf<String, Any>()

        extra["httpMethod"] = method
        extra["httpPath"] = path
        requestContentType?.let { extra["requestContentType"] = it }
        extra["expectedStatus"] = responseStatus
        extra["actualStatus"] = actualResponseStatus

        sourceRepository?.let { extra["sourceRepository"] = it }
        sourceRepositoryBranch?.let { extra["sourceBranch"] = it }
        specification?.let { extra["specification"] = it }

        extra["isWip"] = isWip
        return extra
    }

    override fun tags(): List<String> {
        val tags = mutableListOf<String>()

        if (isWip) tags.add("wip")
        requestContentType?.let { contentType ->
            tags.add("content-type:$contentType")
        }

        tags.add("method:${method.lowercase()}")
        tags.add("status:${responseStatus}")

        return tags
    }

    override fun testMessage(): String {
        return when {
            result == TestResult.Failed && actualResponseStatus != responseStatus ->
                "Expected status ${responseStatus}, but got $actualResponseStatus"

            result == TestResult.Failed ->
                "Test failed: ${scenarioResult?.let { "Scenario failure" } ?: "Unknown error"}"

            isWip -> "Work in progress test"
            else -> ""
        }
    }

    override fun testName(): String {
        val scenarioName = scenarioResult?.scenario?.testDescription()

        return when {
            !scenarioName.isNullOrBlank() -> scenarioName
            isWip -> "WIP: ${method.uppercase()} $path -> $responseStatus"
            else -> "${method.uppercase()} $path (${responseStatus})"
        }
    }
}
