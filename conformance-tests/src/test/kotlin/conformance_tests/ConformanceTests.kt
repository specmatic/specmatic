package conformance_tests

import io.specmatic.conformance.tests.VersionInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

@Execution(ExecutionMode.CONCURRENT)
class ConformanceTests {
    @Test
    fun suite() {
        // Keeps Gradle's `--tests "conformance_tests.ConformanceTests"` filter matchable.
    }

    @TestFactory
    fun loopTests(): Stream<DynamicTest> {
        val specsDir = Path.of("src", "test", "resources", "specs")

        return Files.walk(specsDir).use { paths ->
            paths
                .asSequence()
                .filter { it.isRegularFile() && it.extension in setOf("yaml", "yml") }
                .sorted()
                .map { specPath ->
                    val relativePath = specsDir.relativize(specPath).invariantSeparatorsPathString
                    val displayName = relativePath.substringBeforeLast(".")

                    DynamicTest.dynamicTest(displayName) {
                        runLoopTest(relativePath)
                    }
                }
                .toList()
                .stream()
        }
    }

    private fun runLoopTest(openAPISpecFile: String) {
        val dockerCompose = DockerCompose(
            specmaticVersion = VersionInfo.version,
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
