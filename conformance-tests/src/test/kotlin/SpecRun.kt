import java.io.File

class SpecRun(val specFile: String, private val workDir: File) {
    val dockerCompose: DockerCompose = DockerCompose(specFile, workDir)
    lateinit var captures: List<HttpCapture>
        private set
    lateinit var openApiSpec: OpenApiSpec
        private set

    fun start() {
        dockerCompose.start()
        captures = HttpCapture.parseLog(dockerCompose.mitmLogs())
        openApiSpec = OpenApiSpec.load("${workDir.absolutePath}/specs/$specFile")
    }

    fun stop() = dockerCompose.stop()
}
