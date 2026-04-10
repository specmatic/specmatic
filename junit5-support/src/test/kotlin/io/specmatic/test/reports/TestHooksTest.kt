package io.specmatic.test.reports

import com.sun.net.httpserver.HttpServer
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.ContractTestSettings
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.reports.coverage.Endpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress

class TestHooksTest {
    @Test
    fun `onTestResult should report updated negative scenario for generative tests`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("openapi.yaml")
        specFile.writeText("""
        openapi: 3.0.0
        info:
          title: TestHooks generative reporting
          version: 1.0.0
        paths:
          /products:
            get:
              parameters:
                - in: header
                  name: X-Rate
                  required: true
                  schema:
                    type: integer
              responses:
                '200':
                  description: OK
                '400':
                  description: Bad request
        """.trimIndent())

        val listener = RecordingTestReportListener()
        val server = startServer()
        val baseUrl = "http://localhost:${server.address.port}"
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                testBaseURL = baseUrl,
                contractPaths = specFile.absolutePath,
                filter = "",
                configFile = "",
                generative = true,
                reportBaseDirectory = null,
                coverageHooks = listOf(listener)
            )
        )

        try {
            val tests = SpecmaticJUnitSupport().contractTest().toList()
            assertThat(tests.map { it.displayName }).anyMatch { it.contains("-ve") }
            tests.forEach { it.executable.execute() }
            assertThat(listener.testResults).hasSize(tests.size)

            val negativeResults = listener.testResults.filter { it.scenario.generativePrefix.contains("-ve") }
            assertThat(negativeResults).isNotEmpty
            assertThat(negativeResults).allSatisfy { testExecutionResult ->
                assertThat(testExecutionResult.testResult).isEqualTo(TestResult.Success)
                assertThat(testExecutionResult.actualResponseStatus).isEqualTo(400)
                assertThat(testExecutionResult.scenario.status).isEqualTo(400)
                assertThat(testExecutionResult.result.scenario).isNotNull
                assertThat(testExecutionResult.result.scenario?.status).isEqualTo(400)
            }
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
            server.stop(0)
        }
    }

    private fun startServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/products") { exchange ->
            val rate = exchange.requestHeaders.getFirst("X-Rate")
            val isValidHeader = rate?.toIntOrNull() != null
            val status = if (isValidHeader) 200 else 400
            exchange.sendResponseHeaders(status, 0)
            exchange.responseBody.close()
        }

        server.start()
        return server
    }

    private class RecordingTestReportListener : TestReportListener {
        val testResults = mutableListOf<TestExecutionResult>()
        override fun onActuator(enabled: Boolean) = Unit
        override fun onActuatorApis(apisNotExcluded: List<API>, apisExcluded: List<API>) = Unit
        override fun onEndpointApis(endpointsNotExcluded: List<Endpoint>, endpointsExcluded: List<Endpoint>) = Unit
        override fun onTestResult(result: TestExecutionResult) { testResults.add(result) }
        override fun onExampleErrors(resultsBySpecFile: Map<String, io.specmatic.core.Result>) = Unit
        override fun onTestsComplete() = Unit
        override fun onEnd() = Unit
        override fun onCoverageCalculated(coverage: Int) = Unit
        override fun onPathCoverageCalculated(path: String, pathCoverage: Int) = Unit
        override fun onGovernance(result: io.specmatic.core.Result) = Unit
    }
}
