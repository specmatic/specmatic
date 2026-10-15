package io.specmatic.core.pattern

import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.NumberValue
import io.specmatic.optionalPattern
import io.specmatic.shouldMatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ConvertTest {
    @Test
    fun `create a pattern that matches an empty string or any other pattern`() {
        val pattern = parsedPattern("(number?)")
        val nullValue = EmptyString
        val numberValue = NumberValue(10)

        nullValue shouldMatch pattern
        numberValue shouldMatch pattern
    }

    @Test
    fun `create a pattern that matches an empty string or a list`() {
        val pattern = parsedPattern("(number*?)")
        val nullValue = EmptyString
        val numberList = parsedValue("[1, 2, 3]")

        nullValue shouldMatch pattern
        numberList shouldMatch pattern
    }

    @Test
    fun `create a pattern that matches a list of nullable values`() {
        val pattern = parsedPattern("(number?*)")
        val numberList = parsedValue("[1,2,3]")
        val numberListWithNulls = parsedValue("[1,null,3, 4]")

        numberList shouldMatch pattern
        numberListWithNulls shouldMatch pattern
    }

    @Test
    fun `given a number in string question it should generate a nullable number in string`() {
        val pattern = parsedPattern("(number in string?)")
        val expectedPattern = optionalPattern(PatternInStringPattern(DeferredPattern("(number)")))

        assertThat(pattern).isEqualTo(expectedPattern)
    }

    @Test
    fun `given a number in string star it should generate a nullable number in string`() {
        val pattern = parsedPattern("(number in string*)")
        val expectedPattern = ListPattern(PatternInStringPattern(DeferredPattern("(number)")))

        assertThat(pattern).isEqualTo(expectedPattern)
    }
}
