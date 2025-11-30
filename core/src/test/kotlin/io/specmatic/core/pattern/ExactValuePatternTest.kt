package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.BooleanValue
import org.junit.jupiter.api.Test
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.shouldNotMatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class ExactValuePatternTest {
    @Test
    fun `should gracefully fail when matching non null value to null value`() {
        NullValue shouldNotMatch ExactValuePattern(NumberValue(10))
    }

    @Test
    @Tag(GENERATION)
    fun `negative patterns should be generated`() {
        val result = ExactValuePattern(StringValue("data")).negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder("\"data_\"", "null", "number", "boolean")
    }

    @ParameterizedTest
    @MethodSource("constValuesToNegativeExpectations")
    fun `should generate altered version of the value when executing negativeBasedOn`(value: Value, expectations: List<String>) {
        val pattern = ExactValuePattern(value)
        val negativePatterns = pattern.negativeBasedOn(Row(), Resolver()).toList()
        assertThat(negativePatterns.map { it.value.typeName }).containsExactlyInAnyOrderElementsOf(expectations)
    }

    @Test
    fun `should encompass an any-value pattern with only one pattern that matches self`() {
        val constPattern = ExactValuePattern(NumberValue(100))
        val anyValueOneOf = AnyPattern(pattern = listOf(constPattern), extensions = emptyMap())
        val result = constPattern.encompasses(anyValueOneOf, Resolver(), Resolver())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should not encompass an any-value pattern with more than one pattern that does not matches self`() {
        val constPattern = ExactValuePattern(NumberValue(100))
        val anyValueOneOf = AnyPattern(
            pattern = listOf(ExactValuePattern(NumberValue(99)), constPattern, ExactValuePattern(NumberValue(101))),
            extensions = emptyMap()
        )

        val result = constPattern.encompasses(anyValueOneOf, Resolver(), Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        Expected 100 (number), actual was 99 (number)
        Expected 100 (number), actual was 101 (number)
        """.trimIndent())
    }

    companion object {
        @JvmStatic
        fun constValuesToNegativeExpectations(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    StringValue("ExactValue"),
                    listOf("\"ExactValue_\"", "number", "boolean", "null")
                ),
                Arguments.of(
                    StringValue("101"),
                    listOf("\"101_\"", "number", "boolean", "null")
                ),
                Arguments.of(
                    StringValue("false"),
                    listOf("\"false_\"", "number", "boolean", "null")
                ),
                Arguments.of(
                    NumberValue(100),
                    listOf("101", "string", "boolean", "null")
                ),
                Arguments.of(
                    BooleanValue(false),
                    listOf("true", "string", "number", "null")
                ),
                Arguments.of(
                    NullValue,
                    listOf("string", "number", "boolean")
                ),
            )
        }
    }
}