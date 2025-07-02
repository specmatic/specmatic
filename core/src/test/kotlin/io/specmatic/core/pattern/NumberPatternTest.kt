package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.UseDefaultExample
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.shouldNotMatch
import org.apache.commons.lang3.RandomStringUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.math.RoundingMode

internal class NumberPatternTest {
    private val smallInc = BigDecimal("1")

    @Test
    fun `should fail to match nulls gracefully`() {
        NullValue shouldNotMatch NumberPattern()
    }

    @Test
    fun `should not allow maxLength less than minLength`() {
        val exception = assertThrows<IllegalArgumentException> { NumberPattern(minLength = 6, maxLength = 4) }
        assertThat(exception.message).isEqualTo("maxLength 4 cannot be less than minLength 6")
    }

    @Test
    fun `should allow maxLength equal to minLength`() {
        NumberPattern(minLength = 4, maxLength = 4)
    }

    @Test
    fun `should not allow minLength to be less than 1`() {
        val exception = assertThrows<IllegalArgumentException> { NumberPattern(minLength = 0) }
        assertThat(exception.message).isEqualTo("minLength 0 cannot be less than 1")
    }

    @ParameterizedTest
    @CsvSource(
        "3, 2, false, false, minimum 3 cannot be greater than maximum 2",
        "2, 3, true, true, effective minimum 3 cannot be greater than effective maximum 2 after applying exclusiveMinimum and exclusiveMaximum",
        "3, 3, true, true, effective minimum 4 cannot be greater than effective maximum 2 after applying exclusiveMinimum and exclusiveMaximum",
        "3, 3, true, false, effective minimum 4 cannot be greater than effective maximum 3 after applying exclusiveMinimum and exclusiveMaximum",
        "3, 3, false, true, effective minimum 3 cannot be greater than effective maximum 2 after applying exclusiveMinimum and exclusiveMaximum",
    )
    fun `should not allow effective min to be greater than effective max`(min: String, max: String, exclusiveMin: Boolean, exclusiveMax: Boolean, errorMsg: String) {
        val exception = assertThrows<IllegalArgumentException> { NumberPattern(minimum = BigDecimal(min), maximum = BigDecimal(max), exclusiveMinimum = exclusiveMin, exclusiveMaximum = exclusiveMax) }
        assertThat(exception.message).isEqualTo(errorMsg)
    }

    @Test
    fun `should allow example values as per minimum keyword`() {
        val result = NumberPattern(minimum = BigDecimal(3)).matches(NumberValue(4), Resolver())
        assertThat(result.isSuccess()).isTrue()
    }

    @Test
    fun `should reject example values when minimum keyword is not met`() {
        val result = NumberPattern(minimum = BigDecimal(3)).matches(NumberValue(2), Resolver())
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).isEqualTo("""Expected number >= 3, actual was 2 (number)""")
    }

    @Test
    fun `should allow example values as per exclusiveMinimum keyword`() {
        val result = NumberPattern(minimum = BigDecimal(3.0), exclusiveMinimum = false).matches(NumberValue(3), Resolver())
        assertThat(result.isSuccess()).isTrue()
    }

    @Test
    fun `should reject example values when exclusiveMinimum keyword is not met`() {
        val result = NumberPattern(minimum = BigDecimal(3), exclusiveMinimum = true).matches(NumberValue(3), Resolver())
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).isEqualTo("""Expected number >= 4, actual was 3 (number)""")
    }

    @Test
    fun `should allow example values as per maximum keyword`() {
        val result = NumberPattern(maximum = BigDecimal(99.0)).matches(NumberValue(98), Resolver())
        assertThat(result.isSuccess()).isTrue()
    }

    @Test
    fun `should reject example values when maximum keyword is not met`() {
        val result = NumberPattern(maximum = BigDecimal(99.0)).matches(NumberValue(100), Resolver())
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).isEqualTo("""Expected number <= 99, actual was 100 (number)""")
    }

    @Test
    fun `should allow example values as per exclusiveMaximum keyword`() {
        val result = NumberPattern(maximum = BigDecimal(99.0), exclusiveMaximum = false).matches(NumberValue(99.0), Resolver())
        assertThat(result.isSuccess()).isTrue()
    }

    @Test
    fun `should reject example values when exclusiveMaximum keyword is not met`() {
        val result = NumberPattern(maximum = BigDecimal(99), exclusiveMaximum = true).matches(NumberValue(99), Resolver())
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).isEqualTo("""Expected number <= 98, actual was 99 (number)""")
    }

    @Test
    fun `should not allow maximum less than minimum`() {
        val exception = assertThrows<IllegalArgumentException> { NumberPattern(minimum = BigDecimal(6.0), maximum = BigDecimal(4.0)) }
        assertThat(exception.message).isEqualTo("minimum 6 cannot be greater than maximum 4")
    }

    @Test
    fun `should allow maximum equal to minimum when exclusive keywords are false or not used`() {
        NumberPattern(minimum = BigDecimal(6.0), maximum = BigDecimal(6.0))
        NumberPattern(minimum = BigDecimal(6.0), exclusiveMinimum = false, maximum = BigDecimal(6.0), exclusiveMaximum = false)
    }

    @Test
    fun `should not allow maximum greater than minimum by 1 when both exclusive keywords are set to true`() {
        val exception = assertThrows<IllegalArgumentException> { NumberPattern(minimum = BigDecimal(6.0), exclusiveMinimum = true, maximum = BigDecimal(5.0), exclusiveMaximum = true) }
        assertThat(exception.message).isEqualTo("minimum 6 cannot be greater than maximum 5")
    }

    @Test
    fun `should generate 1 digit long random number when min and max length are not specified`() {
        assertThat(NumberPattern(isDoubleFormat = false).generate(Resolver()).toStringLiteral().length).isEqualTo(3)
    }

    @Test
    fun `should generate a random double number when min and max length are not specified`() {
        val numberValue = NumberPattern(isDoubleFormat = true).generate(Resolver()) as NumberValue

        assertThat((numberValue.number is Double)).isTrue()
    }

    @Test
    fun `should generate random number of minLength length when minLength is greater than 3`() {
        assertThat(
            NumberPattern(minLength = 8, maxLength = 12).generate(Resolver()).toStringLiteral().length
        ).isEqualTo(8)
    }

    @Test
    fun `should generate random number of maxLength length when maxLength is less than 3`() {
        assertThat(
            NumberPattern(minLength = 1, maxLength = 2).generate(Resolver()).toStringLiteral().length
        ).isEqualTo(2)
    }

    @Test
    fun `should generate random number of length 3 when minLength is less than 3 is less than maxLength`() {
        assertThat(
            NumberPattern(minLength = 1, maxLength = 5).generate(Resolver()).toStringLiteral().length
        ).isEqualTo(3)
    }

    @Test
    fun `should generate random number of length 3 when minLength and maxLength are both equal to 3`() {
        assertThat(
            NumberPattern(minLength = 3, maxLength = 3).generate(Resolver()).toStringLiteral().length
        ).isEqualTo(3)
    }

    @Test
    fun `should match number of any length when min and max are not specified`() {
        val randomNumber = RandomStringUtils.randomNumeric((1..9).random()).toInt()
        assertThat(NumberPattern().matches(NumberValue(randomNumber), Resolver()).isSuccess()).isTrue
    }

    @Test
    fun `should generate a number greater than or equal to minimum when exclusive keywords are false or not set`() {
        val minimum = BigDecimal("5")
        val generatedValues = (0..5).map { NumberPattern(minimum = minimum).generate(Resolver()) }
        assertThat(generatedValues).allSatisfy {
            it as NumberValue
            assertThat(it.number as Int).isGreaterThanOrEqualTo(minimum.toInt())
        }
    }

    @Test
    fun `should generate a number less than or equal to maximum when exclusive keywords are false or not set`() {
        val maximum = BigDecimal("5")
        val generatedValues = (0..5).map { NumberPattern(maximum = maximum).generate(Resolver()) }
        assertThat(generatedValues).allSatisfy {
            it as NumberValue
            assertThat(it.number as Int).isLessThanOrEqualTo(maximum.toInt())
        }
    }

    @Test
    fun `should generate a number greater than or equal to minimum and maximum when are set and exclusive keywords are both false`() {
        val minimum = BigDecimal("5")
        val maximum = BigDecimal("10")
        val generatedValues = (0..5).map { NumberPattern(minimum = minimum, maximum = maximum).generate(Resolver()) }
        assertThat(generatedValues).allSatisfy {
            it as NumberValue
            assertThat(it.number as Int).isGreaterThanOrEqualTo(minimum.toInt()).isLessThanOrEqualTo(maximum.toInt())
        }
    }

    @Test
    fun `should generate a number greater than minimum and maximum when are set and exclusive keywords are both true`() {
        val minimum = BigDecimal("5.0")
        val maximum = BigDecimal("10.0")
        val generatedValues = (0..5).map {
            NumberPattern(
                minimum = minimum,
                exclusiveMinimum = true,
                maximum = maximum,
                exclusiveMaximum = true,
                isDoubleFormat = true
            ).generate(Resolver())
        }
        assertThat(generatedValues).allSatisfy {
            it as NumberValue
            assertThat(it.number as Double).isGreaterThan(minimum.toDouble()).isLessThan(maximum.toDouble())
        }
    }

    @Test
    fun `should generate a number greater than minimum even when max is negative`() {
        val maximum = BigDecimal("-10")
        val generatedValues = (0..5).map { NumberPattern(maximum = maximum).generate(Resolver()) }
        assertThat(generatedValues).allSatisfy {
            it as NumberValue
            assertThat(it.number as Int).isLessThanOrEqualTo(maximum.toInt())
        }
    }

    @Test
    fun `should generate a number between min and max even when they are negative`() {
        val minimum = BigDecimal("-100")
        val maximum = BigDecimal("0")
        val generatedValues = (0..5).map { NumberPattern(minimum = minimum, maximum = maximum).generate(Resolver()) }
        assertThat(generatedValues).allSatisfy {
            it as NumberValue
            assertThat(it.number as Int).isGreaterThanOrEqualTo(minimum.toInt()).isLessThanOrEqualTo(maximum.toInt())
        }
    }

    @Test
    fun `should generate an Int within min and max bounds if isDoubleFormat is false and the min and max constraints are set`() {
        val minimum = BigDecimal("0")
        val maximum = BigDecimal("10")
        val generatedValues = (0..5).map {
            NumberPattern(
                minimum = minimum,
                maximum = maximum,
                isDoubleFormat = false
            ).generate(Resolver())
        }
        assertThat(generatedValues).allSatisfy {
            it as NumberValue
            assertThat(it.number as Int).isGreaterThanOrEqualTo(minimum.toInt()).isLessThanOrEqualTo(maximum.toInt())
        }
    }

    @Test
    fun `should not match when number is shorter than minLength`() {
        val result = NumberPattern(minLength = 4).matches(NumberValue(123), Resolver())
        assertThat(result.isSuccess()).isFalse
        assertThat(result.reportString()).isEqualTo("""Expected number with minLength 4, actual was 123 (number)""")
    }

    @Test
    fun `should not match when number is longer than maxLength`() {
        val result = NumberPattern(maxLength = 3).matches(NumberValue(1234), Resolver())
        assertThat(result.isSuccess()).isFalse
        assertThat(result.reportString()).isEqualTo("""Expected number with maxLength 3, actual was 1234 (number)""")
    }

    @Test
    fun `it should use the example if provided when generating`() {
        val generated = NumberPattern(example = "10").generate(Resolver(defaultExampleResolver = UseDefaultExample))
        assertThat(generated).isEqualTo(NumberValue(10))
    }

    @Test
    fun `it should match the lowest min`() {
        val pointZeroOne = ".01"
        val minimumPointZeroOne = NumberPattern(minimum = BigDecimal(pointZeroOne))
        val result = minimumPointZeroOne.matches(NumberValue(convertToNumber(pointZeroOne)), Resolver())
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
    }

    @Test
    @Tag(GENERATION)
    fun `negative values should be generated`() {
        val result = NumberPattern().negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null",
            "string",
            "boolean"
        )
    }

    @ParameterizedTest
    @CsvSource(
        "1, 10, false",
        "10, 20, false",
        "10.00, 20.00, true",
        "0.01, 0.99, true",
    )
    fun `positive values generated should include minimum and maximum keyword values`(min: String, max: String, doubleFormat: Boolean) {
        val minimum = BigDecimal(min)
        val maximum = BigDecimal(max)
        val pattern = NumberPattern(
            minimum = minimum,
            maximum = maximum,
            isDoubleFormat = doubleFormat
        )
        val result = pattern.newBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(result.filterIsInstance<ExactValuePattern>().map { it.pattern as NumberValue }.map {
            val actualValue = it.nativeValue as BigDecimal
            if (doubleFormat)
                actualValue.setScale(2, RoundingMode.HALF_UP)
            else
                actualValue
        }).satisfiesExactlyInAnyOrder(
            {
                assertThat(it).isEqualTo(minimum)
            },
            {
                assertThat(it).isEqualTo(maximum)
            }
        )
    }

    @Test
    fun `positive values generated should include only one value when effective min is equal to effective max`() {
        val minimum = BigDecimal(1)
        val maximum = BigDecimal(3)
        val pattern = NumberPattern(
            minimum = minimum,
            maximum = maximum,
            exclusiveMinimum = true,
            exclusiveMaximum = true
        )
        val result = pattern.newBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(result.filterIsInstance<ExactValuePattern>().map { it.pattern as NumberValue }.map {
            it.nativeValue as BigDecimal
        }).containsExactly(BigDecimal("2"))
    }

    @Test
    @Tag(GENERATION)
    fun `negative values generated should include a value greater than minimum and maximum keyword values when isDoubleFormat is true`() {
        val minimum = BigDecimal(10.0)
        val maximum = BigDecimal(20.0)
        val pattern = NumberPattern(
            minimum = minimum,
            maximum = maximum,
            isDoubleFormat = true
        )
        val result = pattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(result).containsExactlyInAnyOrder(
            NullPattern,
            StringPattern(),
            BooleanPattern(),
            ExactValuePattern(NumberValue(minimum - smallInc)),
            ExactValuePattern(NumberValue(maximum + smallInc)),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "1, 10, false",
        "10, 20, false",
        "10.0, 20.0, true",
        "10.00, 20.00, true",
        "0.01, 0.99, true",
    )
    @Tag(GENERATION)
    fun `negative values generated should include a value less than minimum and greater than maximum keyword values`(min: String, max: String, doubleFormat: Boolean) {
        val minimum = BigDecimal(min)
        val maximum = BigDecimal(max)
        val pattern = NumberPattern(
            minimum = minimum,
            maximum = maximum,
            isDoubleFormat = doubleFormat
        )
        val result = pattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()

        result.filterIsInstance<ExactValuePattern>().map { it.pattern as NumberValue }.forEach {
            val decimal = (it.nativeValue as BigDecimal).setScale(2, RoundingMode.HALF_UP)
            assertThat(decimal).isNotEqualTo(minimum)
            assertThat(decimal).isNotEqualTo(maximum)
        }
    }

    @Test
    @Tag(GENERATION)
    fun `negative values generated should include a value greater than minimum and maximum keyword values when isDoubleFormat is false`() {
        val minimum = BigDecimal("10")
        val maximum = BigDecimal("20")
        val pattern = NumberPattern(
            minimum = minimum,
            exclusiveMinimum = true,
            maximum = maximum,
            exclusiveMaximum = true
        )
        val result = pattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(result).containsExactlyInAnyOrder(
            NullPattern,
            StringPattern(),
            BooleanPattern(),
            ExactValuePattern(NumberValue(minimum)),
            ExactValuePattern(NumberValue(maximum)),
        )
    }

    @Test
    @Tag(GENERATION)
    fun `should exclude data type based negatives when withDataTypeNegatives config is false`() {
        val minimum = BigDecimal("10")
        val maximum = BigDecimal("20")
        val pattern = NumberPattern(
            minimum = minimum,
            maximum = maximum,
        )
        val result = pattern.negativeBasedOn(
            Row(),
            Resolver(),
            NegativePatternConfiguration(withDataTypeNegatives = false)
        ).map { it.value }.toList()

        assertThat(result).containsExactlyInAnyOrder(
            ExactValuePattern(NumberValue(minimum - smallInc)),
            ExactValuePattern(NumberValue(maximum + smallInc)),
        )
    }

    @Test
    fun `NumberPattern with no constraints must generate a 3 digit number to ensure that Spring Boot is not able to convert it into an enum`() {
        val number = NumberPattern(isDoubleFormat = false).generate(Resolver()).toStringLiteral()
        assertThat(number).hasSize(3)
    }
}