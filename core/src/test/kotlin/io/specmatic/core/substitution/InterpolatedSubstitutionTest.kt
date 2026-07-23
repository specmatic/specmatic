package io.specmatic.core.substitution

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class InterpolatedSubstitutionTest {
    @Test
    fun `extracts interpolated placeholder from simple string`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "order-(ORDER_ID:number)",
            running = "order-123"
        )

        assertThat(result).isEqualTo(mapOf("ORDER_ID" to NumberValue(123)))
    }

    @Test
    fun `extracts multiple interpolated placeholders from simple string`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "order-(ORDER_ID:number)-item-(ITEM_ID:number)",
            running = "order-123-item-456"
        )

        assertThat(result).isEqualTo(mapOf("ORDER_ID" to NumberValue(123), "ITEM_ID" to NumberValue(456)))
    }

    @Test
    fun `fails when interpolated string does not match`() {
        assertThatThrownBy {
            InterpolatedSubstitution.extractVariables(
                original = "prefix-(ID:number)-suffix",
                running = "does-not-match"
            )
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Could not extract substitution variables")
    }

    @Test
    fun `returns empty map when original has no placeholder`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "prefix-abc-suffix",
            running = "prefix-123-suffix"
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `supports ordinary parentheses without treating them as variables`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "hello (world) id-(ID:number)",
            running = "hello (world) id-123"
        )

        assertThat(result).isEqualTo(mapOf("ID" to NumberValue(123)))
    }

    @Test
    fun `does not extract from ordinary parentheses`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "hello (world)",
            running = "hello changed"
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `does not extract placeholders from dollar function calls`() {
        val result = InterpolatedSubstitution.extractVariables(original = $$"$match(exact: 123)", running = "123")
        assertThat(result).isEmpty()
    }

    @Test
    fun `fails for adjacent placeholders`() {
        assertThatThrownBy {
            InterpolatedSubstitution.extractVariables(
                original = "(A:string)(B:string)",
                running = "onetwo"
            )
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Ambiguous interpolation")
    }

    @Test
    fun `strict extraction fails for conflicting duplicate variables`() {
        assertThatThrownBy {
            InterpolatedSubstitution.extractVariables(
                original = "(ID:number)-again-(ID:number)",
                running = "10-again-20"
            )
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Conflicting extracted values")
    }

    @Test
    fun `strict extraction allows duplicate variables with same value`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "(ID:number)-again-(ID:number)",
            running = "10-again-10"
        )

        assertThat(result).isEqualTo(mapOf("ID" to NumberValue(10)))
    }

    @Test
    fun `resolves interpolated lookups`() {
        val result = InterpolatedSubstitution.resolve("prefix-$(ID)-suffix") { token ->
            when (token) {
                "$(ID)" -> StringValue("123")
                else -> StringValue(token)
            }
        }

        assertThat(result).isEqualTo(StringValue("prefix-123-suffix"))
    }

    @Test
    fun `returns resolved value unchanged for exact lookup token`() {
        val result = InterpolatedSubstitution.resolve("$(ID)") { token ->
            when (token) {
                "$(ID)" -> NumberValue(123)
                else -> StringValue(token)
            }
        }

        assertThat(result).isEqualTo(NumberValue(123))
    }

    @Test
    fun `uses unformatted string for embedded non string lookup value`() {
        val result = InterpolatedSubstitution.resolve("prefix-$(ID)-suffix") { token ->
            when (token) {
                "$(ID)" -> NumberValue(123)
                else -> StringValue(token)
            }
        }

        assertThat(result).isEqualTo(StringValue("prefix-123-suffix"))
    }

    @Test
    fun `detects ordinary exact and embedded lookup tokens`() {
        assertThat(InterpolatedSubstitution.isLookup("$(ID)")).isTrue()
        assertThat(InterpolatedSubstitution.containsLookup("$(ID)")).isTrue()
        assertThat(InterpolatedSubstitution.isLookup("order-$(ID)")).isFalse()
        assertThat(InterpolatedSubstitution.containsLookup("order-$(ID)")).isTrue()
        assertThat(InterpolatedSubstitution.containsLookup("prefix-abc")).isFalse()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\$match(contains: \$(data.person))",
            "\$match(contains: [\$(data.person), \$(data.company)])",
        ]
    )
    fun `does not detect matcher references as substitution lookups`(matcherExpression: String) {
        assertThat(StringValue(matcherExpression).hasMatcherTemplate()).isTrue()
        assertThat(InterpolatedSubstitution.isLookup(matcherExpression)).isFalse()
        assertThat(InterpolatedSubstitution.containsLookup(matcherExpression)).isFalse()
    }

    @Test
    fun `returns complete matcher expression unchanged without resolving its references`() {
        val matcherExpression = "\$match(contains: \$(data.person))"
        var tokenResolverInvoked = false

        val result = InterpolatedSubstitution.resolve(matcherExpression) {
            tokenResolverInvoked = true
            StringValue("resolved")
        }

        assertThat(result).isEqualTo(StringValue(matcherExpression))
        assertThat(tokenResolverInvoked).isFalse()
    }

    @Test
    fun `does not protect matcher shaped text rejected by the existing matcher recognizer`() {
        val incompleteMatcherExpression = "\$match(contains: \$(data.person) "
        val resolvedTokens = mutableListOf<String>()

        assertThat(StringValue(incompleteMatcherExpression).hasMatcherTemplate()).isFalse()
        assertThat(InterpolatedSubstitution.containsLookup(incompleteMatcherExpression)).isTrue()

        val result = InterpolatedSubstitution.resolve(incompleteMatcherExpression) { token ->
            resolvedTokens.add(token)
            StringValue("resolved")
        }

        assertThat(result).isEqualTo(StringValue("\$match(contains: resolved "))
        assertThat(resolvedTokens).containsExactly("$(data.person)")
    }

    @Nested
    inner class TypedExtraction {
        @Test
        fun `parses built in boolean type`() {
            val result = InterpolatedSubstitution.extractVariables(
                original = "flag-(FLAG:boolean)",
                running = "flag-true"
            )

            assertThat(result).isEqualTo(mapOf("FLAG" to BooleanValue(true)))
        }

        @Test
        fun `falls back to string value when built in type parse fails`() {
            val result = InterpolatedSubstitution.extractVariables(
                original = "id-(ID:number)",
                running = "id-abc"
            )

            assertThat(result).isEqualTo(mapOf("ID" to StringValue("abc")))
        }

        @Test
        fun `parses custom resolver type not in built ins`() {
            val resolver = Resolver(newPatterns = mapOf("(special)" to NumberPattern()))
            val result = InterpolatedSubstitution.extractVariables(
                original = "custom-(CUSTOM:special)",
                running = "custom-42",
                resolver = resolver
            )

            assertThat(result).isEqualTo(mapOf("CUSTOM" to NumberValue(42)))
        }
    }
}
