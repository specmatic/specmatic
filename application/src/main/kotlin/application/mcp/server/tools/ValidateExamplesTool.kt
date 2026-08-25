package application.mcp.server.tools

import application.ExamplesCommand
import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.SystemExitException
import kotlinx.serialization.Serializable
import picocli.CommandLine
import java.io.File

@Serializable
data class ValidateExamplesArgs(
    val contractFile: String? = null,
    val examplesDir: String? = null,
    val examplesToValidate: String? = null
)

class ValidateExamplesTool {

    internal fun validateExamples(args: ValidateExamplesArgs): String {
        if (isInvalidRepoDir(args.contractFile)) return getFallbackResponse(args)

        return try {
            val command = ExamplesCommand.Validate()
            val argsList = mutableListOf<String>()

            args.contractFile?.takeIf { it.isNotBlank() }?.let { argsList.add("--contract-file"); argsList.add(it) }
            args.examplesDir?.takeIf { it.isNotBlank() }?.let { argsList.add("--examples-dir"); argsList.add(it) }
            args.examplesToValidate?.takeIf { it.isNotBlank() }?.let { argsList.add("--examples-to-validate"); argsList.add(it) }

            val (exitCode, stdout, stderr) = captureStandardStreams {
                SystemExit.throwOnExit {
                    CommandLine(command).execute(*argsList.toTypedArray())
                }
            }

            buildString {
                append("## Specmatic Validate Examples Results\n\n")
                if (!args.contractFile.isNullOrBlank()) {
                    append("Contract File: `${args.contractFile}`\n\n")
                }

                append("### Status: ")
                if (exitCode == 0) {
                    append("PASSED")
                } else {
                    append("FAILED")
                }
                append("\n\n")

                if (stdout.isNotBlank()) {
                    append("### Validation Output\n")
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
        } catch (e: SystemExitException) {
            getFallbackResponse(args, e.message)
        } catch (e: Throwable) {
            getFallbackResponse(args, e.message)
        }
    }

    internal fun isInvalidRepoDir(contractFilePath:String?): Boolean {
        if (contractFilePath.isNullOrBlank()) return true
        return !File(contractFilePath).exists()
    }

    internal fun getFallbackResponse(args: ValidateExamplesArgs, reason: String? = null): String {
        val detail = reason ?: "`${args.contractFile}` is unavailable in the current environment or the spec-file path is not correct."
        return """
            ## Specmatic Example Validation

            ### Status: UNAVAILABLE

            $detail
        """.trimIndent()
    }
}
