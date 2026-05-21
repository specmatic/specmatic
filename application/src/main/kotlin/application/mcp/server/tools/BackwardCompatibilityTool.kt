package application.mcp.server.tools

import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import application.mcp.server.utils.McpUtils.captureStandardStreams
import kotlinx.serialization.Serializable

@Serializable
data class BackwardCompatArgs(
    val targetPath: String? = null,
    val baseBranch: String? = null,
    val repoDir: String? = null
)

class BackwardCompatibilityTool {

    internal fun runBackwardCompatibilityCheck(args: BackwardCompatArgs): String {
        val command = BackwardCompatibilityCheckCommandV2().apply {
            options.targetPath = args.targetPath
            options.baseBranch = args.baseBranch
            options.repoDir = args.repoDir
        }

        val (exitCode, stdout, stderr) = captureStandardStreams {
            command.call()
        }

        return buildString {
            append("# Specmatic Backward Compatibility Check\n\n")
            if (!args.targetPath.isNullOrBlank()) {
                append("File: `${args.targetPath}`\n\n")
            }

            if (exitCode == 0) {
                append("Status: BACKWARD COMPATIBLE\n\n")
            } else {
                append("Status: BREAKING CHANGES DETECTED OR CHECK FAILED\n\n")
            }

            if (stdout.isNotBlank()) {
                append("Detailed analysis:\n")
                append("```\n")
                append(stdout.trimEnd())
                append("\n```\n\n")
            }

            if (stderr.isNotBlank()) {
                append("Errors:\n")
                append("```\n")
                append(stderr.trimEnd())
                append("\n```\n")
            }
        }
    }
}
