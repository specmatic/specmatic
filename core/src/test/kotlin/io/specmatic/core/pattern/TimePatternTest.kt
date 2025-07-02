package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TimePatternTest {
    @Test
    fun `should be able to generate a time`() {
        val generated = TimePattern.generate(Resolver())
        println(generated)
        val result = TimePattern.matches(generated, Resolver())
        assertTrue(result is Result.Success)
    }

    @Test
    fun `should generate new time values for test`() {
        val row = Row()
        val patterns = TimePattern.newBasedOn(row, Resolver()).map { it.value }.toList()

        assertEquals(1, patterns.size)
        assertEquals(TimePattern, patterns.first())

        assertThat(patterns).allSatisfy {
            val time = it.generate(Resolver())
            val match = TimePattern.matches(time, Resolver())
            assertThat(match).isInstanceOf(Result.Success::class.java)
        }
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
        val result = TimePattern.matches(StringValue(time), Resolver())

        val isValid = when(validity) {
            "valid" -> true
            "invalid" -> false
            else -> IllegalArgumentException("Unknown validity: $validity")
        }

        assertEquals(isValid, result is Result.Success)
    }
}