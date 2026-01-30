package application.mock

import application.CertInfo
import application.HTTPStubEngine
import application.StubLoaderEngine
import application.findRandomFreePort
import application.portIsInUse
import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.Configuration
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_PORT
import io.specmatic.core.Feature
import io.specmatic.core.KeyData
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfig.Companion.orDefault
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.loadSpecmaticConfigOrNull
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.utilities.ContractPathData.Companion.specToBaseUrlMap
import io.specmatic.core.utilities.throwExceptionIfDirectoriesAreInvalid
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.ContractStub
import io.specmatic.stub.HttpClientFactory
import io.specmatic.stub.listener.MockEventListener
import io.specmatic.core.utilities.exitIfAnyDoNotExist
import io.specmatic.stub.endPointFromHostAndPort
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_BASE_URL
import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.HttpStubFilterContext
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.log.*
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.stub.isSupportedAPISpecification
import java.io.File

data class MockInitializerInputs(
    val contractPaths: List<String> = emptyList(),
    val exampleDirs: List<String> = emptyList(),
    val host: String = DEFAULT_HTTP_STUB_HOST,
    val port: Int = DEFAULT_HTTP_STUB_PORT.toInt(),
    val strictMode: Boolean? = null,
    val passThroughTargetBase: String = "",
    val filter: String? = null,
    val gracefulRestartTimeoutInMs: Long? = null,
    val lenientMode: Boolean? = null,
    val verbose: Boolean? = null,
    val configFileName: String? = null,
    val useCurrentBranchForCentralRepo: Boolean? = null,
    val httpsOpts: HttpsConfiguration.Companion.HttpsFromOpts = HttpsConfiguration.Companion.HttpsFromOpts(),
    val httpClientFactory: HttpClientFactory = HttpClientFactory(),
    val listeners: List<MockEventListener> = emptyList(),
    val applicationSpecmaticConfig: application.SpecmaticConfig = application.SpecmaticConfig(),
    val httpStubEngine: HTTPStubEngine = HTTPStubEngine(),
    val stubLoaderEngine: StubLoaderEngine = StubLoaderEngine(),
    val delayInMilliseconds: Long? = null
)

class MockInitializer {
    fun createAndStart(input: MockInitializerInputs): ContractStub {
        val specmaticConfig = loadSpecmaticConfig(input)
        val keyData = resolveKeyData(input, specmaticConfig)

        val resolvedPort = resolvePort(input)
        setBaseUrl(input.host, resolvedPort, keyData)

        val contractSources = resolveContractSources(input, input.applicationSpecmaticConfig, specmaticConfig)
        validateContracts(contractSources)

        val stubData = loadStubs(input, contractSources, specmaticConfig)
        val filteredStubData = filterStubs(stubData, specmaticConfig)

        return runStubEngine(
            input,
            specmaticConfig,
            keyData,
            resolvedPort,
            contractSources,
            filteredStubData
        )
    }

    fun getSpecmaticConfig(input: MockInitializerInputs): SpecmaticConfig {
        return loadSpecmaticConfig(input)
    }

    private fun runStubEngine(
        input: MockInitializerInputs,
        specmaticConfig: SpecmaticConfig,
        keyData: KeyData?,
        port: Int,
        contractSources: List<ContractPathData>,
        stubs: List<Pair<Feature, List<ScenarioStub>>>
    ): ContractStub {
        val resolvedStrictMode = configuredStrictMode(input, specmaticConfig)
        val configuredGracefulTimeout = configuredGracefulTimeout(input, specmaticConfig)
        return input.httpStubEngine.runHTTPStub(
            stubs = stubs,
            host = input.host,
            port = port,
            keyData = keyData,
            strictMode = resolvedStrictMode,
            passThroughTargetBase = input.passThroughTargetBase,
            specmaticConfig = specmaticConfig,
            httpClientFactory = input.httpClientFactory,
            workingDirectory = WorkingDirectory(),
            gracefulRestartTimeoutInMs = configuredGracefulTimeout,
            specToBaseUrlMap = contractSources.specToBaseUrlMap(),
            listeners = input.listeners
        )
    }

    private fun loadSpecmaticConfig(input: MockInitializerInputs): SpecmaticConfig {
        if (input.configFileName != null) Configuration.configFilePath = input.configFileName
        val configPath = File(Configuration.configFilePath).canonicalPath
        val config = loadSpecmaticConfigOrNull(configPath, explicitlySpecifiedByUser = input.configFileName != null).orDefault()
        val sourcesUpdated = config.mapSources { source -> source.copy(matchBranch = input.useCurrentBranchForCentralRepo ?: source.matchBranch) }
        val stubConfigUpdated = sourcesUpdated.withStubModes(strictMode = input.strictMode).withStubFilter(filter = input.filter)
        return input.delayInMilliseconds?.let(stubConfigUpdated::withGlobalMockDelay) ?: stubConfigUpdated
    }

    private fun resolveKeyData(input: MockInitializerInputs, specmaticConfig: SpecmaticConfig): KeyData? {
        return CertInfo(input.httpsOpts, specmaticConfig.getStubHttpsConfiguration()).getHttpsCert(aliasSuffix = "mock")
    }

    private fun resolvePort(input: MockInitializerInputs): Int {
        return if (isDefaultPort(input.port) && portIsInUse(input.host, input.port)) findRandomFreePort()
        else input.port
    }

    private fun setBaseUrl(host: String, port: Int, keyData: KeyData?) {
        val baseUrl = endPointFromHostAndPort(host, port, keyData)
        System.setProperty(SPECMATIC_BASE_URL, baseUrl)
    }

    private fun resolveContractSources(input: MockInitializerInputs, applicationConfig: application.SpecmaticConfig, specmaticConfig: SpecmaticConfig): List<ContractPathData> {
        val lenient = input.lenientMode ?: false
        val matchBranchEnabled = specmaticConfig.getMatchBranchEnabled()
        return if (input.contractPaths.isEmpty()) {
            applicationConfig.contractStubPathData(matchBranchEnabled).map {
                it.copy(lenientMode = lenient)
            }.filter {
                isSupportedAPISpecification(it.path)
            }
        } else {
            input.contractPaths.map {
                ContractPathData("", it, lenientMode = lenient)
            }
        }
    }

    private fun loadStubs(input: MockInitializerInputs, contractSources: List<ContractPathData>, specmaticConfig: SpecmaticConfig): List<Pair<Feature, List<ScenarioStub>>> {
        val resolvedStrictMode = configuredStrictMode(input, specmaticConfig)
        if (resolvedStrictMode) {
            throwExceptionIfDirectoriesAreInvalid(input.exampleDirs, "example directories")
        }

        val stubData = input.stubLoaderEngine.loadStubs(
            contractPathDataList = contractSources,
            dataDirs = input.exampleDirs,
            specmaticConfigPath = null,
            strictMode = resolvedStrictMode
        )

        logStubLoadingSummary(stubData, input.verbose)
        return stubData
    }

    private fun filterStubs(stubData: List<Pair<Feature, List<ScenarioStub>>>, specmaticConfig: SpecmaticConfig): List<Pair<Feature, List<ScenarioStub>>> {
        val stubFilter = specmaticConfig.getStubFilter().orEmpty()
        val filteredStubData = stubData.mapNotNull { (feature, scenarioStubs) ->
            val metadataFilter = ScenarioMetadataFilter.from(stubFilter)
            val filteredScenarios = ScenarioMetadataFilter.filterUsing(
                feature.scenarios.asSequence(),
                metadataFilter,
            ).toList()
            val stubFilterExpression = ExpressionStandardizer.filterToEvalEx(stubFilter)
            val filteredStubScenario = scenarioStubs.filter {
                stubFilterExpression.with("context", HttpStubFilterContext(it)).evaluate().booleanValue
            }
            if (filteredScenarios.isNotEmpty()) {
                val updatedFeature = feature.copy(scenarios = filteredScenarios)
                updatedFeature to filteredStubScenario
            } else null
        }

        if (stubFilter != "" && filteredStubData.isEmpty()) {
            consoleLog(StringLog("FATAL: No stubs found for the given filter: $stubFilter"))
            return emptyList()
        }

        return filteredStubData
    }

    private fun validateContracts(contractSources: List<ContractPathData>) {
        val paths = contractSources.map { it.path }
        exitIfAnyDoNotExist("The following specifications do not exist", paths)
        validateContractFileExtensions(paths)
    }

    private fun isDefaultPort(port: Int): Boolean {
        return DEFAULT_HTTP_STUB_PORT == port.toString()
    }

    private fun configuredStrictMode(input: MockInitializerInputs, specmaticConfig: SpecmaticConfig): Boolean {
        return input.strictMode ?: specmaticConfig.getStubStrictMode() ?: false
    }

    private fun configuredGracefulTimeout(input: MockInitializerInputs, specmaticConfig: SpecmaticConfig): Long {
        return input.gracefulRestartTimeoutInMs ?: specmaticConfig.getStubGracefulRestartTimeoutInMilliseconds() ?: 1000
    }

    private fun logStubLoadingSummary(stubData: List<Pair<Feature, List<ScenarioStub>>>, verbose: Boolean?) {
        val totalStubs = stubData.sumOf { it.second.size }

        if (verbose == true) {
            logger.boundary()
            consoleLog(StringLog("Loaded stubs:"))
            stubData.forEach { (feature, stubs) ->
                val featureName = feature.specification ?: feature.path
                stubs.forEach { stub ->
                    val stubDescription = buildStubDescription(stub)
                    consoleLog(StringLog("  - $featureName: $stubDescription"))
                }
            }
            consoleLog(StringLog("Total: $totalStubs example(s) loaded"))
        }

        logger.boundary()
    }

    private fun buildStubDescription(stub: ScenarioStub): String {
        val request = stub.partial?.request ?: stub.request
        val method = request.method
        val path = request.path
        return "$method $path"
    }

    private fun validateContractFileExtensions(contractPaths: List<String>) {
        contractPaths.map(::File).filter {
            it.isFile && it.extension !in CONTRACT_EXTENSIONS
        }.let {
            if (it.isNotEmpty()) {
                val files = it.joinToString("\n") { file -> file.path }
                exitWithMessage("The following files do not end with $CONTRACT_EXTENSIONS and cannot be used:\n$files")
            }
        }
    }
}
