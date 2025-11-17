package io.specmatic.stub

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Response codec hook that executes a shell command.
 *
 * The hook sends the request and response JSON to the command's stdin and reads the
 * decoded JSON from the command's stdout.
 */
class CommandResponseCodecHook(
    private val command: String,
    private val hookName: String,
    private val timeoutSeconds: Long = 10
) : ResponseCodecHook {
    override fun codecResponseWithResult(requestResponseJson: JSONObjectValue): InterceptorResult<JSONObjectValue> {
        try {
            // Execute the command
            val process = ProcessBuilder()
                .command("sh", "-c", command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            // Write JSON to stdin
            OutputStreamWriter(process.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestResponseJson.toStringLiteral())
                writer.flush()
            }

            // Wait for process to complete with timeout
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                val error = InterceptorError(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Process timed out after $timeoutSeconds seconds",
                    hookType = hookName
                )
                return InterceptorResult.failure(error)
            }

            val exitCode = process.exitValue()

            // Read output and error streams
            val output = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            val errorOutput = BufferedReader(InputStreamReader(process.errorStream)).use {
                it.readText()
            }

            if (exitCode != 0) {
                val error = InterceptorError(
                    exitCode = exitCode,
                    stdout = output,
                    stderr = errorOutput,
                    hookType = hookName
                )
                return InterceptorResult.failure(error)
            }

            if (output.isBlank()) {
                val error = InterceptorError(
                    exitCode = 0,
                    stdout = "",
                    stderr = "Hook returned empty output",
                    hookType = hookName
                )
                return InterceptorResult.failure(error)
            }

            return try {
                val decodedJson = parsedJSONObject(output)
                InterceptorResult.success(decodedJson)
            } catch (e: Throwable) {
                val error = InterceptorError(
                    exitCode = 0,
                    stdout = output,
                    stderr = "Error parsing JSON output: ${e.message}",
                    hookType = hookName
                )
                InterceptorResult.failure(error)
            }
        } catch (e: Throwable) {
            val error = InterceptorError(
                exitCode = -1,
                stdout = "",
                stderr = "Error executing command: ${e.message}",
                hookType = hookName
            )
            return InterceptorResult.failure(error)
        }
    }

    override fun codecResponse(requestResponseJson: JSONObjectValue): JSONObjectValue? {
        return codecResponseWithResult(requestResponseJson).value
    }
}
