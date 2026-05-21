package io.specmatic.conversions

import io.specmatic.core.pattern.ContractException
import io.specmatic.linter.api.SpecmaticLinter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenApiSpecificationLintTest {
    @Test
    fun `should throw on load when lint returns errors in non lenient mode`() {
        val exception = assertThrows<ContractException> {
            OpenApiSpecification.fromYAML(
                openApiFilePath = "inline.yaml",
                lintConfig = SpecmaticLinter.loadConfig(),
                yamlContent = openApiWithQueryStringPath(),
            )
        }

        assertThat(exception.message).contains("path-not-include-query")
    }

    @Test
    fun `should return lint result without collector diagnostics on lint reporting path`() {
        val (feature, lintResult) = OpenApiSpecification.fromYAML(
            lenientMode = true,
            openApiFilePath = "inline.yaml",
            lintConfig = SpecmaticLinter.loadConfig(),
            yamlContent = openApiWithLintWarningAndCollectorRecovery(),
        ).toFeatureWithLintResult()

        assertThat(feature.scenarios).hasSize(1)
        assertThat(lintResult.problems).isNotEmpty()
    }

    private fun openApiWithQueryStringPath() = """
    openapi: 3.0.1
    info:
      title: Broken API
      version: "1"
    paths:
      /orders?status=active:
        get:
          responses:
            '200':
              description: OK
    """.trimIndent()

    private fun openApiWithLintWarningAndCollectorRecovery() = """
    openapi: 3.0.1
    info:
      title: Warning API
      version: "1"
    paths:
      /orders:
        get:
          responses:
            '200':
              description: OK
              content:
                application/json:
                  schema:
                    type: string
                    additionalProperties: true
    """.trimIndent()
}
