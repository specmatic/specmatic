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
import java.time.LocalDate

internal class DatePatternTest {
    private val pattern = DatePattern()

    @Test
    fun `should parse a valid date value`() {
        val dateString = LocalDate.now().format(RFC3339.dateFormatter)
        val dateValue = pattern.parse(dateString, Resolver())

        assertThat(dateValue.string).isEqualTo(dateString)
    }

    @Test
    fun `should generate a date value which can be parsed`() {
        val valueGenerated = pattern.generate(Resolver())
        val valueParsed = pattern.parse(valueGenerated.string, Resolver())

        assertThat(valueParsed).isEqualTo(valueGenerated)
    }

    @Test
    fun `should match a valid date value`() {
        val valueGenerated = pattern.generate(Resolver())
        valueGenerated shouldMatch pattern
    }

    @Test
    fun `should fail to match an invalid date value`() {
        val valueGenerated = StringValue("this is not a date value")
        valueGenerated shouldNotMatch pattern
    }

    @Test
    fun `should return itself when generating a new pattern based on a row`() {
        val datePatterns = pattern.newBasedOn(Row(), Resolver()).map { it.value as DatePattern }.toList()
        assertThat(datePatterns.size).isEqualTo(1)
        assertThat(datePatterns.first()).isEqualTo(pattern)
    }

    @Test
    fun `should match RFC3339 date format`() {
        val date1 = StringValue("2020-04-12")
        val date2 = StringValue("2020-04-22")

        assertThat(pattern.matches(date1, Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(date2, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should use the provided example during generation`() {
        val example = "2024-05-01"
        val patternWithExample = DatePattern(example = example)

        val generated = patternWithExample.generate(Resolver())

        assertThat(generated.string).isEqualTo(example)
    }


    @Test
    @Tag(GENERATION)
    fun `negative patterns should be generated`() {
        val result = pattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder("string", "number", "boolean", "null")
    }
}
