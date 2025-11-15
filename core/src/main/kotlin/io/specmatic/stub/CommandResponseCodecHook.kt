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
    private val timeoutSeconds: Long = 10
) : ResponseCodecHook {
    override fun codecResponse(requestResponseJson: JSONObjectValue): JSONObjectValue? {
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
                logger.log("Response codec hook timed out: $command")
                return null
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                logger.log("Response codec hook failed with exit code $exitCode, continuing with original response.\nError output:\n$error")
                return null
            }

            // Read decoded JSON from stdout
            val output = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }

            if (output.isBlank()) {
                logger.log("Response codec hook returned empty output")
                return null
            }

            return try {
                parsedJSONObject(output)
            } catch (e: Throwable) {
                logger.log(e, "Error parsing JSON output from response codec hook")
                null
            }
        } catch (e: Throwable) {
            logger.log(e, "Error executing response codec hook: $command")
            return null
        }
    }
}
