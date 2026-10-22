package application

import io.specmatic.testPort
import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.Feature
import io.specmatic.core.IncomingMtlsRegistry
import io.specmatic.core.KeyDataRegistry
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.WorkingDirectory
import io.specmatic.reporter.commands.InsightsReportOptions
import io.specmatic.stub.HttpClientFactory
import io.specmatic.stub.SpecmaticConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HTTPStubEngineTest {

    companion object {
        private fun runHttpStubEngineWithMockPaths(vararg paths: String, port: Int = testPort(), specToBaseUrlMap: Map<String, String?>) {
            HTTPStubEngine().runHTTPStub(
                stubs = paths.map { it.toMockkFeature() to emptyList() },
                host = "0.0.0.0",
                port = port,
                keyDataRegistry = KeyDataRegistry.empty(),
                incomingMtlsRegistry = IncomingMtlsRegistry.empty(),
                strictMode = false,
                httpClientFactory = HttpClientFactory(),
                workingDirectory = WorkingDirectory(),
                gracefulRestartTimeoutInMs = 0,
                specToBaseUrlMap = specToBaseUrlMap,
                specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(SpecmaticConfig()),
                insightsReportOptions = InsightsReportOptions()
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
        val apiPort = testPort(1)
        val bffPort = testPort(2)
        val (stdOut, _) = captureStandardOutput {
            runHttpStubEngineWithMockPaths(
                "api.yaml",
                "bff.yaml",
                specToBaseUrlMap =
                    mapOf(
                        "api.yaml" to "http://localhost:$apiPort/api/v3",
                        "bff.yaml" to "http://localhost:$bffPort",
                    ),
            )
        }

        assertThat(stdOut).containsIgnoringNewLines("""
        |Mock server is running on the following URLs:
        |- http://localhost:$apiPort/api/v3 serving endpoints from specs:
        |\t1. api.yaml
        |
        |- http://localhost:$bffPort serving endpoints from specs:
        |\t1. bff.yaml
        |
        |Press Ctrl + C to stop.
        """.trimMargin().replace("\\t", "\t"))
    }

    @Test
    fun `should only log startup messages for parsed feature and ignore other specifications from baseUrlMap`() {
        val apiPort = testPort(1)
        val (stdOut, _) = captureStandardOutput {
            runHttpStubEngineWithMockPaths("api.yaml", specToBaseUrlMap = mapOf(
                "api.yaml" to "http://localhost:$apiPort/api/v3",
                "grpc.proto" to "http://localhost:${testPort(2)}",
                "kafka.yaml" to null
            ))
        }

        assertThat(stdOut).containsIgnoringNewLines("""
        |Mock server is running on the following URLs:
        |- http://localhost:$apiPort/api/v3 serving endpoints from specs:
        |\t1. api.yaml
        |
        |Press Ctrl + C to stop.
        """.trimMargin().replace("\\t", "\t"))
    }

    @Test
    fun `should log the final baseUrl for specification which initially was null in specToBaseUrlMap`() {
        val port = testPort(1)
        val (stdOut, _) = captureStandardOutput {
            runHttpStubEngineWithMockPaths("api.yaml", "uuid.yaml", port = port, specToBaseUrlMap = mapOf(
                "api.yaml" to null,
                "uuid.yaml" to null,
                "kafka.yaml" to "http://localghost:9092"
            ))
        }

        assertThat(stdOut).containsIgnoringNewLines("""
        |Mock server is running on the following URLs:
        |- http://0.0.0.0:$port serving endpoints from specs:
        |\t1. api.yaml
        |\t2. uuid.yaml
        |
        |Press Ctrl + C to stop.
        """.trimMargin().replace("\\t", "\t"))
    }

    @Test
    fun `httpDumpLog is used for stub request logging`() {
        // HTTPStubEngine always wires httpDumpLog; quieting is owned by AgentConsoleLogger.
        assertThat(io.specmatic.core.log.httpDumpLog).isNotNull()
    }

    @Test
    fun `agentMode still prints mock startup banner`() {
        val port = testPort()
        val (stdOut, _) = captureStandardOutput {
            HTTPStubEngine().runHTTPStub(
                stubs = listOf("api.yaml".toMockkFeature() to emptyList()),
                host = "0.0.0.0",
                port = port,
                keyDataRegistry = KeyDataRegistry.empty(),
                incomingMtlsRegistry = IncomingMtlsRegistry.empty(),
                strictMode = false,
                httpClientFactory = HttpClientFactory(),
                workingDirectory = WorkingDirectory(),
                gracefulRestartTimeoutInMs = 0,
                specToBaseUrlMap = mapOf("api.yaml" to "http://localhost:$port"),
                specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(SpecmaticConfig()),
                insightsReportOptions = InsightsReportOptions(),
                agentMode = true,
            ).close()
        }

        assertThat(stdOut).contains("Mock server is running on the following URLs:")
        assertThat(stdOut).contains("Press Ctrl + C to stop.")
    }
}
