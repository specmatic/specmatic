import java.io.File

class DockerCompose(private val specFile: String) {
    private val projectName = specFile.replace(".", "-")
    private val workDir = File("build/resources/test")
    private val env = mapOf(
        "MITM_PROXY_VERSION" to "12.2.1",
        "SPECMATIC_VERSION" to "2.42.2",
        "PATH_TO_OPEN_API_SPEC_FILE" to "./specs/$specFile"
    )

    var exitCode: Int = -1
        private set

    fun start() {
        val process = command("up", "--exit-code-from", "test").start()
        process.inputStream.bufferedReader().forEachLine(::println)
        exitCode = process.waitFor()
    }

    fun stop() {
        val process = command("down", "--remove-orphans").start()
        process.inputStream.bufferedReader().forEachLine(::println)
        process.waitFor()
    }

    private fun command(vararg args: String): ProcessBuilder =
        ProcessBuilder("docker", "compose", "-p", projectName, *args)
            .directory(workDir)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
}
