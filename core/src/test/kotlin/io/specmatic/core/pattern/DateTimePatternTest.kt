package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import io.specmatic.shouldNotMatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

internal class DateTimePatternTest {
    private val pattern = DateTimePattern()

    @Test
    fun `should parse a valid datetime value`() {
        val dateString = RFC3339.currentDateTime()
        val dateValue = pattern.parse(dateString, Resolver())

        assertThat(dateValue.string).isEqualTo(dateString)
    }

    @Test
    fun `should generate a datetime value which can be parsed`() {
        val valueGenerated = pattern.generate(Resolver())
        val valueParsed = pattern.parse(valueGenerated.string, Resolver())

        assertThat(valueParsed).isEqualTo(valueGenerated)
    }

    @Test
    fun `should match a valid datetime value`() {
        val valueGenerated = pattern.generate(Resolver())
        valueGenerated shouldMatch pattern
    }

    @Test
    fun `should fail to match an invalid datetime value`() {
        val valueGenerated = StringValue("this is not a datetime value")
        valueGenerated shouldNotMatch pattern
    }

    @Test
    fun `should return itself when generating a new pattern based on a row`() {
        val datePatterns = pattern.newBasedOn(Row(), Resolver()).map { it.value as DateTimePattern }.toList()
        assertThat(datePatterns.size).isEqualTo(1)
        assertThat(datePatterns.first()).isEqualTo(pattern)
    }

    @ParameterizedTest
    @MethodSource("getRFC3339CompliantDateTimeData")
    fun `should match RFC3339 date time format`(dateTime: String) {
        assertThat(pattern.matches(
            StringValue(dateTime),
            Resolver()
        )).isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @MethodSource("getRFC3339NonCompliantDateTimeData")
    fun `should fail if the dateTime is not RFC3339 compliant`(dateTime: String) {
        assertThat(
            pattern.matches(
                StringValue(dateTime),
                Resolver()
            )
        ).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    @Tag(GENERATION)
    fun `negative patterns should be generated`() {
        val result = pattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder("string", "number", "boolean", "null")
    }

    @Test
    fun `should use the provided datetime example during generation`() {
        val example = "2024-05-01T12:34:56Z"
        val patternWithExample = DateTimePattern(example = example)

        val generated = patternWithExample.generate(Resolver())

        assertThat(generated.string).isEqualTo(example)
    }

    companion object {
        @JvmStatic
        fun getRFC3339CompliantDateTimeData(): List<String> {
           return listOf(
               "2020-04-12T00:00:00+05:30",
               "2014-12-03T10:05:59+08:00",
               "2024-04-25T09:06:26Z",
               "2024-04-25T09:06:26.57Z",
               "2024-04-25T09:06:26.5732Z",
               "2024-04-25T09:06:26.572577123456Z"
           )
        }

        @JvmStatic
        fun getRFC3339NonCompliantDateTimeData(): List<String> {
            return listOf(
                "2024-04-25 09:06:26Z",
                "2024/04/25T09:06:26Z",
                "25-04-2024T09:06:26Z",
            )
        }
    }
}
