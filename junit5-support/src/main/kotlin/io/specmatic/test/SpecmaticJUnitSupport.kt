package io.specmatic.test

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.filters.ScenarioMetadataFilter.Companion.filterUsing
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.ignoreLog
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Examples
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.*
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_TIMEOUT
import io.specmatic.core.utilities.Flags.Companion.getLongValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.stub.hasOpenApiFileExtension
import io.specmatic.stub.isOpenAPI
import io.specmatic.test.reports.OpenApiCoverageReportProcessor
import io.specmatic.test.reports.TestReportHooks
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.opentest4j.TestAbortedException
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.util.*
import java.util.stream.Stream
import javax.management.ObjectName
import kotlin.streams.asStream

interface ContractTestStatisticsMBean {
    fun testsExecuted(): Int
}

class ContractTestStatistics : ContractTestStatisticsMBean {
    override fun testsExecuted(): Int = SpecmaticJUnitSupport.openApiCoverageReportInput.testResultRecords.size
}

@Serializable
data class API(val method: String, val path: String)

@Execution(ExecutionMode.CONCURRENT)
open class SpecmaticJUnitSupport {
    private val testFilter = ScenarioMetadataFilter.from(readEnvVarOrProperty(FILTER, FILTER).orEmpty())

    companion object {
        const val CONTRACT_PATHS = "contractPaths"
        const val WORKING_DIRECTORY = "workingDirectory"
        const val INLINE_SUGGESTIONS = "suggestions"
        const val SUGGESTIONS_PATH = "suggestionsPath"
        const val HOST = "host"
        const val PORT = "port"
        const val PROTOCOL = "protocol"
        const val TEST_BASE_URL = "testBaseURL"
        const val ENV_NAME = "environment"
        const val VARIABLES_FILE_NAME = "variablesFileName"
        const val FILTER_NAME_PROPERTY = "filterName"
        const val FILTER_NOT_NAME_PROPERTY = "filterNotName"
        const val FILTER = "filter"
        const val FILTER_NAME_ENVIRONMENT_VARIABLE = "FILTER_NAME"
        const val FILTER_NOT_NAME_ENVIRONMENT_VARIABLE = "FILTER_NOT_NAME"
        const val OVERLAY_FILE_PATH = "overlayFilePath"
        const val STRICT_MODE = "strictMode"
        private const val ENDPOINTS_API = "endpointsAPI"
        private const val SWAGGER_UI_BASEURL = "swaggerUIBaseURL"

        val partialSuccesses: MutableList<Result.Success> = mutableListOf()
        private var specmaticConfig: SpecmaticConfig? = null
        var openApiCoverageReportInput: OpenApiCoverageReportInput = OpenApiCoverageReportInput(getConfigFileWithAbsolutePath())

        private val threads: Vector<String> = Vector<String>()

        @AfterAll
        @JvmStatic
        fun report() {
            TestReportHooks.onEachListener { onTestsComplete() }
            val reportProcessors = listOf(OpenApiCoverageReportProcessor(openApiCoverageReportInput))
            val reportConfiguration = getReportConfiguration()
            val config = specmaticConfig?.updateReportConfiguration(reportConfiguration) ?: SpecmaticConfig().updateReportConfiguration(reportConfiguration)

            reportProcessors.forEach { it.process(config) }

            threads.distinct().let {
                if(it.size > 1) {
                    logger.newLine()
                    logger.log("Executed tests in ${it.size} threads")
                }
            }
        }

        private fun getReportConfiguration(): ReportConfiguration {
            val reportConfiguration = specmaticConfig?.getReport()

            if (reportConfiguration == null) {
                logger.log("Could not load report configuration, coverage will be calculated but no coverage threshold will be enforced")
                return ReportConfiguration.default
            }

            return reportConfiguration.withDefaultFormattersIfMissing()
        }

        enum class ActuatorSetupResult(val failed: Boolean) {
            Success(false), Failure(true)
        }

        fun actuatorFromSwagger(testBaseURL: String, client: TestExecutor? = null): ActuatorSetupResult {
            val baseURL = Flags.getStringValue(SWAGGER_UI_BASEURL) ?: testBaseURL
            val httpClient = client ?: LegacyHttpClient(baseURL, log = ignoreLog)

            val request = HttpRequest(path = "/swagger/v1/swagger.yaml", method = "GET")
            val response = httpClient.execute(request)

            if (response.status != 200) {
                logger.log("Failed to query swaggerUI, status code: ${response.status}")
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
            val endpointsAPI: String = Flags.getStringValue(ENDPOINTS_API) ?: return ActuatorSetupResult.Failure
            val request = HttpRequest("GET")
            val response = LegacyHttpClient(endpointsAPI, log = ignoreLog).execute(request)

            if (response.status != 200) {
                logger.log("Failed to query actuator, status code: ${response.status}")
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

                    if(methods != null && paths != null) {
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

        val configFile get() = getConfigFilePath()

        private fun getConfigFileWithAbsolutePath() = File(configFile).canonicalPath
    }

    private fun getEnvConfig(envName: String?): JSONObjectValue {
        if(envName.isNullOrBlank())
            return JSONObjectValue()

        val configFileName = getConfigFilePath()
        if(!File(configFileName).exists())
            throw ContractException("Environment name $envName was specified but config file does not exist in the project root. Either avoid setting envName, or provide the configuration file with the environment settings.")

        val config = loadSpecmaticConfig(configFileName)

        return config.getEnvironment(envName)
    }

    private fun loadExceptionAsTestError(e: Throwable): Stream<DynamicTest> {
        return sequenceOf(DynamicTest.dynamicTest("Load Error") {
            ResultAssert.assertThat(Result.Failure(exceptionCauseMessage(e))).isSuccess()
        }).asStream()
    }

    @TestFactory
    fun contractTest(): Stream<DynamicTest> {
        val statistics = ContractTestStatistics()
        val name = ObjectName("io.specmatic:type=ContractTestStatistics")

        val mbs = ManagementFactory.getPlatformMBeanServer()

        if(!mbs.isRegistered(name))
            mbs.registerMBean(statistics, name)

        val contractPaths = System.getProperty(CONTRACT_PATHS)
        val givenWorkingDirectory = System.getProperty(WORKING_DIRECTORY)
        val filterName: String? = System.getProperty(FILTER_NAME_PROPERTY) ?: System.getenv(FILTER_NAME_ENVIRONMENT_VARIABLE)
        val filterNotName: String? = System.getProperty(FILTER_NOT_NAME_PROPERTY) ?: System.getenv(FILTER_NOT_NAME_ENVIRONMENT_VARIABLE)
        val overlayFilePath: String? = System.getProperty(OVERLAY_FILE_PATH) ?: System.getenv(OVERLAY_FILE_PATH)
        val overlayContent = if(overlayFilePath.isNullOrBlank()) "" else readFrom(overlayFilePath, "overlay")

        val filterExpression = System.getProperty(FILTER, "")
        openApiCoverageReportInput = OpenApiCoverageReportInput(getConfigFileWithAbsolutePath(), filterExpression = filterExpression)

        specmaticConfig = getSpecmaticConfig()

        val timeoutInMilliseconds = specmaticConfig?.getTestTimeoutInMilliseconds() ?: try {
            getLongValue(SPECMATIC_TEST_TIMEOUT)
        } catch (e: NumberFormatException) {
            throw ContractException("$SPECMATIC_TEST_TIMEOUT should be a value of type long")
        } ?: DEFAULT_TIMEOUT_IN_MILLISECONDS

        val suggestionsData = System.getProperty(INLINE_SUGGESTIONS) ?: ""
        val suggestionsPath = System.getProperty(SUGGESTIONS_PATH) ?: ""

        val workingDirectory = WorkingDirectory(givenWorkingDirectory ?: DEFAULT_WORKING_DIRECTORY)

        val envConfig = getEnvConfig(System.getProperty(ENV_NAME))
        val testConfig = try {
            loadTestConfig(envConfig).withVariablesFromFilePath(System.getProperty(VARIABLES_FILE_NAME))
        } catch (e: Throwable) {
            return loadExceptionAsTestError(e)
        }
        val testScenarios = try {
            val (testScenarios, allEndpoints) = when {
                contractPaths != null -> {
                    val testScenariosAndEndpointsPairList = contractPaths.split(",").filter {
                        File(it).extension in CONTRACT_EXTENSIONS
                    }.map {
                        loadTestScenarios(
                            it,
                            suggestionsPath,
                            suggestionsData,
                            testConfig,
                            specificationPath = it,
                            filterName = filterName,
                            filterNotName = filterNotName,
                            specmaticConfig = specmaticConfig,
                            overlayContent = overlayContent
                        )
                    }
                    val tests: Sequence<ContractTest> = testScenariosAndEndpointsPairList.asSequence().flatMap { it.first }
                    val endpoints: List<Endpoint> = testScenariosAndEndpointsPairList.flatMap { it.second }
                    Pair(tests, endpoints)
                }
                else -> {
                    val configFile = configFile
                    if(File(configFile).exists().not()) exitWithMessage(MISSING_CONFIG_FILE_MESSAGE)

                    createIfDoesNotExist(workingDirectory.path)

                    val contractFilePaths = contractTestPathsFrom(configFile, workingDirectory.path)

                    exitIfAnyDoNotExist("The following specifications do not exist", contractFilePaths.map { it.path })

                    val testScenariosAndEndpointsPairList = contractFilePaths.filter {
                        File(it.path).extension in CONTRACT_EXTENSIONS
                    }.map {
                        loadTestScenarios(
                            it.path,
                            "",
                            "",
                            testConfig,
                            it.provider,
                            it.repository,
                            it.branch,
                            it.specificationPath,
                            specmaticConfig?.getSecurityConfiguration(),
                            filterName,
                            filterNotName,
                            specmaticConfig = specmaticConfig,
                            overlayContent = overlayContent
                        )
                    }

                    val tests: Sequence<ContractTest> = testScenariosAndEndpointsPairList.asSequence().flatMap { it.first }

                    val endpoints: List<Endpoint> = testScenariosAndEndpointsPairList.flatMap { it.second }

                    Pair(tests, endpoints)
                }
            }
            openApiCoverageReportInput.addEndpoints(allEndpoints)

            val filteredTestsBasedOnName = selectTestsToRun(
                testScenarios,
                filterName,
                filterNotName
            ) { it.testDescription() }

            filterUsing(filteredTestsBasedOnName, testFilter)
        } catch (e: ContractException) {
            return loadExceptionAsTestError(e)
        } catch (e: Throwable) {
            return loadExceptionAsTestError(e)
        }

        val testBaseURL = try {
            constructTestBaseURL()
        } catch (e: Throwable) {
            logger.logError(e)
            logger.newLine()
            throw(e)
        }

        return try {
            dynamicTestStream(testScenarios, testBaseURL, timeoutInMilliseconds)
        } catch(e: Throwable) {
            logger.logError(e)
            loadExceptionAsTestError(e)
        }
    }

    private fun dynamicTestStream(
        testScenarios: Sequence<ContractTest>,
        testBaseURL: String,
        timeoutInMilliseconds: Long
    ): Stream<DynamicTest> {
        try {
            if (queryActuator().failed && actuatorFromSwagger(testBaseURL).failed) {
                openApiCoverageReportInput.setEndpointsAPIFlag(false)
                logger.log("EndpointsAPI and SwaggerUI URL missing; cannot calculate actual coverage")
            }
        } catch (exception: Throwable) {
            openApiCoverageReportInput.setEndpointsAPIFlag(false)
            logger.log(exception, "Failed to query actuator with error")
        }

        logger.newLine()

        val log: (LogMessage) -> Unit = { logMessage ->
            logger.log(logMessage)
        }

        val httpClient = HttpClient(testBaseURL, log = log, timeoutInMilliseconds = timeoutInMilliseconds)

        return testScenarios.map { contractTest ->
            DynamicTest.dynamicTest(contractTest.testDescription()) {
                threads.add(Thread.currentThread().name)

                var testResult: Pair<Result, HttpResponse?>? = null

                try {
                    testResult = contractTest.runTest(httpClient)
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
                        val (result, response) = testResult
                        contractTest.testResultRecord(result, response)?.let { testREsultRecord -> openApiCoverageReportInput.addTestReportRecords(testREsultRecord) }
                    }
                }
            }
        }.asStream().onClose { httpClient.close() }
    }

    fun constructTestBaseURL(): String {
        val testBaseURL = System.getProperty(TEST_BASE_URL)
        if (testBaseURL != null) {
            when (val validationResult = validateTestOrStubUri(testBaseURL)) {
                URIValidationResult.Success -> return testBaseURL
                else -> throw TestAbortedException("${validationResult.message} in $TEST_BASE_URL environment variable")
            }
        }

        val hostProperty = System.getProperty(HOST)
            ?: throw TestAbortedException("Please specify $TEST_BASE_URL OR $HOST and $PORT as environment variables")
        val host = if (hostProperty.startsWith("http")) {
            URI(hostProperty).host
        } else {
            hostProperty
        }
        val protocol = System.getProperty(PROTOCOL) ?: "http"
        val port = System.getProperty(PORT)

        if (!isNumeric(port)) {
            throw TestAbortedException("Please specify a number value for $PORT environment variable")
        }

        val urlConstructedFromProtocolHostAndPort = "$protocol://$host:$port"

        return when (validateTestOrStubUri(urlConstructedFromProtocolHostAndPort)) {
            URIValidationResult.Success -> urlConstructedFromProtocolHostAndPort
            else -> throw TestAbortedException("Please specify a valid $PROTOCOL, $HOST and $PORT environment variables")
        }
    }

    private fun isNumeric(port: String?): Boolean {
        return port?.toIntOrNull() != null
    }

    private fun portNotSpecified(parsedURI: URI) = parsedURI.port == -1

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
        specmaticConfig: SpecmaticConfig? = null,
        overlayContent: String = ""
    ): Pair<Sequence<ContractTest>, List<Endpoint>> {
        if(hasOpenApiFileExtension(path) && !isOpenAPI(path))
            return Pair(emptySequence(), emptyList())

        val contractFile = File(path)
        val strictMode = (System.getProperty(STRICT_MODE) ?: System.getenv(STRICT_MODE)) == "true"
        val feature =
            parseContractFileToFeature(
                contractFile.path,
                CommandHook(HookName.test_load_contract),
                sourceProvider,
                sourceRepository,
                sourceRepositoryBranch,
                specificationPath,
                securityConfiguration,
                specmaticConfig = specmaticConfig ?: SpecmaticConfig(),
                overlayContent = overlayContent,
                strictMode = strictMode
            ).copy(testVariables = config.variables, testBaseURLs = config.baseURLs).loadExternalisedExamples()

        feature.validateExamplesOrException()

        val suggestions = when {
            suggestionsPath.isNotEmpty() -> suggestionsFromFile(suggestionsPath)
            suggestionsData.isNotEmpty() -> suggestionsFromCommandLine(suggestionsData)
            else -> emptyList()
        }

        val allEndpoints: List<Endpoint> = feature.scenarios.map { scenario ->
            Endpoint(
                convertPathParameterStyle(scenario.path),
                scenario.method,
                scenario.httpResponsePattern.status,
                scenario.sourceProvider,
                scenario.sourceRepository,
                scenario.sourceRepositoryBranch,
                scenario.specification,
                scenario.serviceType,
                scenario.requestContentType,
                scenario.httpResponsePattern.headersPattern.contentType
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
            testFilter
        )

        val filteredScenarioPaths = filteredScenarios.map { convertPathParameterStyle(it.path) }

        val filteredOutScenarios = feature.scenarios.filter { scenario -> scenario !in filteredScenarios }

        val filteredOutScenarioPaths = filteredOutScenarios.map { convertPathParameterStyle(it.path) }

        val excludedEndpoints = filteredOutScenarioPaths.filter { it !in filteredScenarioPaths }

        openApiCoverageReportInput.addExcludedAPIs(excludedEndpoints);
        val tests: Sequence<ContractTest> = feature
            .copy(scenarios = filteredScenarios.toList())
            .also {
                if (it.scenarios.isEmpty())
                    logger.log("All scenarios were filtered out.")
                else if (it.scenarios.size < feature.scenarios.size) {
                    logger.debug("Selected scenarios:")
                    it.scenarios.forEach { scenario -> logger.debug(scenario.testDescription().prependIndent("  ")) }
                }
            }
            .generateContractTests(suggestions, originalScenarios = feature.scenarios)

        return Pair(tests, allEndpoints)
    }

    private fun getSpecmaticConfig(): SpecmaticConfig? {
        return try {
            loadSpecmaticConfig(configFile)
        }
        catch (e: ContractException) {
            logger.log(exceptionCauseMessage(e))
            null
        }
        catch (e: Throwable) {
            exitWithMessage(exceptionCauseMessage(e))
        }
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
            )
        }
    }

    private fun readFrom(path: String, fileTag: String = ""): String {
        if(File(path).exists().not()) {
            throw ContractException("The $fileTag file $path does not exist. Please provide a valid $fileTag file")
        }
        if(File(path).extension != YAML && File(path).extension != JSON && File(path).extension != YML) {
            throw ContractException("The $fileTag file does not have a valid extension.")
        }
        return File(path).readText()
    }
}

private fun columnsFromExamples(exampleData: JSONArrayValue): List<String> {
    val firstRow = exampleData.list[0]
    if (firstRow !is JSONObjectValue)
        throw ContractException("Each value in the list of suggestions must be a json object containing column name as key and sample value as the value")

    return firstRow.jsonObject.keys.toList()
}

private fun asJSONObjectValue(value: Value): Map<String, Value> {
    val errorMessage = "Each value in the list of suggestions must be a json object containing column name as key and sample value as the value"
    if(value !is JSONObjectValue)
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

    val filteredByNotName: Sequence<T> = if(!filterNotName.isNullOrBlank()) {
        val filterNotNames = filterNotName.split(",").map { it.trim() }

        filteredByName.filterNot { test ->
            filterNotNames.any { getTestDescription(test).contains(it) }
        }
    } else
        filteredByName

    return filteredByNotName
}
