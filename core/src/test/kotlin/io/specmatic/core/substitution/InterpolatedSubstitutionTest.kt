package io.specmatic.core.substitution

import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InterpolatedSubstitutionTest {
    @Test
    fun `extracts interpolated placeholder from simple string`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "order-(ORDER_ID:number)",
            running = "order-123"
        )

        assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123"))
    }

    @Test
    fun `extracts multiple interpolated placeholders from simple string`() {
        val result = InterpolatedSubstitution.extractVariables(
            original = "order-(ORDER_ID:number)-item-(ITEM_ID:number)",
            running = "order-123-item-456"
        )

        assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123", "ITEM_ID" to "456"))
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

        assertThat(result).isEqualTo(mapOf("ID" to "123"))
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

        assertThat(result).isEqualTo(mapOf("ID" to "10"))
    }

    @Test
    fun `resolves interpolated lookups`() {
        val result = InterpolatedSubstitution.resolve("prefix-$(ID)-suffix") { token ->
            when (token) {
                "$(ID)" -> "123"
                else -> token
            }
        }

        assertThat(result).isEqualTo("prefix-123-suffix")
    }

    @Test
    fun `detects lookup tokens`() {
        assertThat(InterpolatedSubstitution.containsLookup("order-$(ID)")).isTrue()
        assertThat(InterpolatedSubstitution.containsLookup("prefix-abc")).isFalse()
    }
}
