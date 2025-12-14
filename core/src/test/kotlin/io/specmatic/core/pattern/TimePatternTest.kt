package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TimePatternTest {
    private val pattern = TimePattern()

    @Test
    fun `should be able to generate a time`() {
        val generated = pattern.generate(Resolver())
        println(generated)
        val result = pattern.matches(generated, Resolver())
        assertTrue(result is Result.Success)
    }

    @Test
    fun `should generate new time values for test`() {
        val row = Row()
        val patterns = pattern.newBasedOn(row, Resolver()).map { it.value }.toList()

        assertEquals(1, patterns.size)
        assertEquals(pattern, patterns.first())

        assertThat(patterns).allSatisfy {
            val time = it.generate(Resolver())
            val match = pattern.matches(time, Resolver())
            assertThat(match).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    @Tag(GENERATION)
    fun `negative patterns should be generated`() {
        val result = pattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder("string", "number", "boolean", "null")
    }

    @ParameterizedTest
    @CsvSource(
        "01:01:01, valid",
        "a23:59:59, invalid",
        "01:01:01Z, valid",
        "01:01:01T, invalid",
        "01:01:01+05:30, valid",
        "01:01:01+05:30d, invalid",
        "01:01:01-01:00, valid",
        "01:01:01b-01:00a, invalid",
        "not-a-time, invalid",
        "aa:bb:cc, invalid"
    )
    fun `RFC 6801 regex should validate time`(time: String, validity: String) {
        val result = pattern.matches(StringValue(time), Resolver())

        val isValid = when(validity) {
            "valid" -> true
            "invalid" -> false
            else -> IllegalArgumentException("Unknown validity: $validity")
        }

        assertEquals(isValid, result is Result.Success)
    }

    @Test
    fun `should use the provided time example during generation`() {
        val example = "01:02:03Z"
        val patternWithExample = TimePattern(example = example)

        val generated = patternWithExample.generate(Resolver())

        assertEquals(example, generated.string)
    }
}
