import java.io.File

class SpecRun(val specFile: String, private val workDir: File) {
    val dockerCompose: DockerCompose = DockerCompose(specFile, workDir)

    lateinit var loopTestResult: DockerCompose.CommandResult
        private set

    lateinit var mitmLogsCommandResult: DockerCompose.CommandResult

    lateinit var captures: List<HttpCapture>
        private set

    lateinit var openApiSpec: OpenApiSpec
        private set

    fun start() {
        loopTestResult = dockerCompose.startLoopTest()
        mitmLogsCommandResult = dockerCompose.mitmLogs()
        captures = HttpCapture.parseLog(mitmLogsCommandResult.output)
        openApiSpec = OpenApiSpec.load("${workDir.absolutePath}/specs/$specFile")
    }

    fun stop() = dockerCompose.stopAsync()
}
