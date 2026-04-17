package io.specmatic.test


import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.reporter.ctrf.model.CtrfTestQualifiers
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.TestResultRecord.Companion.getCoverageStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant


class TestResultRecordTest {

    @Test
    fun `should not be considered exercised when result is MissingInSpec or NotCovered`() {
        listOf(TestResult.MissingInSpec, TestResult.NotCovered).forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = it,  specType = SpecType.OPENAPI
            )
            assertFalse(record.isExercised, "Record should not be considered exercised for Result: $it")
        }
    }

    @Test
    fun `should be considered exercised for other results`() {
        TestResult.entries.filterNot { it in listOf(TestResult.MissingInSpec, TestResult.NotCovered) }.forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = it,  specType = SpecType.OPENAPI
            )
            assertTrue(record.isExercised, "Record should be considered exercised for Result: $it")
        }
    }

    @Test
    fun `should be considered covered when results Success, Failed, and NotImplemented`() {
        listOf(TestResult.Success, TestResult.Failed, TestResult.NotImplemented).forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = it,  specType = SpecType.OPENAPI
            )
            assertTrue(record.isCovered, "Record should be considered covered for result $it")
        }
    }

    @Test
    fun `should not be considered covered for other results`() {
        listOf(TestResult.MissingInSpec, TestResult.NotCovered).forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = it,  specType = SpecType.OPENAPI
            )
            assertFalse(record.isCovered, "Record should not be considered covered for result $it")
        }
    }

    @Test
    fun `extraFields should reflect request and response and times when present`() {
        val request = HttpRequest(
            method = "POST",
            path = "/some/path",
            headers = mapOf("Content-Type" to "application/json")
        )
        val response = HttpResponse.ok("{\"hello\":\"world\"}")

        val requestTime = Instant.ofEpochMilli(1_000L)
        val responseTime = Instant.ofEpochMilli(2_000L)

        val record = TestResultRecord(
            path = "/some/path",
            method = "POST",
            responseStatus = 200,
            request = request,
            response = response,
            result = TestResult.Success,
            isWip = false,
            requestTime = requestTime,
            responseTime = responseTime,  specType = SpecType.OPENAPI
        )

        val meta = record.extraFields()
        assertThat(meta.wip).isFalse()
        assertEquals(request.toLogString().trim(), meta.input.trim())
        assertNotNull(meta.outputs)
        assertEquals(1, meta.outputs!!.size)
        assertEquals("Response", meta.outputs!!.single().title)
        assertEquals(response.toLogString().trim(), meta.outputs!!.single().content.trim())
        assertEquals(requestTime.toEpochMilli(), meta.inputTime)
        assertEquals(responseTime.toEpochMilli(), meta.outputs!!.single().time)
    }

    @Test
    fun `extraFields should use defaults when request or response are null`() {
        val record = TestResultRecord(
            path = "/some/path",
            method = "GET",
            responseStatus = 200,
            request = null,
            response = null,
            result = TestResult.Success,
            isWip = true,
            requestTime = null,
            responseTime = null,  specType = SpecType.OPENAPI
        )

        val meta = record.extraFields()
        assertThat(meta.wip).isTrue()
        assertEquals("", meta.input)
        assertNull(meta.outputs)
        assertEquals(0L, meta.inputTime)
    }

    @Test
    fun `getCoverageStatus should return COVERED for exercised test results`() {
        listOf(TestResult.Success, TestResult.Failed).forEach { result ->
            val coverageStatus = listOf(testResultRecord(result = result)).getCoverageStatus()

            assertEquals(CoverageStatus.COVERED, coverageStatus)
        }
    }

    @Test
    fun `getCoverageStatus should return NOT_IMPLEMENTED for not implemented results`() {
        val coverageStatus = listOf(testResultRecord(result = TestResult.NotImplemented)).getCoverageStatus()

        assertEquals(CoverageStatus.NOT_IMPLEMENTED, coverageStatus)
    }

    @Test
    fun `getCoverageStatus should return NOT_COVERED for not covered results`() {
        val coverageStatus = listOf(testResultRecord(result = TestResult.NotCovered)).getCoverageStatus()

        assertEquals(CoverageStatus.NOT_COVERED, coverageStatus)
    }

    @Test
    fun `getCoverageStatus should return MISSING_IN_SPEC for missing in spec results`() {
        val coverageStatus = listOf(testResultRecord(result = TestResult.MissingInSpec)).getCoverageStatus()

        assertEquals(CoverageStatus.MISSING_IN_SPEC, coverageStatus)
    }

    @Test
    fun `getCoverageStatus should return WIP when any test is work in progress`() {
        val coverageStatus = listOf(
            testResultRecord(result = TestResult.Success),
            testResultRecord(result = TestResult.Success, isWip = true)
        ).getCoverageStatus()

        assertEquals(CoverageStatus.WIP, coverageStatus)
    }

    @Test
    fun `returns actual message when isWip is true`() {
        val failure = io.specmatic.core.Result.Failure("WIP failure message")
        val record = testResultRecord(
            isWip = true,
            result = TestResult.NotCovered
        ).copy(scenarioResult = failure)

        val result = record.testMessage()
        assertTrue(result.contains("WIP failure message"))
    }

    @Test
    fun `returns empty string when scenarioResult is null`() {
        val record = testResultRecord(result = TestResult.MissingInSpec, isWip = false)
        val result = record.testMessage()
        assertEquals("", result)
    }

    @Test
    fun `returns empty string when scenarioResult is success`() {
        val record = testResultRecord(
            result = TestResult.Success,
            isWip = false
        ).copy(scenarioResult = io.specmatic.core.Result.Success())

        val result = record.testMessage()
        assertEquals("", result)
    }

    @Test
    fun `returns report string when scenarioResult fails`() {
        val failure = io.specmatic.core.Result.Failure("Something went wrong")

        val record = testResultRecord(
            result = TestResult.Failed,
            isWip = false
        ).copy(scenarioResult = failure)

        val result = record.testMessage()
        assertTrue(result.contains("Something went wrong"))
    }

    @Test
    fun `testQualifiers should include response undeclared when response is outside specification`() {
        val record = testResultRecord(result = TestResult.Failed).copy(isResponseInSpecification = false)
        assertThat(record.testQualifiers()).containsExactly(CtrfTestQualifiers.UNDECLARED_RESPONSE)
        assertThat(record.extraFields().qualifiers).containsExactly(CtrfTestQualifiers.UNDECLARED_RESPONSE)
    }

    @Test
    fun `testQualifiers should be empty when response is declared or unknown`() {
        val responseDeclared = testResultRecord(result = TestResult.Failed).copy(isResponseInSpecification = true)
        val responseUnknown = testResultRecord(result = TestResult.Failed)

        assertThat(responseDeclared.testQualifiers()).isEmpty()
        assertThat(responseDeclared.extraFields().qualifiers).isEmpty()
        assertThat(responseUnknown.testQualifiers()).isEmpty()
        assertThat(responseUnknown.extraFields().qualifiers).isEmpty()
    }

    private fun testResultRecord(
        result: TestResult,
        isWip: Boolean = false
    ) = TestResultRecord(
        path = "/example/path",
        method = "GET",
        responseStatus = 200,
        request = null,
        response = null,
        result = result,
        isWip = isWip,
        specType = SpecType.OPENAPI
    )
}
