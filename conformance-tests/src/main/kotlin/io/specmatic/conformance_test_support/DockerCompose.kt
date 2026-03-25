package io.specmatic.conformance_test_support

import java.io.File
import java.util.concurrent.TimeUnit

class DockerCompose(
    private val specmaticVersion: String,
    private val mitmProxyVersion: String,
    private val pathToOpenAPISpecFile: String,
    private val workDir: File,
    private val specsDirName: String
) {
    data class CommandResult(
        val exitCode: Int, val output: String
    ) {
        fun isSuccessful(): Boolean = exitCode == 0
    }

    fun runLoopTests(): CommandResult {
        val command = buildCommand("up", "--exit-code-from", "test")
        return run(command, 60, TimeUnit.SECONDS)
    }

    fun mustGetHttpTrafficLogs(): String {
        val command = buildCommand("logs", "mitm", "--no-color", "--no-log-prefix")
        return mustRun(command, 5, TimeUnit.SECONDS)
    }

    // must fetch logs individually otherwise they come out of order
    fun mustGetAllLogs(): String {
        return listOf("mock", "test", "mitm")
            .joinToString("\n") {
                mustRun(buildCommand("logs", it, "--no-color"), 5, TimeUnit.SECONDS)
            }
    }

    fun stopAsync() {
        buildCommand("down", "--volumes").start()
    }

    private fun run(command: ProcessBuilder, timeout: Long, timeUnit: TimeUnit): CommandResult {
        val process = command.start()
        process.waitFor(timeout, timeUnit)

        return CommandResult(
            exitCode = process.exitValue(),
            output = process.inputReader(Charsets.UTF_8).use { it.readText() }
        )
    }

    private fun mustRun(command: ProcessBuilder, timeout: Long, timeUnit: TimeUnit): String {
        val result = run(command, timeout, timeUnit)
        return when {
            result.isSuccessful() -> result.output
            else -> error("failed to run ${command.command()}\nexit code: ${result.exitCode}\noutput: ${result.output}")
        }
    }

    private fun buildCommand(vararg args: String): ProcessBuilder {
        val composeProjectName = pathToOpenAPISpecFile
            .substringBefore(".")
            .replace(Regex("[^a-zA-Z0-9]"), "-")

        return ProcessBuilder("docker", "compose", "--project-name", composeProjectName, *args)
            .redirectErrorStream(true)
            .directory(workDir)
            .also {
                it.environment().putAll(
                    mapOf(
                        "SPECMATIC_VERSION" to specmaticVersion,
                        "MITM_PROXY_VERSION" to mitmProxyVersion,
                        "PATH_TO_OPEN_API_SPEC_FILE" to "./${specsDirName}/${pathToOpenAPISpecFile}"
                    )
                )
            }
    }
}
