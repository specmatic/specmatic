package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.BinaryPattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Disabled

internal class MultiPartFilePatternTest {
    private val filenameValue = "employee.csv"
    private val filenameType = ExactValuePattern(StringValue("employee.csv"))

    @Test
    fun `should match file parts`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType, "text/csv", "gzip")
        val value = MultiPartFileValue("employeecsv", filenameValue, "text/csv", "gzip")
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should match file parts without content type or encoding`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType)
        val value = MultiPartFileValue("employeecsv", filenameValue)
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Disabled
    @Test
    fun `should not match file parts with mismatched content type`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType, "text/plain")
        val value = MultiPartFileValue("employeecsv", filenameValue)
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `should not match file parts with mismatched content encoding`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType, "text/plain", "identity")
        val value = MultiPartFileValue("employeecsv", filenameValue, "text/plain", "gzip")
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `ignores content type in value if type is null`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType)
        val value = MultiPartFileValue("employeecsv", filenameValue, "text/plain", "gzip")
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `ignores content encoding in value if type is null`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType, "text/plain")
        val value = MultiPartFileValue("employeecsv", filenameValue, "text/plain", "gzip")
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `it should generate a new pattern`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType, "text/csv", "gzip")
        val newPattern = pattern.newBasedOn(Row(), Resolver())
        assertThat(newPattern.single()).isEqualTo(pattern)
    }

    @Test
    fun `it should generate a new part`() {
        val pattern = MultiPartFilePattern("employeecsv", filenameType, "text/csv", "gzip")
        val value = MultiPartFileValue("employeecsv", filenameValue, "text/csv", "gzip")
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `value file name should match string type`() {
        val pattern = MultiPartFilePattern("employeecsv", StringPattern())
        val value = MultiPartFileValue("employeecsv", "different_filename.csv")
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `binary file pattern generates content without inventing a filename`() {
        val pattern = MultiPartFilePattern(
            name = "document",
            filename = null,
            contentType = "application/octet-stream",
            content = BinaryPattern()
        )

        val value = pattern.generate(Resolver()) as MultiPartFileValue

        assertThat(value.filename).isEmpty()
        assertThat(value.content.bytes).isNotEmpty()
        assertThat(value.contentType).isEqualTo("application/octet-stream")
    }

    @Test
    fun `filename example does not replace binary content pattern`() {
        val pattern = MultiPartFilePattern(
            name = "document",
            filename = null,
            contentType = "application/octet-stream",
            content = BinaryPattern()
        )

        val exampleBasedPattern = pattern.newBasedOn(
            Row(listOf("document_filename"), listOf("example.pdf")),
            Resolver()
        ).single() as MultiPartFilePattern
        val value = exampleBasedPattern.generate(Resolver()) as MultiPartFileValue

        assertThat(value.filename).isEqualTo("example.pdf")
        assertThat(value.content.bytes).isNotEmpty()
        assertThat(exampleBasedPattern.content).isInstanceOf(BinaryPattern::class.java)
    }

    @Test
    fun `binary file pattern ignores filename when it is not constrained`() {
        val pattern = MultiPartFilePattern(
            name = "document",
            filename = null,
            contentType = "application/octet-stream",
            content = BinaryPattern()
        )
        val value = MultiPartFileValue(
            name = "document",
            filename = "client-provided.pdf",
            contentType = "application/octet-stream",
            content = MultiPartContent(byteArrayOf(1, 2, 3))
        )

        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `binary file pattern matches exact binary content from an example`() {
        val expectedBytes = byteArrayOf(1, 2, 3)
        val pattern = MultiPartFilePattern(
            name = "document",
            filename = ExactValuePattern(StringValue("example.pdf")),
            contentType = "application/octet-stream",
            content = ExactValuePattern(BinaryValue(expectedBytes))
        )

        val matching = MultiPartFileValue(
            name = "document",
            filename = "example.pdf",
            contentType = "application/octet-stream",
            content = MultiPartContent(expectedBytes)
        )
        val mismatching = matching.copy(content = MultiPartContent(byteArrayOf(4, 5, 6)))

        assertThat(pattern.matches(matching, Resolver())).isInstanceOf(Success::class.java)
        assertThat(pattern.matches(mismatching, Resolver())).isInstanceOf(Failure::class.java)
    }
}
