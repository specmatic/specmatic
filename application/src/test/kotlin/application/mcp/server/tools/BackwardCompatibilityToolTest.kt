package application.mcp.server.tools

import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BackwardCompatibilityToolTest {

    private val tool = BackwardCompatibilityTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `runBackwardCompatibilityCheck should format results correctly for a successful check`() {
        mockkConstructor(BackwardCompatibilityCheckCommandV2::class)
        every { anyConstructed<BackwardCompatibilityCheckCommandV2>().call() } returns 0

        val args = BackwardCompatArgs(
            targetPath = "spec.yaml",
            baseBranch = "main"
        )

        val result = tool.runBackwardCompatibilityCheck(args)

        assertThat(result).contains("## Specmatic Backward Compatibility Check")
        assertThat(result).contains("File: `spec.yaml`")
        assertThat(result).contains("Status: BACKWARD COMPATIBLE")
    }

    @Test
    fun `runBackwardCompatibilityCheck should format results correctly for a failed check`() {
        mockkConstructor(BackwardCompatibilityCheckCommandV2::class)
        every { anyConstructed<BackwardCompatibilityCheckCommandV2>().call() } returns 1

        val args = BackwardCompatArgs(
            targetPath = "spec.yaml"
        )

        val result = tool.runBackwardCompatibilityCheck(args)

        assertThat(result).contains("## Specmatic Backward Compatibility Check")
        assertThat(result).contains("Status: BREAKING CHANGES DETECTED OR CHECK FAILED")
    }
}
