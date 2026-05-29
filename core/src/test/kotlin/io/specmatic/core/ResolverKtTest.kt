package io.specmatic.core

import io.specmatic.core.pattern.AnyValuePattern
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.ContractException
import io.specmatic.test.asserts.WILDCARD_INDEX
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

    @ParameterizedTest
    @MethodSource("lookupPathSegmentsProvider")
    fun `should parse lookup path segments same as old split logic`(path: String) {
        val segments = Resolver().lookupPathSegments(path)
        assertThat(segments).isEqualTo(
            path
                .replace(WILDCARD_INDEX, "|$WILDCARD_INDEX|")
                .split(".", "|")
                .filter(String::isNotEmpty)
        )
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

        @JvmStatic
        fun lookupPathSegmentsProvider(): Stream<Arguments> {
            return Stream.of(
                // Empty
                Arguments.of("", listOf<String>()),
                Arguments.of(".", listOf<String>()),
                Arguments.of("..", listOf<String>()),
                Arguments.of("...", listOf<String>()),

                // Single tokens
                Arguments.of("[*]", listOf("[*]")),
                Arguments.of("schema", listOf("schema")),

                // Simple dot splits
                Arguments.of("schema.field", listOf("schema", "field")),
                Arguments.of(".field", listOf("field")),
                Arguments.of("schema.", listOf("schema")),
                Arguments.of("schema..field", listOf("schema", "field")),
                Arguments.of("a...b", listOf("a", "b")),

                // Wildcard only / edges
                Arguments.of("[*].field", listOf("[*]", "field")),
                Arguments.of("field.[*]", listOf("field", "[*]")),
                Arguments.of("[*].[*]", listOf("[*]", "[*]")),
                Arguments.of("[*].[*].[*]", listOf("[*]", "[*]", "[*]")),

                // Wildcard with segments
                Arguments.of("schema[*]", listOf("schema", "[*]")),
                Arguments.of("schema[*].field", listOf("schema", "[*]", "field")),
                Arguments.of("schema.field[*].name", listOf("schema", "field", "[*]", "name")),

                // Multiple wildcards
                Arguments.of("schema[*][*]", listOf("schema", "[*]", "[*]")),
                Arguments.of("schema[*][*][*]", listOf("schema", "[*]", "[*]", "[*]")),
                Arguments.of("[*][*]", listOf("[*]", "[*]")),
                Arguments.of("a[*][*][*].b", listOf("a", "[*]", "[*]", "[*]", "b")),

                // Mixed sequences
                Arguments.of("a[*].b[*].c", listOf("a", "[*]", "b", "[*]", "c")),

                // Wildcards inside segment (no dot separation)
                Arguments.of("a[*]b", listOf("a", "[*]", "b")),
                Arguments.of("a[*]b[*]c", listOf("a", "[*]", "b", "[*]", "c")),
                Arguments.of("[*]a[*]b", listOf("[*]", "a", "[*]", "b")),
                Arguments.of("a[*]b[*]c[*]d", listOf("a", "[*]", "b", "[*]", "c", "[*]", "d")),

                // Mixed edge + dots
                Arguments.of("a..[*]..b", listOf("a", "[*]", "b")),
            )
        }
    }
}
