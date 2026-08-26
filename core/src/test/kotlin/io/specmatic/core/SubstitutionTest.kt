package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class SubstitutionTest {
    @ParameterizedTest(name = "isOrContainsLookup({0}) = {1}")
    @MethodSource("lookupCases")
    fun `detects lookup tokens`(text: String, expected: Boolean) {
        assertThat(Substitution.isOrContainsLookup(text)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "isOrContainsCapture({0}) = {1}")
    @MethodSource("captureCases")
    fun `detects capture tokens`(text: String, expected: Boolean) {
        assertThat(Substitution.isOrContainsCapture(text)).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun lookupCases(): Stream<Arguments> = Stream.of(
            Arguments.of("$(ID)", true),
            Arguments.of("order-123-suffix", false),
            Arguments.of("order-$(ID)-suffix", true),
            Arguments.of($$"$match(contains: $(data.person))", false),
        )

        @JvmStatic
        fun captureCases(): Stream<Arguments> = Stream.of(
            Arguments.of("(ID:number)", true),
            Arguments.of("order-123-suffix", false),
            Arguments.of("order-(ID:string)-suffix", true),
            Arguments.of($$"$match(contains: $(data.person))", false),
        )
    }
}
