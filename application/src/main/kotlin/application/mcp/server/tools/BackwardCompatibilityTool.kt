package application.mcp.server.tools

import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import picocli.CommandLine
import java.io.File

@Serializable
data class BackwardCompatArgs(
    val targetPath: String? = null,
    val baseBranch: String? = null,
    val repoDir: String? = null
)

@Serializable
data class BackwardCompatibilityFallbackResponse(
    val title: String = "Specmatic Backward Compatibility Check",
    val message: String,
    val dockerCommand: String,
    val suggestion: String
)

class BackwardCompatibilityTool {

    internal fun runBackwardCompatibilityCheck(args: BackwardCompatArgs): String {
        if(args.repoDir != null) {
            val repoDirFile = File(args.repoDir)
            if (!repoDirFile.exists() || !repoDirFile.isDirectory) {
                return getFallbackResponse(args)
            }
        }
        val command = BackwardCompatibilityCheckCommandV2()
        val argsList = mutableListOf<String>()
        args.targetPath?.let { argsList.add("--target-path"); argsList.add(it) }
        args.baseBranch?.let { argsList.add("--base-branch"); argsList.add(it) }
        args.repoDir?.let { argsList.add("--repo-dir"); argsList.add(args.repoDir) }

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
                append("### Execution Logs\n")
                append("```text\n")
                append(stderr.trimEnd())
                append("\n```\n")
            }
        }
    }

    internal fun getFallbackResponse(args: BackwardCompatArgs): String {
        val command = mutableListOf(
            "docker",
            "run",
            "--rm",
            "-i",
            "-v",
            "${args.repoDir}:/usr/src/app",
            "specmatic/specmatic",
            "backward-compatibility-check"
        )

        args.targetPath?.takeIf { it.isNotBlank() }?.let {
            command += listOf("--target-path", it)
        }

        args.baseBranch?.takeIf { it.isNotBlank() }?.let {
            command += listOf("--base-branch", it)
        }

        return Json.encodeToString(
            BackwardCompatibilityFallbackResponse(
                message = "The specified repository directory `${args.repoDir}` does not exist or is not available from the current container filesystem.",
                dockerCommand = command.joinToString(" "),
                suggestion = "Use the docker command"
            )
        )
    }
}
