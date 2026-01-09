package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
                result = it, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                result = it, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
            assertTrue(record.isExercised, "Record should be considered exercised for Result: $it")
        }
    }

    @Test
    fun `should be considered covered when results Success, Error, Failed, and NotImplemented`() {
        listOf(TestResult.Success, TestResult.Error, TestResult.Failed, TestResult.NotImplemented).forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = it, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
            assertTrue(record.isCovered, "Record should be considered covered for result $it")
        }
    }

    @Test
    fun `should not be considered covered for other results`() {
        TestResult.entries.filterNot { it in listOf(TestResult.Success, TestResult.Error, TestResult.Failed, TestResult.NotImplemented) }.forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = it, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            isValid = true,
            isWip = false,
            requestTime = requestTime,
            responseTime = responseTime, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val meta = record.extraFields()

        assertTrue(meta.valid)
        assertFalse(meta.wip)
        assertEquals(request.toLogString().trim(), meta.input.trim())
        assertEquals(response.toLogString().trim(), meta.output?.trim())
        assertEquals(requestTime.toEpochMilli(), meta.inputTime)
        assertEquals(responseTime.toEpochMilli(), meta.outputTime)
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
            isValid = false,
            isWip = true,
            requestTime = null,
            responseTime = null, protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val meta = record.extraFields()

        assertFalse(meta.valid)
        assertTrue(meta.wip)
        assertEquals("", meta.input)
        assertEquals("", meta.output)
        assertEquals(0L, meta.inputTime)
        assertEquals(0L, meta.outputTime)
    }
}
