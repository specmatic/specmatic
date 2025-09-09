// filepath: /Users/joelrosario/Source/specmatic/specmatic/core/src/test/kotlin/io/specmatic/conversions/StringConstraintsTest.kt
package io.specmatic.conversions

import io.specmatic.stub.captureStandardOutput
import io.swagger.v3.oas.models.media.StringSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StringConstraintsTest {
    private val FOUR_MB = 4 * 1024 * 1024

    @Test
    fun `rightSizedLength returns null and false for null length and prints nothing`() {
        val (output, result) = captureStandardOutput { rightSizedLength(null, "maxLength", "crumb") }
        assertThat(result.first).isNull()
        assertThat(result.second).isFalse()
        assertThat(output).isBlank()
    }

    @Test
    fun `rightSizedLength under or equal to limit returns unchanged with no warning`() {
        val exactLimit = FOUR_MB
        val under = 123

        val (outUnder, resUnder) = captureStandardOutput { rightSizedLength(under, "minLength", "path.to.field") }
        assertThat(resUnder.first).isEqualTo(under)
        assertThat(resUnder.second).isFalse()
        assertThat(outUnder).isBlank()

        val (outExact, resExact) = captureStandardOutput { rightSizedLength(exactLimit, "maxLength", "path.to.field") }
        assertThat(resExact.first).isEqualTo(exactLimit)
        assertThat(resExact.second).isFalse()
        assertThat(outExact).isBlank()
    }

    @Test
    fun `rightSizedLength above limit returns 4MB and true and logs a warning`() {
        val tooLarge = FOUR_MB + 1
        val (output, result) = captureStandardOutput { rightSizedLength(tooLarge, "maxLength", "Example.Field") }

        assertThat(result.first).isEqualTo(FOUR_MB)
        assertThat(result.second).isTrue()
        assertThat(output).contains("WARNING: The maxLength of $tooLarge for Example.Field is very large")
        assertThat(output).contains("more reasonable maxLength of 4MB")
        assertThat(output).contains("Boundary testing will not be done")
    }

    @Test
    fun `StringConstraints downsamples too large min and max and logs warnings with breadcrumb`() {
        val tooLarge = FOUR_MB + 100
        val schema = StringSchema().apply {
            maxLength = tooLarge
            minLength = tooLarge
        }

        val (output, constraints) = captureStandardOutput { StringConstraints(schema, patternName = "", breadCrumb = "user.name") }

        assertThat(constraints.resolvedMaxLength).isEqualTo(FOUR_MB)
        assertThat(constraints.downsampledMax).isTrue()
        assertThat(constraints.resolvedMinLength).isEqualTo(FOUR_MB)
        assertThat(constraints.downsampledMin).isTrue()

        assertThat(output).contains("maxLength of $tooLarge for user.name")
        assertThat(output).contains("minLength of $tooLarge for user.name")
        assertThat(output).contains("4MB")
    }

    @Test
    fun `StringConstraints uses patternName in breadcrumb when provided`() {
        val tooLarge = FOUR_MB + 42
        val schema = StringSchema().apply { maxLength = tooLarge }

        val (output, constraints) = captureStandardOutput { StringConstraints(schema, patternName = "MyPattern", breadCrumb = "ignored.breadcrumb") }

        assertThat(constraints.resolvedMaxLength).isEqualTo(FOUR_MB)
        assertThat(constraints.downsampledMax).isTrue()
        assertThat(output).contains("for schema MyPattern")
        assertThat(output).doesNotContain("ignored.breadcrumb")
    }

    @Test
    fun `StringConstraints keeps reasonable values and prints nothing`() {
        val schema = StringSchema().apply {
            minLength = 1
            maxLength = 100
        }

        val (output, constraints) = captureStandardOutput { StringConstraints(schema, patternName = "", breadCrumb = "account.id") }

        assertThat(constraints.resolvedMinLength).isEqualTo(1)
        assertThat(constraints.downsampledMin).isFalse()
        assertThat(constraints.resolvedMaxLength).isEqualTo(100)
        assertThat(constraints.downsampledMax).isFalse()
        assertThat(output).isBlank()
    }

    @Test
    fun `StringConstraints handles null min and max with no warnings`() {
        val schema = StringSchema() // min and max default to null

        val (output, constraints) = captureStandardOutput { StringConstraints(schema, patternName = "", breadCrumb = "any.path") }

        assertThat(constraints.resolvedMinLength).isNull()
        assertThat(constraints.downsampledMin).isFalse()
        assertThat(constraints.resolvedMaxLength).isNull()
        assertThat(constraints.downsampledMax).isFalse()
        assertThat(output).isBlank()
    }
}

