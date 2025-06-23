package io.specmatic.core.pattern

import io.specmatic.core.Dictionary
import io.specmatic.core.HttpQueryParamPattern
import io.specmatic.core.QueryParameters
import io.specmatic.core.Resolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryParameterScalarPatternTest {
    @Test
    fun `should be able to fix invalid values`() {
        val pattern = HttpQueryParamPattern(mapOf("email" to QueryParameterScalarPattern(EmailPattern())))
        val dictionary = "PARAMETERS: { QUERY: { email: SomeDude@example.com } }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary)
        val invalidValues = listOf(
            "Unknown",
            "999"
        )

        assertThat(invalidValues).allSatisfy {
            val fixedValue = pattern.fixValue(QueryParameters(mapOf("email" to it)), resolver)
            assertThat(fixedValue.asMap()["email"]).isEqualTo("SomeDude@example.com")
        }
    }

}