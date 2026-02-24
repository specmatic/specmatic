package io.specmatic.core.filters

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ExpressionStandardizerTest {


    @Test
    fun `filterToEvalx should return empty string for empty filter`() {
        assertThat(ExpressionStandardizer.filterToEvalEx("").evaluate().booleanValue).isTrue
        assertThat(ExpressionStandardizer.filterToEvalEx("\t").evaluate().booleanValue).isTrue
        assertThat(ExpressionStandardizer.filterToEvalEx(" \r\n   \t").evaluate().booleanValue).isTrue
    }

    @ParameterizedTest
    @MethodSource("validExpressionsProvider")
    fun `should tokenize expressions`(expression: String, expected: String) {
        assertThat(ExpressionStandardizer().tokenizeExpression(expression)).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("invalidExpressionsProvider")
    fun `should throw error`(expression: String) {
        assertThatCode { ExpressionStandardizer().tokenizeExpression(expression) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unsupported keys should be dropped when projecting filter for a limited context`() {
        val expression = ExpressionStandardizer.filterToEvalExForSupportedKeys(
            "EXAMPLE-NAME!='SUCCESS'",
            TestRecordFilter::supportsFilterKey
        )

        assertThat(expression.evaluate().booleanValue).isTrue
    }

    @ParameterizedTest
    @MethodSource("projectionCasesProvider")
    fun `projection should keep supported-key behavior`(
        filterExpression: String,
        matchingValuesByKey: Map<String, Set<String>>
    ) {
        val expression = ExpressionStandardizer.filterToEvalExForSupportedKeys(
            filterExpression,
            TestRecordFilter::supportsFilterKey
        )

        val contextWithSupportedKeyMatch = object : FilterContext {
            override fun includes(key: String, values: List<String>): Boolean =
                matchingValuesByKey[key]?.intersect(values.toSet())?.isNotEmpty() == true

            override fun compare(filterKey: String, operator: String, filterValue: String): Boolean = true
        }

        val contextWithNoSupportedKeyMatches = object : FilterContext {
            override fun includes(key: String, values: List<String>): Boolean = false
            override fun compare(filterKey: String, operator: String, filterValue: String): Boolean = true
        }

        assertThat(expression.with("context", contextWithSupportedKeyMatch).evaluate().booleanValue).isTrue
        assertThat(expression.with("context", contextWithNoSupportedKeyMatches).evaluate().booleanValue).isFalse
    }

    companion object {
        @JvmStatic
        private fun validExpressionsProvider(): List<Arguments> {
            return listOf(
                Arguments.of("", ""),
                Arguments.of("FOO='bar'", "includes('FOO', 'bar')"),
                Arguments.of("FOO = 'one,two'", "includes('FOO', 'one', 'two')"),
                Arguments.of("FOO = 'one, two, three'", "includes('FOO', 'one', 'two', 'three')"),
                Arguments.of("FOO!='one,two'", "!includes('FOO', 'one', 'two')"),
                Arguments.of("FOO!='bar'", "!includes('FOO', 'bar')"),
                Arguments.of("FOO = 'bar' && BOO='baz'", "includes('FOO', 'bar') && includes('BOO', 'baz')"),
                Arguments.of("FOO='bar' && !(BOO='baz')", "includes('FOO', 'bar') && ! ( includes('BOO', 'baz') )"),
                Arguments.of("FOO='bar' || BOO = 'baz'", "includes('FOO', 'bar') || includes('BOO', 'baz')"),
                Arguments.of("A.B ='c,d'", "includes('A.B', 'c', 'd')"),
                Arguments.of("A-B='c,d'", "includes('A-B', 'c', 'd')"),
                Arguments.of("A_B= 'c,d'", "includes('A_B', 'c', 'd')"),
                Arguments.of("A >'200'", "eFunc('A', '>', '200')"),
                Arguments.of("A>= '200'", "eFunc('A', '>=', '200')"),
                Arguments.of("PATH = '/foo/bar'", "includes('PATH', '/foo/bar')"),
            )
        }

        @JvmStatic
        private fun invalidExpressionsProvider(): List<String> {
            return listOf(
                "FOO>='1,2'",
                "FOO<='1,2'",
                "FOO>'1,2'",
                "FOO<'1,2'",

                // with whitespaces around the operator
                "FOO >='1,2'",
                "FOO<=  '1,2'",
                "FOO>\t'1,2'",
                """FOO
                        <
                    '1,2'
                """.trimIndent(),
            )
        }

        @JvmStatic
        private fun projectionCasesProvider(): List<Arguments> {
            return listOf(
                Arguments.of(
                    "PATH='/orders' && EXAMPLE-NAME!='SUCCESS'",
                    mapOf("PATH" to setOf("/orders"))
                ),
                Arguments.of(
                    "METHOD='POST' || EXAMPLE-NAME='SUCCESS'",
                    mapOf("METHOD" to setOf("POST"))
                ),
                Arguments.of(
                    "!(EXAMPLE-NAME='SUCCESS') && METHOD='POST'",
                    mapOf("METHOD" to setOf("POST"))
                ),
                Arguments.of(
                    "(METHOD='POST' || PATH='/orders') && EXAMPLE-NAME!='SUCCESS'",
                    mapOf("METHOD" to setOf("POST"), "PATH" to setOf("/orders"))
                )
            )
        }
    }
}
