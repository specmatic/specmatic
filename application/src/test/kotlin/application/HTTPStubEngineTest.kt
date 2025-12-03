package application

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.Feature
import io.specmatic.core.WorkingDirectory
import io.specmatic.stub.HttpClientFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HTTPStubEngineTest {

    companion object {
        private fun runHttpStubEngineWithMockPaths(vararg paths: String, specToBaseUrlMap: Map<String, String?>) {
            HTTPStubEngine().runHTTPStub(
                stubs = paths.map { it.toMockkFeature() to emptyList() },
                host = "0.0.0.0",
                port = 9000,
                certInfo = CertInfo(),
                strictMode = false,
                httpClientFactory = HttpClientFactory(),
                workingDirectory = WorkingDirectory(),
                gracefulRestartTimeoutInMs = 0,
                specToBaseUrlMap = specToBaseUrlMap
            ).close()
        }

        private fun String.toMockkFeature(): Feature {
            return mockk<Feature>(relaxed = true) {
                every { path } returns this@toMockkFeature
            }
        }
    }

    @Test
    fun `should log sever startup messages for each base urls`() {
        val (stdOut, _) = captureStandardOutput {
            runHttpStubEngineWithMockPaths(
                "api.yaml",
                "bff.yaml",
                specToBaseUrlMap =
                    mapOf(
                        "api.yaml" to "http://localhost:8000/api/v3",
                        "bff.yaml" to "http://localhost:9000",
                    ),
            )
        }

        assertThat(stdOut).containsIgnoringNewLines("""
        |Stub server is running on the following URLs:
        |- http://localhost:8000/api/v3 serving endpoints from specs:
        |\t1. api.yaml
        |
        |- http://localhost:9000 serving endpoints from specs:
        |\t1. bff.yaml
        |
        |Press Ctrl + C to stop.
        """.trimMargin().replace("\\t", "\t"))
    }

    @Test
    fun `should only log startup messages for parsed feature and ignore other specifications from baseUrlMap`() {
        val (stdOut, _) = captureStandardOutput {
            runHttpStubEngineWithMockPaths("api.yaml", specToBaseUrlMap = mapOf(
                "api.yaml" to "http://localhost:8000/api/v3",
                "grpc.proto" to "http://localhost:5000",
                "kafka.yaml" to null
            ))
        }

        assertThat(stdOut).containsIgnoringNewLines("""
        |Stub server is running on the following URLs:
        |- http://localhost:8000/api/v3 serving endpoints from specs:
        |\t1. api.yaml
        |
        |Press Ctrl + C to stop.
        |Saving Stub Usage Report json to ./build/reports/specmatic ...
        """.trimMargin().replace("\\t", "\t"))
    }

    @Test
    fun `should log the final baseUrl for specification which initially was null in specToBaseUrlMap`() {
        val (stdOut, _) = captureStandardOutput {
            runHttpStubEngineWithMockPaths("api.yaml", "uuid.yaml", specToBaseUrlMap = mapOf(
                "api.yaml" to null,
                "uuid.yaml" to null,
                "kafka.yaml" to "http://localghost:9092"
            ))
        }

        assertThat(stdOut).containsIgnoringNewLines("""
        |Stub server is running on the following URLs:
        |- http://0.0.0.0:9000 serving endpoints from specs:
        |\t1. api.yaml
        |\t2. uuid.yaml
        |
        |Press Ctrl + C to stop.
        |Saving Stub Usage Report json to ./build/reports/specmatic ...
        """.trimMargin().replace("\\t", "\t"))
    }
}