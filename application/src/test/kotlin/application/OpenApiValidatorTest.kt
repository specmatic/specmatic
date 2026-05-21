package application

import application.validate.OpenApiValidator
import application.validate.SpecValidationResult
import io.specmatic.core.SpecmaticConfig
import io.specmatic.linter.api.SpecmaticLinter
import io.specmatic.linter.model.LintSeverity
import io.specmatic.linter.model.RuleConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File

class OpenApiValidatorTest {
    @Test
    fun `should use lint result as specification validation output`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("warning.yaml").apply {
            writeText("""
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
            """.trimIndent())
        }

        val baseConfig = SpecmaticLinter.loadConfig(workingDirectory = tempDir.toPath())
        val warningOnlyConfig = baseConfig.copy(
            lintConfig = baseConfig.lintConfig.copy(
                rules = baseConfig.lintConfig.rules + mapOf(
                    "security-defined" to RuleConfig.SeverityOnly(LintSeverity.warn),
                    "operation-operationId" to RuleConfig.SeverityOnly(LintSeverity.warn),
                    "operation-summary" to RuleConfig.SeverityOnly(LintSeverity.warn),
                )
            )
        )

        val result = OpenApiValidator().validateSpecification(specification = specFile, specmaticConfig = SpecmaticConfig(), linterConfig = warningOnlyConfig)
        assertThat(result).isInstanceOf(SpecValidationResult.LinterResult::class.java)

        val validationResult = result as SpecValidationResult.LinterResult
        assertThat(validationResult.result.totals.warnings).isGreaterThan(0)
        assertThat(validationResult.result.problems.map { it.ruleId }).contains("operation-summary")
    }
}
