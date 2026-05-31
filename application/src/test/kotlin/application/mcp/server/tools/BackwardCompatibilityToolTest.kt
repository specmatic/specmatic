package application.mcp.server.tools

import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import picocli.CommandLine

class BackwardCompatibilityToolTest {

    private val tool = BackwardCompatibilityTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `runBackwardCompatibilityCheck should format results correctly for a successful check`() {
        var capturedArgs: List<String> = emptyList()

        mockkConstructor(CommandLine::class)
        every { anyConstructed<CommandLine>().execute(*anyVararg()) } answers {
            capturedArgs = invocation.args.flatMap {
                when (it) {
                    is Array<*> -> it.map { arg -> arg.toString() }
                    else -> listOf(it.toString())
                }
            }
            println("No breaking changes found")
            System.err.println("debug log")
            0
        }

        val args = BackwardCompatArgs(
            targetPath = "spec.yaml",
            baseBranch = "main",
            repoDir = "repo"
        )

        val result = tool.runBackwardCompatibilityCheck(args)

        assertThat(capturedArgs).containsExactly(
            "--target-path", "spec.yaml",
            "--base-branch", "main",
            "--repo-dir", "repo"
        )
        assertThat(result).contains("## Specmatic Backward Compatibility Check")
        assertThat(result).contains("File: `spec.yaml`")
        assertThat(result).contains("Status: BACKWARD COMPATIBLE")
        assertThat(result).contains("No breaking changes found")
        assertThat(result).contains("debug log")
    }

    @Test
    fun `runBackwardCompatibilityCheck should format results correctly for a failed check`() {
        mockkConstructor(CommandLine::class)
        every { anyConstructed<CommandLine>().execute(*anyVararg()) } returns 1

        val args = BackwardCompatArgs(
            targetPath = "spec.yaml"
        )

        val result = tool.runBackwardCompatibilityCheck(args)

        assertThat(result).contains("## Specmatic Backward Compatibility Check")
        assertThat(result).contains("Status: BREAKING CHANGES DETECTED OR CHECK FAILED")
    }
}
