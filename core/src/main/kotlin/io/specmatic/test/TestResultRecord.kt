package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.pattern.ContractException
import io.specmatic.reporter.ctrf.model.CtrfTestMetadata
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.spec.operation.APIOperation
import io.specmatic.reporter.model.TestResult
import io.specmatic.reporter.spec.model.OpenAPIOperation
import java.time.Duration
import java.time.Instant

data class TestResultRecord(
    val path: String,
    val method: String,
    val responseStatus: Int,
    val request: HttpRequest?,
    val response: HttpResponse?,
    override val result: TestResult,
    val sourceProvider: String? = null,
    override val repository: String? = null,
    override val branch: String? = null,
    override val specification: String? = null,
    val serviceType: String? = null,
    val actualResponseStatus: Int = 0,
    val scenarioResult: Result? = null,
    val isValid: Boolean = true,
    override val isWip: Boolean = false,
    val requestContentType: String? = null,
    val soapAction: String? = null,
    val isGherkin: Boolean = false,
    val requestTime: Instant? = null,
    val responseTime: Instant? = null,
    override val duration: Long = durationFrom(requestTime, responseTime),
    override val rawStatus: String? = result.toString(),
    override val testType: String = "ContractTest",
    override val operation: APIOperation = OpenAPIOperation(
        path = path,
        method = method,
        contentType = requestContentType.orEmpty(),
        responseCode = actualResponseStatus
    )
): CtrfTestResultRecord {
    val isExercised = result !in setOf(TestResult.MissingInSpec, TestResult.NotCovered)
    val isCovered = result !in setOf(TestResult.MissingInSpec, TestResult.NotCovered)

    fun isConnectionRefused() = actualResponseStatus == 0

    override fun extraFields(): CtrfTestMetadata {
        return CtrfTestMetadata(
            valid = isValid,
            isWip = isWip,
            input = request?.toLogString().orEmpty(),
            output = response?.toLogString().orEmpty(),
            inputTime = requestTime?.toEpochMilli() ?: 0L,
            outputTime = responseTime?.toEpochMilli() ?: 0L
        )
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

    companion object {
        fun List<TestResultRecord>.getCoverageStatus(): CoverageStatus {
            if(this.any { it.isWip }) return CoverageStatus.WIP

            if(!this.any { it.isValid }) {
                return when (this.first().result) {
                    TestResult.MissingInSpec -> CoverageStatus.MISSING_IN_SPEC
                    else -> CoverageStatus.INVALID
                }
            }

            if (this.any { it.isExercised }) {
                return when (this.first().result) {
                    TestResult.NotImplemented -> CoverageStatus.NOT_IMPLEMENTED
                    else -> CoverageStatus.COVERED
                }
            }

            return when (val result = this.first().result) {
                TestResult.NotCovered -> CoverageStatus.NOT_COVERED
                TestResult.MissingInSpec -> CoverageStatus.MISSING_IN_SPEC
                else -> throw ContractException("Cannot determine remarks for unknown test result: $result")
            }
        }
    }
}

private fun durationFrom(requestTime: Instant?, responseTime: Instant?) =
    if (requestTime != null && responseTime != null)
        Duration.between(requestTime, responseTime).toMillis()
    else 0L
