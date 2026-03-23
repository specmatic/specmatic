package conformance_tests

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

abstract class AbstractConformanceTest(
    private val openAPISpecFile: String
) {
    @Test
    fun `loop test`() {
        val dockerCompose = DockerCompose(
            specmaticVersion = System.getProperty("specmatic.version"),
            pathToOpenAPISpecFile = openAPISpecFile
        )

        try {
            val loopTestsResult = dockerCompose.runLoopTests()
            assertThat(loopTestsResult.isSuccessful())
                .withFailMessage { loopTestsResult.output }
                .isTrue
        } finally {
            dockerCompose.stopAsync()
        }
    }
}
