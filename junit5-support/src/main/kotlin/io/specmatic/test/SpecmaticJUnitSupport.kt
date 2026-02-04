package io.specmatic.test

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.config.ReportConfiguration
import io.specmatic.core.config.ResiliencyTestSuite
import io.specmatic.core.config.SecurityConfiguration
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.filters.ScenarioMetadataFilter.Companion.filterUsing
import io.specmatic.core.log.LogMessage
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
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.hasOpenApiFileExtension
import io.specmatic.stub.isOpenAPI
import io.specmatic.stub.isSupportedAPISpecification
import io.specmatic.test.TestResultRecord.Companion.getCoverageStatus
import io.specmatic.test.reports.OpenApiCoverageReportProcessor
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
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
import java.util.stream.Stream
import kotlin.streams.asStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
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
    private val settings = ContractTestSettings(settingsStaging)
    private val specmaticConfig: SpecmaticConfig = settings.getSpecmaticConfig()
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

    internal val openApiCoverageReportInput: OpenApiCoverageReportInput =
        OpenApiCoverageReportInput(
            configFilePath = getConfigFileWithAbsolutePath(),
            filterExpression = specmaticConfig.getTestFilter().orEmpty(),
            coverageHooks = settings.coverageHooks,
            httpInteractionsLog = httpInteractionsLog,
            previousTestResultRecord = settings.previousTestRuns,
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

        val httpClient = client ?: LegacyHttpClient(swaggerDocUrl, log = ignoreLog, prettyPrint = prettyPrint)
        val request = HttpRequest(path = "/", method = "GET")
        val response = httpClient.execute(request)

        if (response.status != 200) {
            logger.debug("Failed to query swaggerUI, status code: ${response.status}")
            return ActuatorSetupResult.Failure
        }

        val featureFromJson = OpenApiSpecification.fromYAML(response.body.toStringLiteral(), "").toFeature()
        val apis = featureFromJson.scenarios.map { scenario ->
            API(method = scenario.method, path = convertPathParameterStyle(scenario.path))
        }

        openApiCoverageReportInput.addAPIs(apis.distinct())
        openApiCoverageReportInput.setEndpointsAPIFlag(true)

        return ActuatorSetupResult.Success
    }

    fun queryActuator(): ActuatorSetupResult {
        val endpointsAPI = specmaticConfig.getActuatorUrl() ?: return ActuatorSetupResult.Failure
        val request = HttpRequest("GET")
        val response = LegacyHttpClient(endpointsAPI, log = ignoreLog, prettyPrint = prettyPrint).execute(request)

        if (response.status != 200) {
            logger.debug("Failed to query actuator, status code: ${response.status}")
            return ActuatorSetupResult.Failure
        }

        logger.debug(response.toLogString())
        openApiCoverageReportInput.setEndpointsAPIFlag(true)
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
        openApiCoverageReportInput.addAPIs(apis)

        return ActuatorSetupResult.Success
    }

    private fun getConfigFileWithAbsolutePath() = File(settings.configFile.orEmpty()).canonicalPath

    @AfterAll
    fun report() {
        settings.coverageHooks.forEach { it.onTestsComplete() }
        val reportProcessors =
            listOf(OpenApiCoverageReportProcessor(openApiCoverageReportInput, settings.reportBaseDirectory ?: "."))
        val reportConfiguration = getReportConfiguration()
        val config = specmaticConfig.updateReportConfiguration(reportConfiguration)

        reportProcessors.forEach { it.process(config) }

        val report = openApiCoverageReportInput.generateCoverageReport(emptyList())
        val start = startTime?.toEpochMilli() ?: 0L
        val end = startTime?.let { Instant.now().toEpochMilli() } ?: 0L

        val specConfigs = openApiCoverageReportInput.endpoints()
            .groupBy {
                it.specification.orEmpty()
            }.flatMap { (_, groupedEndpoints) ->
                groupedEndpoints.map {
                    CtrfSpecConfig(
                        protocol = it.protocol.key,
                        specType = it.specType.value,
                        specification = it.specification.orEmpty(),
                        sourceProvider = it.sourceProvider,
                        repository = it.sourceRepository,
                        branch = it.sourceRepositoryBranch ?: "main"
                    )
                }
            }

        val reportDirPath = specmaticConfig?.getReportDirPath() ?: defaultReportDirPath
        ReportGenerator.generateReport(
            testResultRecords = report.testResultRecords,
            startTime = start,
            endTime = end,
            specConfigs = specConfigs,
            coverage = report.totalCoveragePercentage,
            reportDir = File("$reportDirPath/test")
        ) { ctrfTestResultRecords ->
            ctrfTestResultRecords.filterIsInstance<TestResultRecord>().getCoverageStatus()
        }


        threads.distinct().let {
            if (it.size > 1) {
                logger.newLine()
                logger.log("Executed tests in ${it.size} threads")
            }
        }
    }

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
        return sequenceOf(DynamicTest.dynamicTest("Load Error") {
            ResultAssert.assertThat(Result.Failure(exceptionCauseMessage(e))).isSuccess()
        }).asStream()
    }

    private fun noTestsFoundError(reason: String): Stream<DynamicTest> {
        return sequenceOf(DynamicTest.dynamicTest("No Tests Found") {
            ResultAssert.assertThat(Result.Failure("No tests found to run. $reason")).isSuccess()
        }).asStream()
    }

    @TestFactory
    fun contractTest(): Stream<DynamicTest> {
        LicenseResolver.setCurrentExecutorIfNotSet(Executor.PROGRAMMATIC)

        LicenseConfig.instance.utilization.shipDisabled = specmaticConfig.isTelemetryDisabled()
        partialSuccesses.clear()

        val filterName: String? = settings.filterName
        val filterNotName: String? = settings.filterNotName
        val overlayFilePath: String? = settings.overlayFilePath?.canonicalPath
        val overlayContent = if (overlayFilePath.isNullOrBlank()) "" else readFrom(overlayFilePath, "overlay")
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

                    val testScenariosAndEndpointsPairList = settings.contractPaths.split(",").filter {
                        File(it).extension in CONTRACT_EXTENSIONS
                    }.map {
                        val (tests, endpoints, filteredEndpoints) = loadTestScenarios(
                            it,
                            suggestionsPath,
                            suggestionsData,
                            testConfig,
                            specificationPath = it,
                            filterName = filterName,
                            filterNotName = filterNotName,
                            specmaticConfig = specmaticConfig,
                            overlayContent = overlayContent,
                            filter = testFilter
                        )

                        Triple(tests.map { test -> Pair(test, defaultBaseURL) }, endpoints, filteredEndpoints)
                    }

                    val testsWithUrls: Sequence<Pair<ContractTest, String>> =
                        testScenariosAndEndpointsPairList.asSequence().flatMap { it.first }
                    val endpoints: List<Endpoint> = testScenariosAndEndpointsPairList.flatMap { it.second }
                    val filteredEndpoints: List<Endpoint> = testScenariosAndEndpointsPairList.flatMap { it.third }

                    TestData(testsWithUrls, endpoints, filteredEndpoints, setOf(defaultBaseURL))
                }

                else -> {
                    if (File(settings.configFile.orEmpty()).exists().not()) exitWithMessage(MISSING_CONFIG_FILE_MESSAGE)

                    createIfDoesNotExist(workingDirectory.path)

                    val contractFilePaths = contractTestPathsFrom(
                        settings.configFile.orEmpty(),
                        workingDirectory.path,
                        useCurrentBranchForCentralRepo
                    ).filter {
                        isSupportedAPISpecification(it.path)
                    }

                    exitIfAnyDoNotExist("The following specifications do not exist", contractFilePaths.map { it.path })

                    // Compute default base URL only if any spec lacks a provides baseUrl
                    val needsDefaultBase = contractFilePaths.any { it.baseUrl.isNullOrBlank() }
                    val defaultBaseURL = if (needsDefaultBase) constructTestBaseURL() else ""

                    val baseUrls = mutableSetOf<String>()
                    val testScenariosAndEndpointsPairList = contractFilePaths.filter {
                        File(it.path).extension in CONTRACT_EXTENSIONS
                    }.map { contractPathData ->
                        val (tests, endpoints, filteredEndpoints) = loadTestScenarios(
                            contractPathData.path,
                            "",
                            "",
                            testConfig,
                            contractPathData.provider,
                            contractPathData.repository,
                            contractPathData.branch,
                            contractPathData.specificationPath,
                            specmaticConfig.getSecurityConfiguration(),
                            filterName,
                            filterNotName,
                            specmaticConfig = specmaticConfig,
                            generative = contractPathData.generative,
                            overlayContent = overlayContent,
                            filter = testFilter,
                            exampleDirPaths = contractPathData.exampleDirPaths.orEmpty()
                        )

                        val resolvedBaseURL = contractPathData.baseUrl ?: defaultBaseURL
                        baseUrls.add(resolvedBaseURL)
                        Triple(tests.map { test -> Pair(test, resolvedBaseURL) }, endpoints, filteredEndpoints)
                    }

                    val testsWithUrls: Sequence<Pair<ContractTest, String>> =
                        testScenariosAndEndpointsPairList.asSequence().flatMap { it.first }
                    val endpoints: List<Endpoint> = testScenariosAndEndpointsPairList.flatMap { it.second }
                    val filteredEndpoints: List<Endpoint> = testScenariosAndEndpointsPairList.flatMap { it.third }

                    TestData(testsWithUrls, endpoints, filteredEndpoints, baseUrls.toSet())
                }
            }
        } catch (e: ContractException) {
            return loadExceptionAsTestError(e)
        } catch (e: Throwable) {
            return loadExceptionAsTestError(e)
        }

        testBuildResult.baseUrls.forEach { baseUrl ->
            if (isBaseURLReachable(baseUrl)) return@forEach
            return loadExceptionAsTestError(e = ContractException("""
            Cannot connect to server at: $baseUrl
            Please check:
            - Is the server running?
            - Is the testBaseURL correct?
            """.trimIndent()))
        }

        openApiCoverageReportInput.addEndpoints(testBuildResult.allEndpoints, testBuildResult.filteredEndpoints)
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

        val actuatorBaseURL = settings.testBaseURL ?: testBuildResult.baseUrls.firstOrNull() ?: constructTestBaseURL()
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
    ): Stream<DynamicTest> {
        try {
            if (queryActuator().failed && actuatorFromSwagger(actuatorBaseURL).failed) {
                openApiCoverageReportInput.setEndpointsAPIFlag(false)
                logger.boundary()
                logger.log("Endpoints API and SwaggerUI URL were not exposed by the application, so cannot calculate actual coverage")
            }
        } catch (exception: Throwable) {
            openApiCoverageReportInput.setEndpointsAPIFlag(false)
            logger.debug(exception, "Failed to query actuator with error")
        }

        logger.newLine()

        startTime = Instant.now()
        return testScenarios.map { (contractTest, baseURL) ->
            DynamicTest.dynamicTest(contractTest.testDescription()) {
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
                            httpInteractionsLog = httpInteractionsLog,
                        )

                    testResult =
                        httpClient.use { httpClient ->
                            contractTest.runTest(httpClient)
                        }
                    val (result) = testResult

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
                            openApiCoverageReportInput.addTestReportRecords(testResultRecord)
                        }
                    }
                }
            }
        }.asStream()
    }

    fun constructTestBaseURL(): String {
        if (settings.testBaseURL != null) {
            when (val validationResult = validateTestOrStubUri(settings.testBaseURL)) {
                URIValidationResult.Success -> return settings.testBaseURL
                else -> throw TestAbortedException("${validationResult.message} in $TEST_BASE_URL environment variable")
            }
        }

        // If testBaseURL is not provided, assume http://localhost:9000 by default.
        if (!settings.host.isNullOrBlank() && !settings.port.isNullOrBlank()) {
            val host = if (settings.host.startsWith("http")) {
                URI(settings.host).host
            } else {
                settings.host
            }

            if (!isNumeric(settings.port)) {
                throw TestAbortedException("Please specify a number value for $PORT environment variable")
            }

            val protocol = settings.protocol ?: "http"
            val urlConstructedFromProtocolHostAndPort = "$protocol://$host:${settings.port}"
            return when (validateTestOrStubUri(urlConstructedFromProtocolHostAndPort)) {
                URIValidationResult.Success -> urlConstructedFromProtocolHostAndPort
                else -> throw TestAbortedException("Please specify a valid $PROTOCOL, $HOST and $PORT environment variables")
            }
        }

        return "http://localhost:9000"
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
        val rawSpecmaticConfig = specmaticConfig
        val effectiveSpecmaticConfig =
            when (generative) {
                ResiliencyTestSuite.positiveOnly -> rawSpecmaticConfig.copyResiliencyTestsConfig(onlyPositive = true)
                ResiliencyTestSuite.all -> rawSpecmaticConfig.copyResiliencyTestsConfig(onlyPositive = false)
                ResiliencyTestSuite.none, null -> rawSpecmaticConfig
            }

        val feature =
            parseContractFileToFeature(
                contractFile.path,
                CommandHook(HookName.test_load_contract),
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
            ).copy(testVariables = config.variables, testBaseURLs = config.baseURLs)

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
                scenario.sourceProvider,
                scenario.sourceRepository,
                scenario.sourceRepositoryBranch,
                scenario.specification,
                scenario.requestContentType,
                scenario.httpResponsePattern.headersPattern.contentType,
                scenario.protocol,
                scenario.specType
            )
        }

        val filteredScenariosBasedOnName = selectTestsToRun(
            feature.scenarios.asSequence(),
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
                scenario.sourceProvider,
                scenario.sourceRepository,
                scenario.sourceRepositoryBranch,
                scenario.specification,
                scenario.requestContentType,
                scenario.httpResponsePattern.headersPattern.contentType,
                scenario.protocol,
                scenario.specType
            )
        }.toList()

        val filteredFeature = feature
            .copy(scenarios = filteredScenarios.toList())
            .loadExternalisedExamples()
            .also { it.validateExamplesOrException() }

        val tests: Sequence<ContractTest> = filteredFeature.also {
            if (it.scenarios.isEmpty()) {
                logger.log("All scenarios were filtered out.")
            } else if (it.scenarios.size < feature.scenarios.size) {
                logger.debug("Selected scenarios:")
                it.scenarios.forEach { scenario -> logger.debug(scenario.testDescription().prependIndent("  ")) }
            }
        }.generateContractTests(suggestions, originalScenarios = feature.scenarios)

        return LoadedTestScenarios(tests, allEndpoints, filteredEndpoints)
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
}

data class LoadedTestScenarios(
    val scenarios: Sequence<ContractTest>,
    val allEndpoints: List<Endpoint>,
    val filteredEndpoints: List<Endpoint>
)

private data class TestData(
    val scenarios: Sequence<Pair<ContractTest, String>>,
    val allEndpoints: List<Endpoint>,
    val filteredEndpoints: List<Endpoint>,
    val baseUrls: Set<String>,
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


fun isBaseURLReachable(baseUrl: String, timeOutMs: Int = 3000): Boolean {
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
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
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
