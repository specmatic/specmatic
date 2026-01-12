package io.specmatic.core.utilities

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OpenApiPathTest {
    @ParameterizedTest(name = "normalize {0} → {1}")
    @MethodSource("numericNormalizeCases")
    fun `normalize numeric path segments`(path: String, expected: List<String>) {
        val normalized = OpenApiPath.from(path).normalize()
        assertThat(parts(normalized)).containsExactlyElementsOf(expected)
    }

    @ParameterizedTest(name = "normalize ignores non numeric segment {0}")
    @MethodSource("nonNumericNormalizeCases")
    fun `normalize ignores non numeric segments`(path: String) {
        val normalized = OpenApiPath.from(path).normalize()
        assertThat(parts(normalized)).containsExactlyElementsOf(path.trim('/').split('/'))
    }

    @ParameterizedTest(name = "toPath {0} → {1}")
    @MethodSource("toPathCases")
    fun `toPath renders parameters correctly`(parts: List<String>, expected: String) {
        val path = OpenApiPath(parts)
        assertThat(path.toPath()).isEqualTo(expected)
    }

    private fun parts(path: OpenApiPath): List<String> {
        @Suppress("UNCHECKED_CAST")
        return OpenApiPath::class.java.getDeclaredField("parts").apply { isAccessible = true }.get(path) as List<String>
    }

    companion object {
        @JvmStatic
        fun numericNormalizeCases() = listOf(
            // int max / min
            "/users/2147483647" to listOf("users", "(param:integer)"),
            "/users/2147483648" to listOf("users", "(param:integer)"),

            // long max / min
            "/users/9223372036854775807" to listOf("users", "(param:integer)"),
            "/users/9223372036854775808" to listOf("users", "(param:integer)"),

            // decimal values
            "/price/0.1" to listOf("price", "(param:number)"),
            "/price/10.0001" to listOf("price", "(param:number)"),
            "/metrics/1/2.5" to listOf("metrics", "(param1:integer)", "(param2:number)")
        ).map { org.junit.jupiter.params.provider.Arguments.of(it.first, it.second) }

        @JvmStatic
        fun nonNumericNormalizeCases() = listOf(
            "/users/01a",
            "/users/1a",
            "/users/a1",
            "/users/1.2.3",
            "/users/.",
            "/users/1.",
            "/users/.1",
            "/users/-1",
            "/users/+1"
        ).map { org.junit.jupiter.params.provider.Arguments.of(it) }

        @JvmStatic
        fun toPathCases() = listOf(
            listOf("users", "profile") to "/users/profile",
            listOf("(param:integer)") to "/{param}",
            listOf("(param1:integer)", "orders", "(param2:number)") to "/{param1}/orders/{param2}",
            listOf("(param99:number)") to "/{param99}"
        ).map { org.junit.jupiter.params.provider.Arguments.of(it.first, it.second) }
    }
}
