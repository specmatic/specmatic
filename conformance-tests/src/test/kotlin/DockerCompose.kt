import java.io.File

class DockerCompose(private val specFile: String, private val workDir: File = File("build/resources/test")) {
    private val projectName = specFile.replace(".", "-")
    private val env = mapOf(
        "MITM_PROXY_VERSION" to "12.2.1",
        "SPECMATIC_VERSION" to "2.42.2",
        "PATH_TO_OPEN_API_SPEC_FILE" to "./specs/$specFile"
    )

    var exitCode: Int = -1
        private set

    fun start() {
        val process = command("up", "--exit-code-from", "test").start()
        exitCode = process.waitFor()
    }

    fun stop() {
        val process = command("down", "--remove-orphans").start()
        process.waitFor()
    }

    fun mitmLogs(): String {
        val process = command("logs", "mitm").start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun command(vararg args: String): ProcessBuilder =
        ProcessBuilder("docker", "compose", "-p", projectName, *args)
            .directory(workDir)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
}
