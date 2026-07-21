package application.mcp.server.tools

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BackwardCompatibilityToolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `runBackwardCompatibilityCheck should return an executable command payload`() {
        val tool = BackwardCompatibilityTool()
        val args = BackwardCompatArgs(
            targetPath = "spec.yaml",
            baseBranch = "main",
            repoDir = "repo"
        )

        val result = tool.runBackwardCompatibilityCheck(args)
        val payload = json.decodeFromString<ExecutableCommandResponse>(result)

        assertThat(payload.action).isEqualTo("execute")
        assertThat(payload.command).isEqualTo(
            "docker run --rm -i -v repo:/usr/src/app -w /usr/src/app " +
                "specmatic/specmatic:2.50.1-SNAPSHOT backward-compatibility-check " +
                "--target-path spec.yaml --base-branch main"
        )
    }

    @Test
    fun `runBackwardCompatibilityCheck should default repoDir when missing`() {
        val tool = BackwardCompatibilityTool()

        val result = tool.runBackwardCompatibilityCheck(BackwardCompatArgs(targetPath = "spec.yaml"))
        val payload = json.decodeFromString<ExecutableCommandResponse>(result)

        assertThat(payload.command).contains("docker run --rm -i -v .:/usr/src/app -w /usr/src/app")
        assertThat(payload.command).contains("--target-path spec.yaml")
    }
}
