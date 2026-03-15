package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

internal class ConsolePrinterTest {
    @BeforeEach
    fun resetRunContext() {
        LogContext.startRun(command = "unspecified", component = "specmatic", runId = "test-run")
    }

    @Test
    fun `should print to standard output`() {
        val output = captureConsoleOutput {
            ConsolePrinter.print(StringLog("hello"))
        }

        assertThat(output).contains("hello")
    }

    @Test
    fun `should colorize warnings`() {
        val output = captureConsoleOutput {
            ConsolePrinter.print(StringLog("WARNING: disk is almost full"))
        }

        assertThat(output).contains("[!] WARNING:")
    }

    @Test
    fun `should colorize errors`() {
        val output = captureConsoleOutput {
            ConsolePrinter.print(StringLog("ERROR: plain"))
        }

        assertThat(output).contains("[x] ERROR:")
    }

    @Test
    fun `should print command context from run metadata`() {
        LogContext.startRun(command = "mock", component = "application", runId = "run-1")

        val output = captureConsoleOutput {
            ConsolePrinter.print(StringLog("started"))
        }

        assertThat(output).contains("[mock] started")
    }

    private fun captureConsoleOutput(block: () -> Unit): String {
        val originalOut = System.out
        val stream = ByteArrayOutputStream()
        val newOut = PrintStream(stream)
        System.setOut(newOut)

        return try {
            block()
            System.out.flush()
            stream.toString().trim()
        } finally {
            System.setOut(originalOut)
        }
    }
}
