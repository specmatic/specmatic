package io.specmatic.core.log

import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class StructuredLoggingTest {
    @AfterEach
    fun resetDefaultLogger() {
        setDefaultDiagnosticLogger(NoOpDiagnosticLogger)
        System.clearProperty("SPECMATIC_NEW_LOGGER")
        resetLogger()
    }

    @Test
    fun `new logger switch is enabled only for true values`() {
        System.setProperty("SPECMATIC_NEW_LOGGER", "true")
        assertThat(newLoggingEnabled()).isTrue()

        System.setProperty("SPECMATIC_NEW_LOGGER", "false")
        assertThat(newLoggingEnabled()).isFalse()
    }

    @Test
    fun `legacy logger remains visible when flag is disabled`() {
        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(DebugLogger) {
                logger.log("hello world")
            }
        }

        assertThat(stripAnsi(output)).contains("hello world")
    }

    @Test
    fun `without logging suppresses legacy output when flag is disabled`() {
        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(DebugLogger) {
                withoutLogging {
                    logger.log("hidden")
                }
            }
        }

        assertThat(stripAnsi(output)).isEmpty()
    }

    @Test
    fun `legacy logger is silent when flag is enabled`() {
        System.setProperty("SPECMATIC_NEW_LOGGER", "true")
        resetLogger()

        val (output, _) = captureStandardOutput(trim = false) {
            logger.log("legacy message")
        }

        assertThat(stripAnsi(output)).isEmpty()
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\u001B\\[[;\\d]*m"), "").trim()
    }
}
