package io.specmatic.core

import io.specmatic.core.pattern.AnyValuePattern
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class ResolverKtTest {
    @Test
    fun `it should throw an exception when the request pattern does not exist`() {
        assertThatThrownBy { Resolver().getPattern("(NonExistentPattern)") }.isInstanceOf(ContractException::class.java)
    }

    @ParameterizedTest
    @MethodSource("typeAliasToLookupKeyProvider")
    fun `should be able to combine typeAlias and lookupKey to a lookupPath`(typeAlias: String?, lookupKey: String, expectedLookupPath: String) {
        val resolver = Resolver().updateLookupPath(typeAlias, KeyWithPattern(lookupKey, AnyValuePattern))
        assertThat(resolver.dictionaryLookupPath).isEqualTo(expectedLookupPath)
    }

    @Test
    fun `actualPatternMatch should match using the pattern from dataType matcher when in mock mode and return failure`() {
        val resolver = Resolver(mockMode = true)
        val result = resolver.actualPatternMatch(
            null,
            NumberPattern(),
            StringValue("\$match(dataType: string)")
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `actualPatternMatch should match using the pattern from dataType matcher when in mock mode and return success`() {
        val resolver = Resolver(mockMode = true)
        val result = resolver.actualPatternMatch(
            null,
            NumberPattern(),
            StringValue("\$match(dataType: number)")
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `actualPatternMatch should match using the pattern from regex matcher when in mock mode and return success`() {
        val resolver = Resolver(mockMode = true)

        val result = resolver.actualPatternMatch(
            null,
            NumberPattern(),
            StringValue("\$match(pattern: 1)")
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `actualPatternMatch should match using the pattern from equality matcher when in mock mode and return failure`() {
        val resolver = Resolver(mockMode = true)

        val result = resolver.actualPatternMatch(
            null,
            StringPattern(),
            StringValue("\$match(exact: 1)")
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `actualPatternMatch should match using the pattern from equality matcher when in mock mode and return success`() {
        val resolver = Resolver(mockMode = true)

        val result = resolver.actualPatternMatch(
            null,
            ExactValuePattern(NumberValue(1)),
            StringValue("\$match(exact: 1)")
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    companion object {
        @JvmStatic
        fun typeAliasToLookupKeyProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "", ""),
                Arguments.of("", "", ""),
                Arguments.of("Schema", "", "Schema"),
                Arguments.of(null, "key", ".key"),
                Arguments.of("", "key", ".key"),
                Arguments.of(null, "[*]", "[*]"),
                Arguments.of("", "[*]", "[*]"),
                Arguments.of("Schema", "key", "Schema.key"),
                Arguments.of("Schema", "[*]", "Schema[*]"),
            )
        }
    }
}