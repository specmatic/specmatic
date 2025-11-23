package io.specmatic.test

import io.qameta.allure.Allure
import io.qameta.allure.AllureLifecycle
import io.qameta.allure.model.Status as AllureTestStatus
import io.qameta.allure.util.ResultsUtils.createLabel
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.ignoreLog
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.Flags
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.test.SpecmaticJUnitSupport.ActuatorSetupResult
import io.specmatic.test.SpecmaticJUnitSupport.Companion.partialSuccesses
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import org.junit.jupiter.api.DynamicTest
import org.opentest4j.TestAbortedException
import java.time.Instant
import java.util.*
import java.util.stream.Stream
import kotlin.streams.asStream
import io.qameta.allure.model.TestResult as AllureTestResult


class SpecmaticDynamicTestStream(
    var openApiCoverageReportInput: OpenApiCoverageReportInput,
    private val httpInteractionsLog: HttpInteractionsLog,
    private val threads: Vector<String>,
    private var startTime: Instant?
) {
    companion object {
        private const val ENDPOINTS_API = "endpointsAPI"
        private const val SWAGGER_UI_BASEURL = "swaggerUIBaseURL"
    }

    fun dynamicTestStream(
        testScenarios: Sequence<Pair<ContractTest, String>>,
        actuatorBaseURL: String,
        timeoutInMilliseconds: Long,
    ): Stream<DynamicTest> {
        try {
            if (queryActuator().failed && actuatorFromSwagger(actuatorBaseURL).failed) {
                openApiCoverageReportInput.setEndpointsAPIFlag(false)
                logger.log("EndpointsAPI and SwaggerUI URL missing; cannot calculate actual coverage")
            }
        } catch (exception: Throwable) {
            openApiCoverageReportInput.setEndpointsAPIFlag(false)
            logger.log(exception, "Failed to query actuator with error")
        }

        logger.newLine()

        val allureLifecycle = Allure.getLifecycle()

        startTime = Instant.now()
        return testScenarios.map { (contractTest, baseURL) ->
            val testId = UUID.randomUUID().toString()
            DynamicTest.dynamicTest(contractTest.testDescription()) {
                threads.add(Thread.currentThread().name)

                allureLifecycle.scheduleTestCase(
                    AllureTestResult()
                        .setUuid(testId)
                        .setName(contractTest.testDescription())
                )
                allureLifecycle.startTestCase(testId)
                addAllureMetadata(allureLifecycle, testId, contractTest)

                var testResult: Pair<Result, HttpResponse?>? = null
                val httpLogMessage = HttpLogMessage(targetServer = baseURL)

                try {
                    val log: (LogMessage) -> Unit = { logMessage ->
                        logger.log(logMessage)
                    }

                    HttpClient(
                        baseURL,
                        log = log,
                        timeoutInMilliseconds = timeoutInMilliseconds,
                        httpInteractionsLog = httpInteractionsLog,
                        httpLogMessage = httpLogMessage
                    ).use { client ->
                        testResult = contractTest.runTest(client)
                    }
                    testResult?.let { (result, _) ->
                        if (result is Result.Success && result.isPartialSuccess()) {
                            partialSuccesses.add(result)
                        }

                        attachContractDetails(contractTest, httpLogMessage, result)
                        allureLifecycle.updateTestCase(testId) { testResult ->
                            val status = if(result.isSuccess()) AllureTestStatus.PASSED else AllureTestStatus.FAILED
                            testResult.setStatus(status)
                        }

                        if(result.shouldBeIgnored()) {
                            val message =
                                "Test FAILED, ignoring since the scenario is tagged @WIP${System.lineSeparator()}${
                                    result.toReport().toText().prependIndent("  ")
                                }"
                            throw TestAbortedException(message)
                        }

                        ResultAssert.assertThat(result).isSuccess()
                    }
                } catch (e: Throwable) {
                    throw e
                } finally {
                    testResult?.let { (result, response) ->
                        contractTest.testResultRecord(result, response)?.let { testResultRecord ->
                            openApiCoverageReportInput.addTestReportRecords(testResultRecord)
                        }
                    }
                    allureLifecycle.stopTestCase(testId)
                    allureLifecycle.writeTestCase(testId)
                }
            }
        }.asStream()
    }

    private fun addAllureMetadata(
        allureLifecycle: AllureLifecycle,
        testId: String,
        contract: ContractTest
    ) {
        allureLifecycle.updateTestCase(testId) { testResult ->
            testResult.setDescription(contract.testDescription())

            if(contract !is ScenarioAsTest) return@updateTestCase

            val spec = contract.scenario.specification ?: "N/A"
            val method = contract.scenario.method
            val path = contract.scenario.path
            val status = contract.scenario.status.toString()
            val contentType = contract.scenario.requestContentType ?: "N/A"

            testResult.labels.addAll(
                listOf(
                    createLabel("epic", spec),
                    createLabel("spec", spec),
                    createLabel("method", method),
                    createLabel("path", path),
                    createLabel("statusCode", status),
                    createLabel("contentType", contentType)
                )
            )

            val story = buildString {
                append("spec: $spec, ")
                append("method: $method, ")
                append("path: $path, ")
                append("statusCode: $status, ")
                append("contentType: $contentType")
            }

            testResult.labels.add(createLabel("feature", spec))
            testResult.labels.add(createLabel("story", story))

            testResult.labels.add(createLabel("parentSuite", "Specmatic Contract Tests"));
            testResult.labels.add(createLabel("suite", spec))
            testResult.labels.add(createLabel("subSuite", story))
            testResult.labels.add(createLabel("testClass", spec))
            testResult.labels.add(createLabel("package", "specmatic.contracts"))
        }
    }

    private fun attachContractDetails(
        test: ContractTest,
        httpLogMessage: HttpLogMessage,
        result: Result
    ) {
        if(test !is ScenarioAsTest) return

        val request = httpLogMessage.request
        Allure.addAttachment(
            "Request",
            request.headers["Content-Type"].orEmpty(),
            request.toLogString()
        )

        val response = httpLogMessage.response
        if (response != null) {
            Allure.addAttachment(
                "Response",
                response.headers["Content-Type"].orEmpty(),
                response.toLogString()
            )
        }

        if(result.isSuccess().not()) {
            Allure.addAttachment(
                "Failure Details",
                result.reportString()
            )
        }

        // Attach contract specification snippet
//        if (contract.getContractSnippet() != null) {
//            Allure.addAttachment(
//                "Contract Specification",
//                "text/plain",
//                contract.getContractSnippet(),
//                ".txt"
//            )
//        }
    }


    private fun queryActuator(): ActuatorSetupResult {
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


    internal fun actuatorFromSwagger(testBaseURL: String, client: TestExecutor? = null): ActuatorSetupResult {
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
}