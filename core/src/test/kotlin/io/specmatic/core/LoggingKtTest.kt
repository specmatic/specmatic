package io.specmatic.core

import io.specmatic.core.log.*
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class LoggingKtTest {
    @Test
    fun `log with no indentation`() {
        val (output, _) = captureStandardOutput(trim = false) {
            InfoLogger.log("Hello")
        }

        assertThat(output.trimEnd()).isEqualTo("Hello")
    }

    @Test
    fun `log with indentation`() {
        val (output, _) = captureStandardOutput(trim = false) {
            InfoLogger.withIndentation(2) {
                InfoLogger.log("Hello")
            }
        }

        assertThat(output.trimEnd()).isEqualTo(" ".repeat(2) + "Hello")
    }

    @Test
    fun `log with nested indentation`() {
        val (output, _) = captureStandardOutput(trim = false) {
            InfoLogger.log("Outer")
            InfoLogger.withIndentation(2) {
                InfoLogger.log("Inner")
                InfoLogger.withIndentation(2) {
                    InfoLogger.log("Innermost")
                }
                InfoLogger.log("Return to inner")
            }
            InfoLogger.log("Return to outer")
        }

        assertThat(output.trimEnd()).isEqualTo("Outer${System.lineSeparator()}  Inner${System.lineSeparator()}    Innermost${System.lineSeparator()}  Return to inner${System.lineSeparator()}Return to outer")
    }

    @Test
    fun `log in one line`() {
        val (output, _) = captureStandardOutput {
            val oneLineLogger = NonVerbose(CompositePrinter(listOf(OneLinePrinter)))
            val json = JSONObjectValue(mapOf("data" to StringValue("information")))
            oneLineLogger.log(json.toStringLiteral())
        }

        assertThat(output).isEqualTo("""{ "data": "information" }""")
    }

    @Test
    fun `render a boundary`() {
        val (output, _) = captureStandardOutput {
            InfoLogger.log("one")
            InfoLogger.boundary()
            InfoLogger.log("two")
        }

        assertThat(output).isEqualTo("one${System.lineSeparator()}${System.lineSeparator()}two")
    }

    @Test
    fun `render a single boundary even though boundary is called twice`() {
        val (output, _) = captureStandardOutput {
            InfoLogger.log("one")
            InfoLogger.boundary()
            InfoLogger.boundary()
            InfoLogger.log("two")
        }

        assertThat(output).isEqualTo("one${System.lineSeparator()}${System.lineSeparator()}two")
    }
}