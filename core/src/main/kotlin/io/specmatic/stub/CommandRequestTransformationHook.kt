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
 * Request transformation hook that executes a shell command.
 *
 * The hook sends the request JSON to the command's stdin and reads the
 * transformed JSON from the command's stdout.
 */
class CommandRequestTransformationHook(
    private val command: String,
    private val timeoutSeconds: Long = 10
) : RequestTransformationHook {
    override fun transformRequest(requestJson: JSONObjectValue): JSONObjectValue? {
        try {
            // Execute the command
            val process = ProcessBuilder()
                .command("sh", "-c", command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            // Write JSON to stdin
            OutputStreamWriter(process.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestJson.toStringLiteral())
                writer.flush()
            }

            // Wait for process to complete with timeout
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                logger.log("Request transformation hook timed out: $command")
                return null
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                logger.log("Request transformation hook failed with exit code $exitCode: $error")
                return null
            }

            // Read transformed JSON from stdout
            val output = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }

            if (output.isBlank()) {
                logger.log("Request transformation hook returned empty output")
                return null
            }

            return try {
                parsedJSONObject(output)
            } catch (e: Throwable) {
                logger.log(e, "Error parsing JSON output from request transformation hook")
                null
            }
        } catch (e: Throwable) {
            logger.log(e, "Error executing request transformation hook: $command")
            return null
        }
    }
}
