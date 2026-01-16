package io.specmatic.core

import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.LogPrinter
import io.specmatic.core.log.NonVerbose
import io.specmatic.core.log.withLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class SpecmaticConfigLoggingTest {
    private class CapturingPrinter : LogPrinter {
        val logged: MutableList<LogMessage> = mutableListOf()

        override fun print(msg: LogMessage, indentation: String) {
            logged.add(msg)
        }
    }

    @Test
    fun `missing inferred config does not log when using non-verbose logger`(@TempDir tempDir: File) {
        val printer = CapturingPrinter()
        val logStrategy = NonVerbose(CompositePrinter(listOf(printer)))
        val missingConfig = File(tempDir, "specmatic.yaml")

        withLogger(logStrategy) {
            loadSpecmaticConfigOrNull(missingConfig.path)
        }

        assertThat(printer.logged).isEmpty()
    }

    @Test
    fun `missing explicit config logs message`(@TempDir tempDir: File) {
        val printer = CapturingPrinter()
        val logStrategy = NonVerbose(CompositePrinter(listOf(printer)))
        val missingConfig = File(tempDir, "specmatic.yaml")

        withLogger(logStrategy) {
            loadSpecmaticConfigOrNull(missingConfig.path, explicitlySpecifiedByUser = true)
        }

        val logged = printer.logged.single()
        assertThat(logged.toLogString()).contains("Could not find the Specmatic configuration at path")
    }
}
