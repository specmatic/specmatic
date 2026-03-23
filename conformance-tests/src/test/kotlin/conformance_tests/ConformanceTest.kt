package conformance_tests

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class ConformanceTest {
    @Test
    fun `loop test`() {
        val dockerCompose = DockerCompose(
            specmaticVersion = System.getProperty("specmatic.version"),
            pathToOpenAPISpecFile = File("001-http-methods/001-get.yaml")
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
