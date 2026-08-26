package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class DotPatternOpenApiTest {
    data class Case(
        val name: String,
        val pattern: String,
        val legalSample: String,
        val newlineSample: String,
        val newlineMustFail: Boolean,
        val generatedMustOmitLineTerminators: Boolean,
        val generatedMustContainDot: Boolean,
    ) {
        override fun toString(): String = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `OpenAPI generation and StringPattern matches agree for each dot permutation`(case: Case) {
        val pattern = StringPattern(maxLength = 32, regex = case.pattern)
        val resolver = Resolver()

        assertThat(pattern.matches(StringValue(case.legalSample), resolver).isSuccess()).isTrue()
        assertThat(pattern.matches(StringValue(case.newlineSample), resolver).isSuccess())
            .isEqualTo(!case.newlineMustFail)

        val feature = OpenApiSpecification.fromYAML(requestSpec(case.pattern), "").toFeature()
        var generated: String? = null
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                generated = (request.body as JSONObjectValue).getString("value")
                return HttpResponse(204)
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        val value = generated ?: error("no generated value for ${case.name}")
        assertThat(pattern.matches(StringValue(value), resolver).isSuccess()).isTrue()
        assertThat(value).matches(javaRegex(case.pattern))
        if (case.generatedMustOmitLineTerminators) {
            assertThat(value.none(::isJavaLineTerminator)).isTrue()
        }
        if (case.generatedMustContainDot) {
            assertThat(value).contains(".")
        }
    }

    companion object {
        @JvmStatic
        fun cases(): Stream<Case> = Stream.of(
            Case("bare-dot", ".", "x", "\n", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("anchored-any", "^a.b$", "axb", "a\nb", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("star", "^a.*z$", "abcz", "a\nz", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("plus", "^a.+z$", "axz", "a\nz", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("exactly-3", "^.{3}$", "abc", "a\nb", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("bounded", "^.{1,3}$", "ab", "\n", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("lower-bound", "^.{,2}$", "ab", "\n", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("escaped", "^a\\.b$", "a.b", "a\nb", newlineMustFail = true, generatedMustOmitLineTerminators = false, generatedMustContainDot = true),
            Case("class-only", "^[.]$", ".", "\n", newlineMustFail = true, generatedMustOmitLineTerminators = false, generatedMustContainDot = true),
            Case("class-mixed", "^[ab.]$", ".", "\n", newlineMustFail = true, generatedMustOmitLineTerminators = false, generatedMustContainDot = false),
            Case("negated-class", "^[^.]$", "x", "\n", newlineMustFail = false, generatedMustOmitLineTerminators = false, generatedMustContainDot = false),
            Case("version", "^v\\d{3}\\.\\d{3}$", "v123.456", "v123\n456", newlineMustFail = true, generatedMustOmitLineTerminators = false, generatedMustContainDot = true),
            Case("non-capturing", "^(?:a.b)$", "axb", "a\nb", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
            Case("quoted", "^\\Q.\\E$", ".", "\n", newlineMustFail = true, generatedMustOmitLineTerminators = false, generatedMustContainDot = true),
            Case("alternation", "^(a.b|x\\.y)$", "x.y", "a\nb", newlineMustFail = true, generatedMustOmitLineTerminators = true, generatedMustContainDot = false),
        )

        private fun javaRegex(pattern: String): String =
            pattern.replace(Regex("""\{,(\d+)}"""), "{0,$1}")

        private fun isJavaLineTerminator(ch: Char): Boolean =
            ch == '\n' || ch == '\r' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029'

        private fun yamlPattern(pattern: String): String = "'" + pattern.replace("'", "''") + "'"

        private fun requestSpec(pattern: String): String = """
            ---
            openapi: "3.0.1"
            info:
              title: "Dot pattern"
              version: "1"
            paths:
              /dot:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [value]
                          properties:
                            value:
                              type: string
                              maxLength: 32
                              pattern: ${yamlPattern(pattern)}
                  responses:
                    '204':
                      description: ok
        """.trimIndent()
    }
}
