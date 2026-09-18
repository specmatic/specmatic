package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class AgentConsoleLoggerTest {
    private val originalLogger = logger

    private val suppressibleKind = object : ConsoleLogEmissionPolicy {
        override val suppressUnderAgent: Boolean = true
    }

    @AfterEach
    fun restoreLogger() {
        logger = originalLogger
    }

    @Test
    fun `Default kind prints under agent logger`() {
        val output = captureStdout {
            withAgentConsoleLogger {
                consoleLog("keep-me", ConsoleLogKind.Default)
            }
        }
        assertThat(output).contains("keep-me")
    }

    @Test
    fun `HttpDump kind is suppressed under agent logger`() {
        val output = captureStdout {
            withAgentConsoleLogger {
                consoleLog("quiet-dump", ConsoleLogKind.HttpDump)
            }
        }
        assertThat(output).doesNotContain("quiet-dump")
    }

    @Test
    fun `HttpDump kind prints when agent logger is not installed`() {
        val output = captureStdout {
            consoleLog("loud-dump", ConsoleLogKind.HttpDump)
        }
        assertThat(output).contains("loud-dump")
    }

    @Test
    fun `withAgentConsoleLogger restores previous logger`() {
        val before = logger
        withAgentConsoleLogger {
            assertThat(logger).isInstanceOf(AgentConsoleLogger::class.java)
        }
        assertThat(logger).isSameAs(before)
    }

    @Test
    fun `setLoggerUsing preserves agent wrapper`() {
        withAgentConsoleLogger {
            setLoggerUsing(io.specmatic.core.config.LoggingConfiguration.default())
            assertThat(logger).isInstanceOf(AgentConsoleLogger::class.java)
            val output = captureStdout {
                consoleLog("still-quiet", suppressibleKind)
            }
            assertThat(output).doesNotContain("still-quiet")
        }
    }

    @Test
    fun `shouldPrintToConsole asks the installed logger`() {
        assertThat(logger.shouldPrintToConsole(suppressibleKind)).isTrue()
        withAgentConsoleLogger {
            assertThat(logger.shouldPrintToConsole(ConsoleLogKind.Default)).isTrue()
            assertThat(logger.shouldPrintToConsole(suppressibleKind)).isFalse()
        }
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured))
        return try {
            block()
            System.out.flush()
            captured.toString()
        } finally {
            System.setOut(original)
        }
    }
}
