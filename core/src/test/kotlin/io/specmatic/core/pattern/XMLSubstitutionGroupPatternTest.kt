package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.XMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class XMLSubstitutionGroupPatternTest {
    private val resolver = Resolver()

    @Test
    fun `matches fails when value is null`() {
        val pattern = petSubstitutionGroup()

        val result = pattern.matches(null, resolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).reportString()).contains("Expected XML but got nothing")
    }

    @Test
    fun `negativeBasedOn delegates to candidate negatives`() {
        val pattern = petSubstitutionGroup()

        val generatedNegativePatterns = pattern.negativeBasedOn(Row(), resolver)
            .map { it.value }
            .toList()

        assertThat(generatedNegativePatterns).isNotEmpty
        assertThat(generatedNegativePatterns)
            .allSatisfy { negativePattern -> assertThat(negativePattern).isInstanceOf(XMLPattern::class.java) }
        assertThat(generatedNegativePatterns.map { (it as XMLPattern).pattern.name })
            .contains("Dog", "Cat")
    }

    private fun petSubstitutionGroup(): XMLSubstitutionGroupPattern {
        return XMLSubstitutionGroupPattern(
            headElementName = "{http://example.com/pets}Pet",
            candidates = listOf(
                XMLPattern("<Dog><name>(string)</name></Dog>"),
                XMLPattern("<Cat><name>(string)</name></Cat>")
            )
        )
    }
}
