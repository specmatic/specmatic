package io.specmatic.test

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.filters.ScenarioMetadataFilter.Companion.filterUsing
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.consoleLog
import io.specmatic.core.log.ignoreLog
import io.specmatic.core.log.logger
import io.specmatic.core.log.setLoggerUsing
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Examples
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.report.ReportGenerator
import io.specmatic.core.utilities.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.license.core.*
import io.specmatic.license.core.util.LicenseConfig
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.hasOpenApiFileExtension
import io.specmatic.stub.isOpenAPI
import io.specmatic.stub.isSupportedAPISpecification
import io.specmatic.test.reports.OpenApiCoverageReportProcessor
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverage
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.opentest4j.TestAbortedException
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import kotlin.streams.asStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Serializable
data class API(
    val method: String,
    val path: String,
)

@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class SpecmaticJUnitSupport {
    private var startTime: Instant? = null
    private var settings = ContractTestSettings(settingsStaging)

    private val specmaticConfig: SpecmaticConfig = settings.getSpecmaticConfig()
    private val keyDataRegistry: KeyDataRegistry = specmaticConfig.getTestHttpsConfiguration().toTestKeyDataRegistry()
    private val httpInteractionsLog: HttpInteractionsLog = HttpInteractionsLog()
    private val testFilter = ScenarioMetadataFilter.from(specmaticConfig.getTestFilter().orEmpty())
    private val prettyPrint = specmaticConfig.getPrettyPrint()

    init { setLoggerUsing(specmaticConfig.getLogConfigurationOrDefault()) }

    companion object {
        val settingsStaging = ThreadLocal<ContractTestSettings?>()

        const val HOST = "host"
        const val PORT = "port"
        const val PROTOCOL = "protocol"
        const val TEST_BASE_URL = "testBaseURL"
        const val FILTER = "filter"

        val partialSuccesses: ConcurrentLinkedDeque<Result.Success> = ConcurrentLinkedDeque()
    }

    internal val openApiCoverage: OpenApiCoverage = OpenApiCoverage(
        configFilePath = getConfigFileWithAbsolutePath(),
        filterExpression = settings.getReportFilter().orEmpty(),
        coverageHooks = settings.coverageHooks,
        previousTestResultRecord = settings.previousTestRuns,
        httpInteractionsLog = httpInteractionsLog
    )

    private val threads: Vector<String> = Vector<String>()

    private fun getReportConfiguration(): ReportConfiguration {
        val reportConfiguration = specmaticConfig.getReport()

        if (reportConfiguration == null) {
            logger.log("Could not load report configuration, coverage will be calculated but no coverage threshold will be enforced")
            return ReportConfiguration.default
        }
        return reportConfiguration
    }

    enum class ActuatorSetupResult(val failed: Boolean) {
        Success(false), Failure(true)
    }

    fun actuatorFromSwagger(testBaseURL: String, client: TestExecutor? = null): ActuatorSetupResult {
        // TODO: Deprecate and remove SWAGGER_UI_BASEURL
        val defaultBaseURL = specmaticConfig.getTestSwaggerUIBaseUrl() ?: testBaseURL
        val swaggerDocUrl = when {
            specmaticConfig.getTestSwaggerUrl() != null -> specmaticConfig.getTestSwaggerUrl().orEmpty()
            else -> "$defaultBaseURL/swagger/v1/swagger.yaml"
        }

        val request = HttpRequest(path = "/", method = "GET")
        val response = if (client != null) {
            client.execute(request)
        } else {
            HttpClient(
                swaggerDocUrl,
                log = ignoreLog,
                prettyPrint = prettyPrint,
                keyData = keyDataFor(swaggerDocUrl)
            ).use { httpClient ->
                httpClient.execute(request)
            }
        }

        if (response.status != 200) {
            logger.debug("Failed to query swaggerUI, status code: ${response.status}")
            return ActuatorSetupResult.Failure
        }

        val featureFromJson = OpenApiSpecification.fromYAML(response.body.toStringLiteral(), "").toFeature()
        val apis = featureFromJson.scenarios.map { scenario ->
            API(method = scenario.method, path = convertPathParameterStyle(scenario.path))
        }

        openApiCoverage.addAPIs(apis.distinct())
        openApiCoverage.setEndpointsAPIFlag(true)

        return ActuatorSetupResult.Success
    }

    fun queryActuator(): ActuatorSetupResult {
        val endpointsAPI = specmaticConfig.getActuatorUrl() ?: return ActuatorSetupResult.Failure
        val request = HttpRequest("GET")
        val response = HttpClient(
            endpointsAPI,
            log = ignoreLog,
            prettyPrint = prettyPrint,
            keyData = keyDataFor(endpointsAPI)
        ).use { httpClient ->
            httpClient.execute(request)
        }

        if (response.status != 200) {
            logger.debug("Failed to query actuator, status code: ${response.status}")
            return ActuatorSetupResult.Failure
        }

        logger.debug(response.toLogString())
        openApiCoverage.setEndpointsAPIFlag(true)
        val endpointData = response.body as JSONObjectValue
        val apis: List<API> = endpointData.getJSONObject("contexts").entries.flatMap { entry ->
            val mappings: JSONArrayValue =
                (entry.value as JSONObjectValue).findFirstChildByPath("mappings.dispatcherServlets.dispatcherServlet") as JSONArrayValue
            mappings.list.map { it as JSONObjectValue }.filter {
                it.findFirstChildByPath("details.handlerMethod.className")?.toStringLiteral()
                    ?.contains("springframework") != true
            }.flatMap {
                val methods: JSONArrayValue? =
                    it.findFirstChildByPath("details.requestMappingConditions.methods") as JSONArrayValue?
                val paths: JSONArrayValue? =
                    it.findFirstChildByPath("details.requestMappingConditions.patterns") as JSONArrayValue?

                if (methods != null && paths != null) {
                    methods.list.flatMap { method ->
                        paths.list.map { path ->
                            API(method.toStringLiteral(), path.toStringLiteral())
                        }
                    }
                } else {
                    emptyList()
                }
            }
        }

        openApiCoverage.addAPIs(apis)
        return ActuatorSetupResult.Success
    }

    private fun getConfigFileWithAbsolutePath() = File(settings.configFile.orEmpty()).canonicalPath

    fun generateCtrfReport() {
        val start = startTime?.toEpochMilli() ?: 0L
        val reportDirPath = specmaticConfig.getReportDirPath()
        val end = startTime?.let { Instant.now().toEpochMilli() } ?: 0L
        val coverageReport = openApiCoverage.generateWithoutHooks()

        consoleLog("Generating CTRF report using  coverageReportOperations...")
        ReportGenerator.generateReport(
            endTime = end,
            startTime = start,
            reportDir = File("$reportDirPath/test"),
            coverageReportOperations = coverageReport.coverageOperations,
            coverage = coverageReport.totalCoveragePercentage,
            actuatorEnabled = coverageReport.actuatorEnabled,
            absoluteCoverage = coverageReport.absoluteCoveragePercentage,
            testResultRecords = coverageReport.testResultRecords,
            specConfigs = coverageReport.getSpecConfigs(),
        )
    }

    @AfterAll
    fun report() {
        settings.coverageHooks.forEach { it.onTestsComplete() }
        val reportConfiguration = getReportConfiguration()
        val excludedOpenAPIEndpoints = reportConfiguration.excludedOpenAPIEndpoints() + excludedEndpointsFromEnv()
        openApiCoverage.addExcludedAPIs(excludedOpenAPIEndpoints)
        val report = openApiCoverage.generate()

        try {
            val reportProcessors = listOf(OpenApiCoverageReportProcessor(report, settings.reportBaseDirectory ?: "."))
            val config = specmaticConfig.updateReportConfiguration(reportConfiguration)
            reportProcessors.forEach { it.process(config) }
        } finally {
            report.onProcessingComplete()
            this.generateCtrfReport()
            threads.distinct().let {
                if (it.size > 1) {
                    logger.newLine()
                    logger.log("Executed tests in ${it.size} threads")
                }
            }
        }
    }

    private fun excludedEndpointsFromEnv() = System.getenv("SPECMATIC_EXCLUDED_ENDPOINTS")?.let { excludedEndpoints ->
        excludedEndpoints.split(",").map { it.trim() }
    } ?: emptyList()

    private fun getEnvConfig(envName: String?): JSONObjectValue {
        if (envName.isNullOrBlank())
            return JSONObjectValue()

        val configFileName = getConfigFilePath()
        if (!File(configFileName).exists())
            throw ContractException("Environment name $envName was specified but config file does not exist in the project root. Either avoid setting envName, or provide the configuration file with the environment settings.")

        val config = loadSpecmaticConfig(configFileName)

        return config.getEnvironment(envName)
    }

    private fun loadExceptionAsTestError(e: Throwable): Stream<DynamicTest> {
        return sequenceOf(DynamicTest.dynamicTest("Specmatic Test Suite") {
            ResultAssert.assertThat(Result.Failure(exceptionCauseMessage(e))).isSuccess()
        }).asStream()
    }

    private fun noTestsFoundError(reason: String): Stream<DynamicTest> {
        return sequenceOf(DynamicTest.dynamicTest("Specmatic Test Suite") {
            ResultAssert.assertThat(Result.Failure("No tests found to run. $reason")).isSuccess()
        }).asStream()
    }

    @TestFactory
    fun contractTest(): Stream<DynamicTest> {
        LicenseResolver.setCurrentExecutorIfNotSet(Executor.PROGRAMMATIC)

        settings = ContractTestSettings(settings, specmaticConfig)

        LicenseConfig.instance.utilization.shipDisabled = specmaticConfig.isTelemetryDisabled()
        partialSuccesses.clear()

        val filterName: String? = settings.filterName
        val filterNotName: String? = settings.filterNotName
        val useCurrentBranchForCentralRepo = specmaticConfig.getMatchBranchEnabled()
        val timeoutInMilliseconds = try {
            specmaticConfig.getTestTimeoutInMilliseconds()
        } catch (_: NumberFormatException) {
            throw ContractException("The test timeout should be a value of type long")
        } ?: DEFAULT_TIMEOUT_IN_MILLISECONDS

        val workingDirectory = WorkingDirectory(DEFAULT_WORKING_DIRECTORY)
        val suggestionsData = settings.inlineSuggestions.orEmpty()
        val suggestionsPath = settings.suggestionsPath.orEmpty()
        val envConfig = getEnvConfig(settings.envName)
        val testConfig = try {
            loadTestConfig(envConfig).withVariablesFromFilePath(settings.variablesFileName)
        } catch (e: Throwable) {
            return loadExceptionAsTestError(e)
        }
        val testBuildResult = try {
            when {
                settings.contractPaths != null -> {
                    // Default base URL is mandatory for this mode
                    val defaultBaseURL = constructTestBaseURL()
                    val contractPaths = settings.contractPaths.orEmpty().split(",").filter { File(it).extension in CONTRACT_EXTENSIONS }
                    val loadedScenariosByContractPath = contractPaths.map { contractPath ->
                        val overlayFilePath: String? = settings.overlayFilePath?.canonicalPath ?: specmaticConfig.getTestOverlayFilePath(File(contractPath), SpecType.OPENAPI)
                        val overlayContent = if (overlayFilePath.isNullOrBlank()) "" else readFrom(overlayFilePath, "overlay")
                        contractPath to loadTestScenarios(
                            contractPath,
                            suggestionsPath,
                            suggestionsData,
                            testConfig,
                            specificationPath = contractPath,
                            filterName = filterName,
                            filterNotName = filterNotName,
                            specmaticConfig = specmaticConfig,
                            overlayContent = overlayContent,
                            filter = testFilter
                        )
                    }

                    val endpoints: List<Endpoint> = loadedScenariosByContractPath.flatMap { (_, loaded) -> loaded.allEndpoints }
                    val filteredEndpoints: List<Endpoint> = loadedScenariosByContractPath.flatMap { (_, loaded) -> loaded.filteredEndpoints }
                    val exampleValidationResults = loadedScenariosByContractPath.associate { (contractPath, loaded) -> contractPath to loaded.exampleValidationResult }
                    val testsWithUrls: Sequence<Pair<ContractTest, String>> = loadedScenariosByContractPath.asSequence().flatMap { (_, loaded) ->
                        loaded.scenarios.map { test -> Pair(test, defaultBaseURL) }
                    }

                    TestData(testsWithUrls, endpoints, filteredEndpoints, setOf(defaultBaseURL), exampleValidationResults)
                }

                else -> {
                    if (File(settings.configFile.orEmpty()).exists().not()) exitWithMessage(MISSING_CONFIG_FILE_MESSAGE)

                    val contractFilePaths = contractTestPathsFrom(
                        settings.configFile.orEmpty(),
                        workingDirectory.path,
                        useCurrentBranchForCentralRepo
                    ).filter {
                        isSupportedAPISpecification(it.path)
                    }

                    exitIfAnyDoNotExist("The following specifications do not exist", contractFilePaths.map { it.path })

                    // Compute default base URL only if any spec lacks a provides baseUrl
                    val defaultBaseURL = constructTestBaseURL()
                    val loadedScenariosWithBaseUrlsByContractPath = contractFilePaths.filter {
                        File(it.path).extension in CONTRACT_EXTENSIONS
                    }.map { contractPathData ->
                        val overlayFilePath: String? = settings.overlayFilePath?.canonicalPath ?: specmaticConfig.getTestOverlayFilePath(File(contractPathData.path), SpecType.OPENAPI)
                        val overlayContent = if (overlayFilePath.isNullOrBlank()) "" else readFrom(overlayFilePath, "overlay")
                        val loadedTestScenarios = loadTestScenarios(
                            contractPathData.path,
                            "",
                            "",
                            testConfig,
                            contractPathData.provider,
                            contractPathData.repository,
                            contractPathData.branch,
                            contractPathData.specificationPath,
                            specmaticConfig.getSecurityConfiguration(File(contractPathData.path)),
                            filterName,
                            filterNotName,
                            specmaticConfig = specmaticConfig,
                            generative = contractPathData.generative,
                            overlayContent = overlayContent,
                            filter = testFilter,
                            exampleDirPaths = contractPathData.exampleDirPaths.orEmpty()
                        )

                        val resolvedBaseURL = contractPathData.baseUrl ?: defaultBaseURL
                        Triple(contractPathData.path, loadedTestScenarios, resolvedBaseURL)
                    }

                    val baseUrls = loadedScenariosWithBaseUrlsByContractPath.map { (_, _, resolvedBaseURL) -> resolvedBaseURL }.toSet()
                    val endpoints: List<Endpoint> = loadedScenariosWithBaseUrlsByContractPath.flatMap { (_, loaded, _) -> loaded.allEndpoints }
                    val filteredEndpoints: List<Endpoint> = loadedScenariosWithBaseUrlsByContractPath.flatMap { (_, loaded, _) -> loaded.filteredEndpoints }
                    val exampleValidationResults = loadedScenariosWithBaseUrlsByContractPath.associate { (contractPath, loaded, _) -> contractPath to loaded.exampleValidationResult }
                    val testsWithUrls: Sequence<Pair<ContractTest, String>> = loadedScenariosWithBaseUrlsByContractPath.asSequence().flatMap { (_, loaded, resolvedBaseURL) ->
                        loaded.scenarios.map { test -> Pair(test, resolvedBaseURL) }
                    }

                    TestData(testsWithUrls, endpoints, filteredEndpoints, baseUrls, exampleValidationResults)
                }
            }
        } catch (e: ContractException) {
            return loadExceptionAsTestError(e)
        } catch (e: Throwable) {
            return loadExceptionAsTestError(e)
        }

        settings.coverageHooks.forEach { it.onExampleErrors(testBuildResult.exampleValidationResults) }
        openApiCoverage.addEndpoints(testBuildResult.allEndpoints, testBuildResult.filteredEndpoints)
        val testScenariosWithUrls = try {
            val filteredPairsBasedOnName = selectTestsToRun(
                testBuildResult.scenarios,
                filterName,
                filterNotName
            ) { it.first.testDescription() }

            filteredPairsBasedOnName.filter { pair ->
                testFilter.isSatisfiedBy(pair.first.toScenarioMetadata())
            }
        } catch (e: ContractException) {
            return loadExceptionAsTestError(e)
        } catch (e: Throwable) {
            return loadExceptionAsTestError(e)
        }

        // Check if no tests remain after filtering
        if (!testScenariosWithUrls.iterator().hasNext()) {
            val filterDetails = buildString {
                if (!filterName.isNullOrBlank()) append("name filter: '$filterName'")
                if (!filterNotName.isNullOrBlank()) {
                    if (isNotEmpty()) append(", ")
                    append("exclude filter: '$filterNotName'")
                }
                if (testFilter.expression != null) {
                    if (isNotEmpty()) append(", ")
                    append("expression filter: \"${specmaticConfig.getTestFilter()}\"")
                }
            }
            val reason = if (filterDetails.isNotEmpty()) {
                "Applied filters ($filterDetails) matched no test scenarios."
            } else {
                "No test scenarios found."
            }
            return noTestsFoundError(reason)
        }

        val actuatorBaseURL = settings.baseUrlFromArgOrSysProp()
            ?: settings.baseUrlFromConfig()
            ?: testBuildResult.baseUrls.firstOrNull()
            ?: constructTestBaseURL()

        return try {
            dynamicTestStream(
                firstNScenarios(testScenariosWithUrls),
                actuatorBaseURL,
                timeoutInMilliseconds
            )
        } catch (e: Throwable) {
            logger.logError(e)
            loadExceptionAsTestError(e)
        }
    }

    private fun firstNScenarios(testScenarios: Sequence<Pair<ContractTest, String>>): Sequence<Pair<ContractTest, String>> {
        val maxTestCount = specmaticConfig.getMaxTestCount() ?: return testScenarios
        return testScenarios.take(maxTestCount)
    }

    private fun dynamicTestStream(
        testScenarios: Sequence<Pair<ContractTest, String>>,
        actuatorBaseURL: String,
        timeoutInMilliseconds: Long,
    ): Stream<DynamicTest>
    {
        val suiteAbortMessage = AtomicReference<String?>(null)

        try {
            if (queryActuator().failed && actuatorFromSwagger(actuatorBaseURL).failed) {
                openApiCoverage.setEndpointsAPIFlag(false)
                logger.boundary()
                logger.log("Endpoints API and SwaggerUI URL were not exposed by the application, so cannot calculate actual coverage")
            }
        } catch (exception: Throwable) {
            openApiCoverage.setEndpointsAPIFlag(false)
            logger.debug(exception, "Failed to query actuator with error")
        }

        logger.newLine()

        startTime = Instant.now()
        return testScenarios.map { (contractTest, baseURL) ->
            DynamicTest.dynamicTest(contractTest.testDescription()) {
                suiteAbortMessage.get()?.let { message ->
                    throw TestAbortedException(message)
                }

                LicenseResolver.utilize(
                    product = LicensedProduct.OPEN_SOURCE,
                    feature = SpecmaticFeature.TEST,
                    protocol = listOfNotNull(contractTest.protocol),
                )

                threads.add(Thread.currentThread().name)

                var testResult: ContractTestExecutionResult? = null

                try {
                    val log: (LogMessage) -> Unit = { logMessage ->
                        logger.log(logMessage)
                    }

                    val httpClient =
                        HttpClient(
                            baseURL,
                            log = log,
                            timeoutInMilliseconds = timeoutInMilliseconds,
                            prettyPrint = prettyPrint,
                            keyData = keyDataFor(baseURL),
                            httpInteractionsLog = httpInteractionsLog,
                        )

                    testResult =
                        httpClient.use {
                            contractTest.runTest(it)
                        }
                    val (result) = testResult

                    if (result is Result.Failure && result.failureReason == FailureReason.ConnectivityFailure) {
                        val message = connectivityFailureMessage(baseURL, result.message)
                        suiteAbortMessage.compareAndSet(null, message)
                        throw ContractException(message)
                    }

                    if (result is Result.Success && result.isPartialSuccess()) {
                        partialSuccesses.add(result)
                    }

                    when {
                        result.shouldBeIgnored() -> {
                            val message =
                                "Test FAILED, ignoring since the scenario is tagged @WIP${System.lineSeparator()}${
                                    result.toReport().toText().prependIndent("  ")
                                }"
                            throw TestAbortedException(message)
                        }

                        else -> ResultAssert.assertThat(result).isSuccess()
                    }

                } catch (e: Throwable) {
                    throw e
                } finally {
                    if (testResult != null) {
                        contractTest.testResultRecord(testResult)?.let { testResultRecord ->
                            openApiCoverage.addTestReportRecords(testResultRecord)
                        }
                    }
                }
            }
        }.asStream()
    }

    private fun connectivityFailureMessage(baseURL: String, reason: String): String {
        return """
            Cannot connect to server at: $baseURL
            Reason: $reason
            Please check:
            - Is the server running?
            - Is the testBaseURL correct?
        """.trimIndent()
    }

    fun constructTestBaseURL(): String {
        return testBaseUrlFromSettings()
            ?: hostAndPortFromSettings()
            ?: testBaseUrlFromConfig()
            ?: "http://localhost:9000"
    }

    private fun testBaseUrlFromSettings(): String? {
        val baseUrlFromArgOrSysProp = settings.baseUrlFromArgOrSysProp() ?: return null
        return validateBaseUrlOrAbort(baseUrlFromArgOrSysProp, "$TEST_BASE_URL environment variable")
    }

    private fun hostAndPortFromSettings(): String? {
        if (!settings.isHostOrPortExplicitlySpecified) return null

        val hostFromSettings = settings.host
        val portFromSettings = settings.port
        if (hostFromSettings.isNullOrBlank() || portFromSettings.isNullOrBlank()) return null

        val host = if (hostFromSettings.startsWith("http")) {
            URI(hostFromSettings).host
        } else {
            hostFromSettings
        }

        if (!isNumeric(portFromSettings)) {
            throw TestAbortedException("Please specify a number value for $PORT environment variable")
        }

        val protocol = settings.protocol ?: "http"
        val urlConstructedFromProtocolHostAndPort = "$protocol://$host:$portFromSettings"
        return when (validateTestOrStubUri(urlConstructedFromProtocolHostAndPort)) {
            URIValidationResult.Success -> urlConstructedFromProtocolHostAndPort
            else -> throw TestAbortedException("Please specify a valid $PROTOCOL, $HOST and $PORT environment variables")
        }
    }

    private fun testBaseUrlFromConfig(): String? {
        val baseUrl = settings.baseUrlFromConfig() ?: return null
        return validateBaseUrlOrAbort(baseUrl, "config file")
    }

    private fun validateBaseUrlOrAbort(baseUrl: String, source: String): String {
        return when (val validationResult = validateTestOrStubUri(baseUrl)) {
            URIValidationResult.Success -> baseUrl
            else -> throw TestAbortedException("${validationResult.message} in $source")
        }
    }

    private fun isNumeric(port: String?): Boolean {
        return port?.toIntOrNull() != null
    }

    fun loadTestScenarios(
        path: String,
        suggestionsPath: String,
        suggestionsData: String,
        config: TestConfig,
        sourceProvider: String? = null,
        sourceRepository: String? = null,
        sourceRepositoryBranch: String? = null,
        specificationPath: String? = null,
        securityConfiguration: SecurityConfiguration? = null,
        filterName: String?,
        filterNotName: String?,
        specmaticConfig: SpecmaticConfig = settings.getSpecmaticConfig(),
        generative: ResiliencyTestSuite? = null,
        overlayContent: String = "",
        filter: ScenarioMetadataFilter,
        exampleDirPaths: List<String> = emptyList()
    ): LoadedTestScenarios {
        if (hasOpenApiFileExtension(path) && !isOpenAPI(path)) {
            return LoadedTestScenarios(emptySequence(), emptyList(), emptyList())
        }

        val contractFile = File(path)
        val strictMode = specmaticConfig.getTestStrictMode() ?: false
        val effectiveSpecmaticConfig =
            when (generative) {
                ResiliencyTestSuite.positiveOnly -> specmaticConfig.enableResiliencyTests(onlyPositive = true)
                ResiliencyTestSuite.all -> specmaticConfig.enableResiliencyTests(onlyPositive = false)
                ResiliencyTestSuite.none, null -> specmaticConfig
            }

        val testDictionary = specmaticConfig.getTestDictionary()
        val feature =
            parseContractFileToFeature(
                contractFile.path,
                CommandHook(HookName.test_load_contract, contractFile),
                sourceProvider,
                sourceRepository,
                sourceRepositoryBranch,
                specificationPath,
                securityConfiguration,
                specmaticConfig = effectiveSpecmaticConfig,
                overlayContent = overlayContent,
                strictMode = strictMode,
                lenientMode = specmaticConfig.getTestLenientMode() ?: false,
                exampleDirPaths = exampleDirPaths
            ).copy(testVariables = config.variables, testBaseURLs = config.baseURLs).useDictionary(testDictionary)

        val suggestions = when {
            suggestionsPath.isNotEmpty() -> suggestionsFromFile(suggestionsPath)
            suggestionsData.isNotEmpty() -> suggestionsFromCommandLine(suggestionsData)
            else -> emptyList()
        }

        val allEndpoints = feature.scenarios.map { scenario ->
            Endpoint(
                convertPathParameterStyle(scenario.path),
                scenario.method,
                scenario.httpResponsePattern.status,
                scenario.soapActionUnescaped,
                scenario.sourceProvider,
                scenario.sourceRepository,
                scenario.sourceRepositoryBranch,
                scenario.specification,
                scenario.requestContentType,
                scenario.responseContentType,
                scenario.protocol,
                scenario.specType
            )
        }

        val featureWithExternalizedExamples = feature.loadExternalisedExamples()

        val filteredScenariosBasedOnName = selectTestsToRun(
            featureWithExternalizedExamples.scenarios.asSequence(),
            filterName,
            filterNotName
        ) {
            it.testDescription()
        }

        val filteredScenarios = filterUsing(
            filteredScenariosBasedOnName,
            filter
        )

        val filteredEndpoints = filteredScenarios.map { scenario ->
            Endpoint(
                convertPathParameterStyle(scenario.path),
                scenario.method,
                scenario.httpResponsePattern.status,
                scenario.soapActionUnescaped,
                scenario.sourceProvider,
                scenario.sourceRepository,
                scenario.sourceRepositoryBranch,
                scenario.specification,
                scenario.requestContentType,
                scenario.responseContentType,
                scenario.protocol,
                scenario.specType
            )
        }.toList()

        val filteredFeature = featureWithExternalizedExamples.copy(scenarios = filteredScenarios.toList())
        val (validExampleFeature, result) = filteredFeature.validateAndFilterExamples()
        if (specmaticConfig.getTestLenientMode() == false) result.throwOnFailure()

        validExampleFeature.validateExamplesOrException()
        val tests: Sequence<ContractTest> = validExampleFeature.also {
            if (it.scenarios.isEmpty()) {
                logger.log("All scenarios were filtered out.")
            } else if (it.scenarios.size < feature.scenarios.size) {
                logger.debug("Selected scenarios:")
                it.scenarios.forEach { scenario -> logger.debug(scenario.testDescription().prependIndent("  ")) }
            }
        }.generateContractTests(suggestions, originalScenarios = feature.scenarios)

        return LoadedTestScenarios(tests, allEndpoints, filteredEndpoints, result)
    }

    private fun suggestionsFromFile(suggestionsPath: String): List<Scenario> {
        return Suggestions.fromFile(suggestionsPath).scenarios
    }

    private fun suggestionsFromCommandLine(suggestions: String): List<Scenario> {
        val suggestionsValue = parsedValue(suggestions)
        if (suggestionsValue !is JSONObjectValue)
            throw ContractException("Suggestions must be a json value with scenario name as the key, and json array with 1 or more json objects containing suggestions")

        return suggestionsValue.jsonObject.mapValues { (_, exampleData) ->
            when {
                exampleData !is JSONArrayValue -> throw ContractException("The value of a scenario must be a list of examples")
                exampleData.list.isEmpty() -> Examples()
                else -> {
                    val columns = columnsFromExamples(exampleData)

                    val rows = exampleData.list.map { row ->
                        asJSONObjectValue(row)
                    }.map { row ->
                        Row(columns, columns.map { row.getValue(it).toStringLiteral() })
                    }.toMutableList()

                    Examples(columns, rows)
                }
            }
        }.entries.map { (name, examples) ->
            Scenario(
                name,
                HttpRequestPattern(),
                HttpResponsePattern(),
                emptyMap(),
                listOf(examples),
                emptyMap(),
                emptyMap(),
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        }
    }

    private fun readFrom(path: String, fileTag: String = ""): String {
        if (File(path).exists().not()) {
            throw ContractException("The $fileTag file $path does not exist. Please provide a valid $fileTag file")
        }
        if (File(path).extension != YAML && File(path).extension != JSON && File(path).extension != YML) {
            throw ContractException("The $fileTag file does not have a valid extension.")
        }
        return File(path).readText()
    }

    private fun keyDataFor(url: String): KeyData? {
        val uri = URI(url)
        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else when (uri.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> return null
        }

        return keyDataRegistry.get(host, port)
    }
}

data class LoadedTestScenarios(
    val scenarios: Sequence<ContractTest>,
    val allEndpoints: List<Endpoint>,
    val filteredEndpoints: List<Endpoint>,
    val exampleValidationResult: Result = Result.Success()
)

private data class TestData(
    val scenarios: Sequence<Pair<ContractTest, String>>,
    val allEndpoints: List<Endpoint>,
    val filteredEndpoints: List<Endpoint>,
    val baseUrls: Set<String>,
    val exampleValidationResults: Map<String, Result>,
)

private fun columnsFromExamples(exampleData: JSONArrayValue): List<String> {
    val firstRow = exampleData.list[0]
    if (firstRow !is JSONObjectValue)
        throw ContractException("Each value in the list of suggestions must be a json object containing column name as key and sample value as the value")

    return firstRow.jsonObject.keys.toList()
}

private fun asJSONObjectValue(value: Value): Map<String, Value> {
    val errorMessage =
        "Each value in the list of suggestions must be a json object containing column name as key and sample value as the value"
    if (value !is JSONObjectValue)
        throw ContractException(errorMessage)

    return value.jsonObject
}

fun <T> selectTestsToRun(
    testScenarios: Sequence<T>,
    filterName: String? = null,
    filterNotName: String? = null,
    getTestDescription: (T) -> String
): Sequence<T> {
    val filteredByName = if (!filterName.isNullOrBlank()) {
        val filterNames = filterName.split(",").map { it.trim() }

        testScenarios.filter { test ->
            filterNames.any { getTestDescription(test).contains(it) }
        }
    } else
        testScenarios

    val filteredByNotName: Sequence<T> = if (!filterNotName.isNullOrBlank()) {
        val filterNotNames = filterNotName.split(",").map { it.trim() }

        filteredByName.filterNot { test ->
            filterNotNames.any { getTestDescription(test).contains(it) }
        }
    } else
        filteredByName

    return filteredByNotName
}


fun isBaseURLReachable(baseUrl: String, timeOutMs: Int = 3000, keyData: KeyData? = null): Boolean {
    return try {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val connection = URL(url).openConnection() as HttpURLConnection

        if (connection is HttpsURLConnection) {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                }
            )

            val keyManagers = keyData?.let {
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(it.keyStore, it.keyPassword.toCharArray())
                }.keyManagers
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagers, trustAllCerts, SecureRandom())
            }
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }

        connection.requestMethod = "HEAD"
        connection.connectTimeout = timeOutMs
        connection.readTimeout = timeOutMs
        connection.instanceFollowRedirects = true
        connection.connect()

        val code = connection.responseCode
        code in 200..499
    } catch (e: Exception) {
        false
    }
}
