import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.stream.Stream

class ConformanceTest {

    @TestFactory
    fun conformanceTests(): Stream<DynamicContainer> =
        File("build/resources/test/specs")
            .listFiles { f -> f.isFile }
            .orEmpty()
            .map { it.name }
            .sorted()
            .map { specFile ->
                DynamicContainer.dynamicContainer(
                    specFile, listOf(
                        DynamicTest.dynamicTest("it successfully performs a loop test") {
                            val dockerCompose = DockerCompose(specFile)
                            dockerCompose.start()
                            try {
                                assertThat(dockerCompose.exitCode).isEqualTo(0)
                            } finally {
                                dockerCompose.stop()
                            }
                        }
                    ))
            }
            .stream()
}
