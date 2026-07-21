package application.mcp.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackwardCompatArgs(
    val targetPath: String? = null,
    val baseBranch: String? = null,
    val repoDir: String? = null
)

@Serializable
data class ExecutableCommandResponse(
    val action: String,
    val command: String
)

class BackwardCompatibilityTool(

) {

    internal fun runBackwardCompatibilityCheck(args: BackwardCompatArgs): String {
        val repoDir = args.repoDir?.takeIf { it.isNotBlank() } ?: "."
        val command = mutableListOf(
            "docker",
            "run",
            "--rm",
            "-i",
            "-v",
            "$repoDir:/usr/src/app",
            "-w",
            "/usr/src/app",
            "specmatic/specmatic:2.50.1-SNAPSHOT",
            "backward-compatibility-check"
        )

        args.targetPath?.takeIf { it.isNotBlank() }?.let {
            command += listOf("--target-path", it)
        }

        args.baseBranch?.takeIf { it.isNotBlank() }?.let {
            command += listOf("--base-branch", it)
        }

        return Json.encodeToString(
            ExecutableCommandResponse(
                action = "execute",
                command = command.joinToString(" ")
            )
        )
    }
}
