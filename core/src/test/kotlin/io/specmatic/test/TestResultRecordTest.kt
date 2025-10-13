package io.specmatic.test

import io.specmatic.core.TestResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TestResultRecordTest {

    @Test
    fun `should not be considered exercised when result is MissingInSpec or NotCovered`() {
        listOf(TestResult.MissingInSpec, TestResult.NotCovered).forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                result = it
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
                result = it
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
                result = it
            )
            assertTrue(record.isCovered, "Record should be considered covered for result $it")
        }
    }

    @Test
    fun `isSuccess returns true only for Success enum value`() {
        assertTrue(TestResult.Success.isSuccess())
        assertFalse(TestResult.Error.isSuccess())
        assertFalse(TestResult.Failed.isSuccess())
        assertFalse(TestResult.NotImplemented.isSuccess())
        assertFalse(TestResult.MissingInSpec.isSuccess())
        assertFalse(TestResult.NotCovered.isSuccess())
    }

    @Test
    fun `isSkipped returns true only for NotCovered enum value`() {
        assertTrue(TestResult.NotCovered.isSkipped())
        assertFalse(TestResult.Success.isSkipped())
        assertFalse(TestResult.Error.isSkipped())
        assertFalse(TestResult.Failed.isSkipped())
        assertFalse(TestResult.NotImplemented.isSkipped())
        assertFalse(TestResult.MissingInSpec.isSkipped())
    }

    @Test
    fun `isOther returns true only for MissingInSpec enum value`() {
        assertTrue(TestResult.MissingInSpec.isOther())
        assertFalse(TestResult.Success.isOther())
        assertFalse(TestResult.Error.isOther())
        assertFalse(TestResult.Failed.isOther())
        assertFalse(TestResult.NotImplemented.isOther())
        assertFalse(TestResult.NotCovered.isOther())
    }

    @Test
    fun `isFailure returns true for Failed and NotImplemented when isWip is false`() {
        val isWip = false
        assertTrue(TestResult.Failed.isFailure(isWip))
        assertTrue(TestResult.NotImplemented.isFailure(isWip))
        assertFalse(TestResult.Success.isFailure(isWip))
        assertFalse(TestResult.Error.isFailure(isWip))
        assertFalse(TestResult.MissingInSpec.isFailure(isWip))
        assertFalse(TestResult.NotCovered.isFailure(isWip))
    }

    @Test
    fun `isFailure returns true only for Failed when isWip is true`() {
        val isWip = true
        assertTrue(TestResult.Failed.isFailure(isWip))
        assertFalse(TestResult.NotImplemented.isFailure(isWip))
        assertFalse(TestResult.Success.isFailure(isWip))
        assertFalse(TestResult.Error.isFailure(isWip))
        assertFalse(TestResult.MissingInSpec.isFailure(isWip))
        assertFalse(TestResult.NotCovered.isFailure(isWip))
    }

    @Test
    fun `isError returns true for Error and NotImplemented when isWip is true`() {
        val isWip = true
        assertTrue(TestResult.Error.isError(isWip))
        assertTrue(TestResult.NotImplemented.isError(isWip))
        assertFalse(TestResult.Failed.isError(isWip))
        assertFalse(TestResult.Success.isError(isWip))
        assertFalse(TestResult.MissingInSpec.isError(isWip))
        assertFalse(TestResult.NotCovered.isError(isWip))
    }

    @Test
    fun `isError returns true only for Error when isWip is false`() {
        val isWip = false
        assertTrue(TestResult.Error.isError(isWip))
        assertFalse(TestResult.NotImplemented.isError(isWip))
        assertFalse(TestResult.Failed.isError(isWip))
        assertFalse(TestResult.Success.isError(isWip))
        assertFalse(TestResult.MissingInSpec.isError(isWip))
        assertFalse(TestResult.NotCovered.isError(isWip))
    }
}
