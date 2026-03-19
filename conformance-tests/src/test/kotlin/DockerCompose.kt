import java.io.File

class DockerCompose(
    private val specFile: String,
    private val workDir: File
) {

    data class CommandResult(
        val exitCode: Int, val output: String
    ) {
        fun isSuccessful(): Boolean = exitCode == 0
    }

    fun startLoopTest(): CommandResult {
        val command = buildCommand("up", "--exit-code-from", "test")
        return run(command)
    }

    fun stop(): CommandResult {
        val command = buildCommand("down", "--volumes")
        return run(command)
    }

    fun mitmLogs(): CommandResult {
        val command = buildCommand("logs", "mitm", "--no-color", "--no-log-prefix")
        return run(command)
    }

    fun mustGetAllLogsOutput(): String {
        val command = buildCommand("logs", "--no-color")
        val result = run(command)
        return when (result.isSuccessful()) {
            true -> result.output
            else -> throw RuntimeException("failed to getAlllogs. exit code: ${result.exitCode}. output: ${result.output}")
        }
    }

    private fun run(command: ProcessBuilder): CommandResult {
        val process = command.start()
        val output = process.inputReader().readText()
        val exitCode = process.waitFor()
        return CommandResult(exitCode, output)
    }

    private fun buildCommand(vararg args: String): ProcessBuilder {
        val composeProjectName = specFile.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase()

        return ProcessBuilder("docker", "compose", "--project-name", composeProjectName, *args)
            .directory(workDir)
            .redirectErrorStream(true).also {
                it.environment().putAll(
                    mapOf(
                        "MITM_PROXY_VERSION" to "12.2.1",
                        "SPECMATIC_VERSION" to "2.42.2",
                        "PATH_TO_OPEN_API_SPEC_FILE" to "./specs/${specFile}"
                    )
                )
            }
    }
}
