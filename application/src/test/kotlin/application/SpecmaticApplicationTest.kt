package application

import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.SystemExitException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpecmaticApplicationTest {

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
}