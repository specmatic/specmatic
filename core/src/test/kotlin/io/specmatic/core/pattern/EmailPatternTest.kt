package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class EmailPatternTest {
    @Test
    @Tag(GENERATION)
    fun `negative values should be generated`() {
        val result = EmailPattern().negativeBasedOn(Row(), Resolver()).toList().map { it.value }
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null",
            "number",
            "boolean",
            "string"
        )
    }

    @Test
    fun `email should not encompass string`() {
        assertThat(
            EmailPattern().encompasses(StringPattern(), Resolver(), Resolver(), emptySet())
        ).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `fillInTheBlanks should handle any-value pattern token correctly`() {
        val pattern = EmailPattern()
        val resolver = Resolver()
        val value = StringValue("(anyvalue)")

        val filledInValue = pattern.fillInTheBlanks(value, resolver).value
        val matchResult = pattern.matches(filledInValue, resolver)

        assertThat(matchResult.isSuccess()).withFailMessage(matchResult.reportString()).isTrue()
    }
}