package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class MultiPartContentPatternTest {
    @Test
    fun `match a multi part non-file part` () {
        val value = MultiPartContentValue("employeeid", StringValue("10"))
        val pattern = MultiPartContentPattern("employeeid", StringPattern())

        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `it should generate a new pattern`() {
        val pattern = MultiPartContentPattern("employeeid", StringPattern())
        val value: MultiPartContentValue = pattern.generate(Resolver()) as MultiPartContentValue

        assertThat(value.name).isEqualTo("employeeid")
        assertThat(value.content).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `it should generate a new pattern with contentType`() {
        val pattern = MultiPartContentPattern("employeeid", StringPattern(), "text/plain")
        val value = pattern.generate(Resolver()) as MultiPartContentValue

        assertThat(value.name).isEqualTo("employeeid")
        assertThat(value.content).isInstanceOf(StringValue::class.java)
        assertThat(value.contentType).isEqualTo("text/plain")
    }

    @Test
    fun `it should generate a new pattern for test`() {
        val pattern = MultiPartContentPattern("employeeid", StringPattern())
        val newPattern = pattern.newBasedOn(Row(mapOf()), Resolver())
        assertThat(newPattern.single()).isEqualTo(pattern)
    }

    @Test
    fun `it should generate a new part`() {
        val pattern = MultiPartContentPattern("employeeid", StringPattern())
        val value = MultiPartContentValue("employeeid", StringValue("data"))
        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should generate a new pattern based on row values when the row has a column with the part name`() {
        val pattern = MultiPartContentPattern("id", NumberPattern())
        val row = Row(mapOf("id" to "10"))
        val patterns = pattern.newBasedOn(row, Resolver()).map { it as MultiPartContentPattern }.toList()

        assertThat(patterns).hasSize(1)

        assertThat(patterns.single().name).isEqualTo("id")
        assertThat(patterns.single().content).isEqualTo(ExactValuePattern(NumberValue(10)))
    }

    @Test
    fun `should generate a new pattern based on row values file when the row has a column with the part name with a file name in it`(@TempDir tempDir: File) {
        val fileContainingNumber = tempDir.resolve("fileContainingNumber.txt")
        fileContainingNumber.writeText("10")
        val pattern = MultiPartContentPattern("id", NumberPattern())
        val row = Row(mapOf("id" to "(@${fileContainingNumber.path})"))
        val patterns = pattern.newBasedOn(row, Resolver()).map { it as MultiPartContentPattern }.toList()

        assertThat(patterns).hasSize(1)

        assertThat(patterns.single().name).isEqualTo("id")
        assertThat(patterns.single().content).isEqualTo(ExactValuePattern(NumberValue(10)))
    }

    @Test
    fun `should generate a new pattern based on row values with the specified contentType when the row has a column with the part name`() {
        val pattern = MultiPartContentPattern("id", NumberPattern(), contentType = "application/json")
        val row = Row(mapOf("id" to "10"))
        val patterns = pattern.newBasedOn(row, Resolver()).map { it as MultiPartContentPattern }.toList()

        assertThat(patterns).hasSize(1)

        assertThat(patterns.single().name).isEqualTo("id")
        assertThat(patterns.single().content).isEqualTo(ExactValuePattern(NumberValue(10)))
        assertThat(patterns.single().contentType).isEqualTo("application/json")
    }

    @Test
    fun `content pattern should match value with pattern content in mock mode`() {
        val pattern = MultiPartContentPattern("id", NumberPattern())
        val value = MultiPartContentValue("id", StringValue("(number)"))

        val mockModeResolver = Resolver(mockMode = true)

        assertThat(pattern.matches(value, mockModeResolver)).isInstanceOf(Success::class.java)
    }

    @Test
    fun `content pattern should match value given contentType in pattern but not in value in mock mode`() {
        val pattern = MultiPartContentPattern("id", NumberPattern(), "text/plain")
        val value = MultiPartContentValue("id", StringValue("(number)"))

        val mockModeResolver = Resolver(mockMode = true)

        assertThat(pattern.matches(value, mockModeResolver)).isInstanceOf(Success::class.java)
    }

    @Test
    fun `content pattern should match value given contentType in pattern and in type and in value in mock mode`() {
        val pattern = MultiPartContentPattern("id", NumberPattern(), "text/plain")
        val value = MultiPartContentValue("id", StringValue("(number)"), "text/plain")

        val mockModeResolver = Resolver(mockMode = true)

        assertThat(pattern.matches(value, mockModeResolver)).isInstanceOf(Success::class.java)
    }

    @Disabled
    @Test
    fun `content pattern should not match value when the contentType in pattern and value are different`() {
        val pattern = MultiPartContentPattern("id", NumberPattern(), "text/plain")
        val value = MultiPartContentValue("id", StringValue("(number)"), specifiedContentType = "text/jaggery")

        val mockModeResolver = Resolver(mockMode = true)

        assertThat(pattern.matches(value, mockModeResolver)).isInstanceOf(Result.Failure::class.java)
    }
}