package application.mcp.server.tools

import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.File

class ValidateExamplesToolTest {

    private val tool = ValidateExamplesTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `validateExamples should format results correctly for a successful check`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("spec.yaml").apply { writeText("openapi: 3.0.0") }
        var capturedArgs: List<String> = emptyList()

        mockkConstructor(CommandLine::class)
        every { anyConstructed<CommandLine>().execute(*anyVararg()) } answers {
            capturedArgs = invocation.args.flatMap {
                when (it) {
                    is Array<*> -> it.map { arg -> arg.toString() }
                    else -> listOf(it.toString())
                }
            }
            println("All specifications are valid")
            System.err.println("debug log")
            0
        }

        val args = ValidateExamplesArgs(
            contractFile = specFile.path
        )

        val result = tool.validateExamples(args)

        assertThat(capturedArgs).containsExactly(
            "--spec-file", specFile.path
        )
        assertThat(result).contains("## Specmatic Validate Results")
        assertThat(result).contains("Contract File: `${specFile.path}`")
        assertThat(result).contains("Status: PASSED")
        assertThat(result).contains("All specifications are valid")
        assertThat(result).contains("debug log")
    }

    @Test
    fun `validateExamples should format results correctly for a failed check`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("spec.yaml").apply { writeText("invalid openapi") }
        mockkConstructor(CommandLine::class)
        every { anyConstructed<CommandLine>().execute(*anyVararg()) } returns 1

        val args = ValidateExamplesArgs(
            contractFile = specFile.path
        )

        val result = tool.validateExamples(args)

        assertThat(result).contains("## Specmatic Validate Results")
        assertThat(result).contains("Status: FAILED")
    }
}
