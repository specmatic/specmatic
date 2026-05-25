package application

import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.SystemExitException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SpecmaticApplicationTest {

    @Test
    fun `should redirect stdout to stderr when arguments are mcp server`() {
        val originalOut = System.`out`
        val originalErr = System.`err`
        try {
            val out = PrintStream(ByteArrayOutputStream())
            val err = PrintStream(ByteArrayOutputStream())
            System.setOut(out)
            System.setErr(err)

            val method = SpecmaticApplication.Companion::class.java.getDeclaredMethod("redirectStdoutToStderrIfMcpServer", Array<String>::class.java)
            method.isAccessible = true

            method.invoke(SpecmaticApplication.Companion, arrayOf("mcp", "server"))

            assertThat(System.`out`).isSameAs(err)
            assertThat(System.`out`).isSameAs(System.`err`)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `should NOT redirect stdout to stderr when arguments are NOT mcp server`() {
        val originalOut = System.`out`
        val originalErr = System.err
        try {
            val out = PrintStream(ByteArrayOutputStream())
            val err = PrintStream(ByteArrayOutputStream())
            System.setOut(out)
            System.setErr(err)

            val method = SpecmaticApplication.Companion::class.java.getDeclaredMethod("redirectStdoutToStderrIfMcpServer", Array<String>::class.java)
            method.isAccessible = true

            method.invoke(SpecmaticApplication.Companion, arrayOf("test"))

            assertThat(System.`out`).isSameAs(out)
            assertThat(System.`out`).isNotSameAs(err)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `should print version info on each invocation that isn't version check`() {
        val args = arrayOf("test", "--help")
        val (stdOut, exception) = captureStandardOutput {
            assertThrows<SystemExitException> {
                SystemExit.throwOnExit {
                    SpecmaticApplication.main(args)
                }
            }
        }

        assertThat(exception.code).isEqualTo(0)
        assertThat(stdOut).containsPattern("Specmatic Version: v\\d+\\.\\d+\\.\\d+")
    }

    @Test
    fun `should print version info when invoking version check on any command or sub-command`() {
        val args = arrayOf("examples", "validate", "-V")
        val (stdOut, exception) = captureStandardOutput {
            assertThrows<SystemExitException> {
                SystemExit.throwOnExit {
                    SpecmaticApplication.main(args)
                }
            }
        }

        assertThat(exception.code).isEqualTo(0)
        assertThat(stdOut).containsPattern("v\\d+\\.\\d+\\.\\d+")
    }

    @Test
    fun `root version command should only print specmatic version`() {
        val args = arrayOf("--version")
        val (stdOut, exception) = captureStandardOutput {
            assertThrows<SystemExitException> {
                SystemExit.throwOnExit {
                    SpecmaticApplication.main(args)
                }
            }
        }

        val nonBlankLines = stdOut.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        assertThat(exception.code).isEqualTo(0)
        assertThat(nonBlankLines).hasSize(1)
        assertThat(nonBlankLines.single()).containsPattern("^Specmatic Version: v\\d+\\.\\d+\\.\\d+.*$")
    }
}
