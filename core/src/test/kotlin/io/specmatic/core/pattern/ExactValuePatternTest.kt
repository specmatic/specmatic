package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.value.BooleanValue
import org.junit.jupiter.api.Test
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.ScalarValue
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
        val result =
            ExactValuePattern(StringValue("data")).negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null",
            "number",
            "boolean"
        )
    }

    @ParameterizedTest
    @MethodSource("constValuesToNegativeExpectations")
    fun `should generate altered version of the value when marked as constant`(value: Value, expectations: List<String>) {
        val pattern = ExactValuePattern(value, isConst = true)
        val negativePatterns = pattern.negativeBasedOn(Row(), Resolver()).toList()
        assertThat(negativePatterns.map { it.value.typeName }).containsExactlyInAnyOrderElementsOf(expectations)
    }

    @ParameterizedTest
    @MethodSource("constValuesToNegativeExpectations")
    fun `should not generate altered version of the value when not marked as constant`(value: ScalarValue, expectations: List<String>) {
        val pattern = ExactValuePattern(value)
        val negativePatterns = pattern.negativeBasedOn(Row(), Resolver()).toList()
        val filteredExpectations = expectations.filter { it != value.alterValue().displayableValue() }
        assertThat(negativePatterns.map { it.value.typeName }).containsExactlyInAnyOrderElementsOf(filteredExpectations)
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