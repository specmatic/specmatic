package io.specmatic.test.utils

import io.specmatic.core.JSON
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.YAML
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v3.SpecmaticConfigV3Impl
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.yamlMapper
import io.specmatic.test.API
import io.specmatic.test.ContractTestSettings
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.reports.TestExecutionResult
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.reporter.model.OpenAPIOperation
import org.junit.jupiter.api.DynamicTest
import java.io.File
import java.util.UUID

class ContractTestScope(private val specification: File, private val tempDir: File = specification.parentFile) {
    private val server: MockHttpServer = MockHttpServer()
    private val listener: RecordingTestReportListener = RecordingTestReportListener()
    @Volatile private var specmaticJunitSupport: SpecmaticJUnitSupport? = null

    fun execute(specmaticConfig: SpecmaticConfig = SpecmaticConfig(), block: (MockHttpServer) -> Unit): ContractTestScope {
        val configFile = tempDir.resolve("${specification.name}.specmatic.yaml").apply {
            writeText(specmaticConfig.toYaml())
        }

        val contractTestSettings = ContractTestSettings(
            coverageHooks = listOf(listener),
            configFile = configFile.canonicalPath,
            filter = specmaticConfig.getTestFilter(),
            contractPaths = specification.canonicalPath,
            testBaseURL = "http://localhost:${server.port}",
        )

        try {
            SpecmaticJUnitSupport.settingsStaging.set(contractTestSettings)
            val newInstance = SpecmaticJUnitSupport()
            specmaticJunitSupport = newInstance
            block(server)
            newInstance.contractTest().forEach { dynamicTest ->
                listener.onContractTest(dynamicTest)
                runCatching { dynamicTest.executable.execute() }
            }
        } finally {
            server.close()
            configFile.delete()
            SpecmaticJUnitSupport.settingsStaging.remove()
        }

        return this
    }

    fun verify(block: (RecordingTestReportListener) -> Unit): ContractTestScope {
        block(listener)
        return this
    }

    fun verifyOpenApiCoverage(block: OpenApiCoverageVerifier.() -> Unit): ContractTestScope {
        requireNotNull(specmaticJunitSupport) { "verify must be called post test execution" }
        val instance = specmaticJunitSupport as SpecmaticJUnitSupport
        block(OpenApiCoverageVerifier(instance.openApiCoverage))
        return this
    }

    private fun SpecmaticConfig.toYaml(): String {
        if (this is SpecmaticConfigV1V2Common) return yamlMapper.writeValueAsString(SpecmaticConfigV2.loadFrom(this))
        if (this is SpecmaticConfigV3Impl) return yamlMapper.writeValueAsString(this.specmaticConfig)
        return yamlMapper.writeValueAsString(this)
    }

    companion object {
        fun from(content: String, tempDir: File): ContractTestScope {
            val trimmed = content.trimStart()
            val extension = when {
                trimmed.startsWith("{") -> JSON
                trimmed.startsWith("<") -> "wsdl"
                else -> YAML
            }

            val specFile = tempDir.resolve("${UUID.randomUUID()}.$extension")
            specFile.parentFile.mkdirs()
            specFile.writeText(content)
            return ContractTestScope(specFile)
        }
    }
}

class RecordingTestReportListener : TestReportListener {
    private val _dynamicTests = mutableListOf<DynamicTest>()
    val dynamicTests: List<DynamicTest>
        get() = _dynamicTests

    private val _testResults = mutableListOf<TestExecutionResult>()
    val testResults: List<TestExecutionResult>
        get() = _testResults

    fun onContractTest(test: DynamicTest) {
        _dynamicTests += test
    }

    override fun onTestResult(result: TestExecutionResult) {
        _testResults += result
    }

    override fun onActuator(enabled: Boolean) = Unit
    override fun onActuatorApis(apisNotExcluded: List<API>, apisExcluded: List<API>) = Unit
    override fun onEndpointApis(endpointsNotExcluded: List<Endpoint>, endpointsExcluded: List<Endpoint>) = Unit
    override fun onExampleErrors(resultsBySpecFile: Map<String, io.specmatic.core.Result>) = Unit
    override fun onTestsComplete() = Unit
    override fun onEnd() = Unit
    override fun onCoverageCalculated(coverage: Int, absoluteCoverage: Int) = Unit
    override fun onPathCoverageCalculated(path: String, pathCoverage: Int) = Unit
    override fun onGovernance(result: io.specmatic.core.Result) = Unit
    override fun onTestDecision(decision: Decision<*, OpenAPIOperation>) = Unit
}
