package io.specmatic.test

import io.specmatic.core.TestResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `should not be considered covered for other results`() {
        TestResult.entries.filterNot { it in listOf(TestResult.Success, TestResult.Error, TestResult.Failed, TestResult.NotImplemented) }.forEach {
            val record = TestResultRecord(
                path = "/example/path",
                method = "GET",
                responseStatus = 200,
                result = it
            )
            assertFalse(record.isCovered, "Record should not be considered covered for result $it")
        }
    }

    @Test
    fun `should return scenario metadata`() {
        val record = TestResultRecord(
            path = "/example/path",
            method = "GET",
            responseStatus = 200,
            result = TestResult.Success
        )

        val scenarioMetadata = record.toScenarioMetadata()

        assertThat(scenarioMetadata.method).isEqualTo("GET")
        assertThat(scenarioMetadata.path).isEqualTo("/example/path")
        assertThat(scenarioMetadata.statusCode).isEqualTo(200)
        assertThat(scenarioMetadata.header).isEmpty()
        assertThat(scenarioMetadata.query).isEmpty()
        assertThat(scenarioMetadata.exampleName).isEmpty()
    }
}
