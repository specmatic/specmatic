package application.mcp.server.tools

import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import kotlinx.serialization.Serializable
import picocli.CommandLine

@Serializable
data class BackwardCompatArgs(
    val targetPath: String? = null,
    val baseBranch: String? = null,
    val repoDir: String? = null
)

class BackwardCompatibilityTool {

    internal fun runBackwardCompatibilityCheck(args: BackwardCompatArgs): String {
        val command = BackwardCompatibilityCheckCommandV2()
        val argsList = mutableListOf<String>()
        args.targetPath?.let { argsList.add("--target-path"); argsList.add(it) }
        args.baseBranch?.let { argsList.add("--base-branch"); argsList.add(it) }
        args.repoDir?.let { argsList.add("--repo-dir"); argsList.add(it) }

        val (exitCode, stdout, stderr) = captureStandardStreams {
            CommandLine(command).execute(*argsList.toTypedArray())
        }

        return buildString {
            append("## Specmatic Backward Compatibility Check\n\n")
            if (!args.targetPath.isNullOrBlank()) {
                append("File: `${args.targetPath}`\n\n")
            }

            append("### Status: ")
            if (exitCode == 0) {
                append("BACKWARD COMPATIBLE")
            } else {
                append("BREAKING CHANGES DETECTED OR CHECK FAILED")
            }
            append("\n\n")

            if (stdout.isNotBlank()) {
                append("### Detailed Analysis\n")
                append("```text\n")
                append(stdout.trimEnd())
                append("\n```\n\n")
            }

            if (stderr.isNotBlank()) {
                append("### Errors\n")
                append("```text\n")
                append(stderr.trimEnd())
                append("\n```\n")
            }
        }
    }
}
