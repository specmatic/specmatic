package io.specmatic.test

import io.specmatic.core.TestResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestResultRecordOpenApiTest {

    @Test
    fun `should not be considered exercised when result is MissingInSpec or NotCovered`() {
        listOf(TestResult.MissingInSpec, TestResult.NotCovered).forEach {
            val record = OpenApiTestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                testResult = it
            )
            assertFalse(record.isExercised, "Record should not be considered exercised for Result: $it")
        }
    }

    @Test
    fun `should be considered exercised for other results`() {
        TestResult.entries.filterNot { it in listOf(TestResult.MissingInSpec, TestResult.NotCovered) }.forEach {
            val record = OpenApiTestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                testResult = it
            )
            assertTrue(record.isExercised, "Record should be considered exercised for Result: $it")
        }
    }

    @Test
    fun `should be considered covered when results Success, Error, Failed, and NotImplemented`() {
        listOf(TestResult.Success, TestResult.Error, TestResult.Failed, TestResult.NotImplemented).forEach {
            val record = OpenApiTestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                testResult = it
            )
            assertTrue(record.isCovered, "Record should be considered covered for result $it")
        }
    }

    @Test
    fun `should not be considered covered for other results`() {
        TestResult.entries.filterNot { it in listOf(TestResult.Success, TestResult.Error, TestResult.Failed, TestResult.NotImplemented) }.forEach {
            val record = OpenApiTestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                testResult = it
            )
            assertFalse(record.isCovered, "Record should not be considered covered for result $it")
        }
    }
}
