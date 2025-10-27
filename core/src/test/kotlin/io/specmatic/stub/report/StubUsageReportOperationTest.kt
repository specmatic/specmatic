package io.specmatic.stub.report

import io.specmatic.reports.OpenApiOperationGroup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StubUsageReportOperationTest {
    @Test
    fun `hasSameOperationIdentifiers should return true for matching identifiers`() {
        val operation1 = OpenApiOperationGroup(path = "/api/test", method = "GET", responseCode = 200, count = 5)
        val operation2 = OpenApiOperationGroup(path = "/api/test", method = "GET", responseCode = 200, count = 10)

        val result = operation1.equalsByIdentity(operation2)
        assertTrue(result)
    }

    @Test
    fun `hasSameOperationIdentifiers should return false for non-matching identifiers`() {
        val operation1 = OpenApiOperationGroup(path = "/api/test", method = "GET", responseCode = 200, count = 5)
        val operation2 = OpenApiOperationGroup(path = "/api/other", method = "POST", responseCode = 404, count = 10)

        val result = operation1.equalsByIdentity(operation2)
        assertFalse(result)
    }

    @Test
    fun `merge should combine counts of two operations`() {
        val operation1 = OpenApiOperationGroup(path = "/api/test", method = "GET", responseCode = 200, count = 5)
        val operation2 = OpenApiOperationGroup(path = "/api/test", method = "GET", responseCode = 200, count = 10)

        val mergedOperation = operation1.merge(operation2) as OpenApiOperationGroup
        assertThat(mergedOperation.count).isEqualTo(15)
        assertThat(mergedOperation.path).isEqualTo(operation1.path)
        assertThat(mergedOperation.method).isEqualTo(operation1.method)
        assertThat(mergedOperation.responseCode).isEqualTo(operation1.responseCode)
    }
}
