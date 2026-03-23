package conformance_tests

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

class DockerCompose(
    private val specmaticVersion: String,
    private val mitmProxyVersion: String,
    private val pathToOpenAPISpecFile: String,
    private val composeProjectName: String = pathToOpenAPISpecFile
        .substringBefore(".")
        .replace(Regex("[^a-zA-Z0-9]"), "-")
) {
    val logger: Logger = LoggerFactory.getLogger(DockerCompose::class.java)

    data class CommandResult(
        val exitCode: Int, val output: String
    ) {
        fun isSuccessful(): Boolean = exitCode == 0
    }

    fun runLoopTests(): CommandResult {
        val command = buildCommand("up", "--exit-code-from", "test")
        val process = command.start()
        process.waitFor(60, TimeUnit.SECONDS)

        return CommandResult(
            exitCode = process.exitValue(),
            output = process.inputReader(Charsets.UTF_8).use { it.readText() }
        ).also { logger.debug("Loop tests result: {}", it) }
    }

    fun stopAsync() {
        buildCommand("down", "--volumes").start()
    }

    private fun buildCommand(vararg args: String): ProcessBuilder {
        return ProcessBuilder("docker", "compose", "--project-name", composeProjectName, *args)
            .redirectErrorStream(true)
            .directory(File("build/resources/test"))
            .also {
                it.environment().putAll(
                    mapOf(
                        "SPECMATIC_VERSION" to specmaticVersion,
                        "MITM_PROXY_VERSION" to mitmProxyVersion,
                        "PATH_TO_OPEN_API_SPEC_FILE" to "./specs/${pathToOpenAPISpecFile}"
                    )
                )
            }
    }
}
