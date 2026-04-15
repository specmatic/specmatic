package io.specmatic.test

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.logger
import io.specmatic.core.matchers.MatcherEngine
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.Value
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import io.specmatic.test.handlers.ResponseHandler
import io.specmatic.test.handlers.ResponseHandlerRegistry
import io.specmatic.test.handlers.ResponseHandlingResult
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.specmatic.core.log.consoleLog
import io.specmatic.reporter.ctrf.model.CtrfFixtureExecutionRecord
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.ServiceLoader
import javax.net.ssl.SSLException
import kotlin.jvm.java

private const val BEFORE_FIXTURE_DISCRIMINATOR_KEY = "before"
private const val AFTER_FIXTURE_DISCRIMINATOR_KEY = "after"

data class ScenarioAsTest(
    val scenario: Scenario,
    private val feature: Feature,
    private val flagsBased: FlagsBased,
    private val sourceProvider: String? = null,
    private val sourceRepository: String? = null,
    private val sourceRepositoryBranch: String? = null,
    private val specification: String? = null,
    override val protocol: SpecmaticProtocol,
    override val specType: SpecType,
    private val annotations: String? = null,
    private val validators: List<ResponseValidator> = emptyList(),
    private val originalScenario: Scenario,
    private val workflow: Workflow = Workflow(),
    private val responseHandlerRegistry: ResponseHandlerRegistry = ResponseHandlerRegistry(feature, originalScenario),
) : ContractTest {
    companion object {
        private var id: Value? = null
    }

    private var startTime: Instant? = null
    private var endTime: Instant? = null
    private val matcherEngine: MatcherEngine? by lazy { MatcherEngine.load() }

    override fun toScenarioMetadata() = scenario.toScenarioMetadata()

    override fun testResultRecord(executionResult: ContractTestExecutionResult): TestResultRecord {
        val (result, request, response, ctrfFixtureExecutionResult) = executionResult
        val resultStatus = result.testResult()
        val path = convertPathParameterStyle(scenario.path)

        return TestResultRecord(
            path = path,
            method = scenario.method,
            requestContentType = scenario.requestContentType,
            responseStatus = scenario.status,
            request = request,
            response = response,
            result = resultStatus,
            sourceProvider = sourceProvider,
            repository = sourceRepository,
            branch = sourceRepositoryBranch,
            specification = specification,
            specType = specType,
            actualResponseStatus = response?.status ?: 0,
            scenarioResult = result,
            soapAction = scenario.httpRequestPattern.getSOAPAction().takeIf { scenario.isGherkinScenario },
            isGherkin = scenario.isGherkinScenario,
            requestTime = startTime,
            responseTime = Instant.now(),
            operations = setOf(
                openAPIOperationFrom(scenario, path)
            ),
            ctrfFixtureExecutionResult = ctrfFixtureExecutionResult
        )
    }

    override fun testDescription(): String {
        return scenario.testDescription()
    }

    override fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): ContractTestExecutionResult {
        val log: (LogMessage) -> Unit = { logMessage ->
            logger.log(logMessage.withComment(this.annotations))
        }

        val httpClient = LegacyHttpClient(testBaseURL, log = log, timeoutInMilliseconds = timeoutInMilliseconds)

        return runTest(httpClient)
    }

    override fun runTest(testExecutor: TestExecutor): ContractTestExecutionResult {
        startTime = Instant.now()
        val newExecutor = if (testExecutor is HttpClient) {
            val log: (LogMessage) -> Unit = { logMessage ->
                logger.log(logMessage.withComment(this.annotations))
            }

            testExecutor.withLogger(log)
        } else {
            testExecutor
        }

        val executionResult = executeTestAndReturnResultAndResponse(scenario, newExecutor, flagsBased)
        consoleLog("execution result --> $executionResult")
        endTime = Instant.now()
        return executionResult.copy(result = executionResult.result.updateScenario(scenario), ctrfFixtureExecutionResult = executionResult.ctrfFixtureExecutionResult)
    }

    override fun plusValidator(validator: ResponseValidator): ScenarioAsTest {
        return this.copy(
            validators = this.validators.plus(validator)
        )
    }

    private fun executeTestAndReturnResultAndResponse(
        testScenario: Scenario,
        testExecutor: TestExecutor,
        flagsBased: FlagsBased
    ): ContractTestExecutionResult {
        try {
            val beforeFixtureExecutionResultRecord = fixtureExecutionResult(BEFORE_FIXTURE_DISCRIMINATOR_KEY)
            val beforeFixtureExecutionResult = beforeFixtureExecutionResultRecord.result
            consoleLog("only before --> ${beforeFixtureExecutionResultRecord.fixtureExecutionResults}")
            if (beforeFixtureExecutionResult.isSuccess().not()) {
                return ContractTestExecutionResult(result = beforeFixtureExecutionResult.updateScenario(testScenario), ctrfFixtureExecutionResult = CtrfFixtureExecutionRecord(beforeFixtureExecutionResultRecord.fixtureExecutionResults))
            }
            val request = testScenario.generateHttpRequest(flagsBased).let {
                workflow.updateRequest(it, originalScenario).adjustPayloadForContentType()
            }.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, testScenario.status.toString())

            testExecutor.setServerState(testScenario.serverState)
            testExecutor.preExecuteScenario(testScenario, request)
            val response = testExecutor.execute(request)

            val responseBodyFromExample = testScenario.responseBodyFromExample()
            consoleLog("only before 1 --> ${beforeFixtureExecutionResultRecord.fixtureExecutionResults}")

            if(responseBodyFromExample != null) {
                matcherEngine?.let {
                    val matchesResult = it.matchResponseValue(
                        responseBodyFromExample,
                        response.body,
                        testScenario.resolver
                    )
                    if(matchesResult is Result.Failure) {
                        return ContractTestExecutionResult(
                            result = matchesResult.withBindings(testScenario.bindings, response),
                            request = request,
                            response = response,
                            ctrfFixtureExecutionResult = CtrfFixtureExecutionRecord(beforeFixtureExecutionResultRecord.fixtureExecutionResults)
                        )
                    }
                }
            }

            //TODO: Review - Do we need workflow anymore
            workflow.extractDataFrom(response, originalScenario)
            val validatorResult = validators.asSequence().mapNotNull { it.validate(scenario, response) }.firstOrNull()
            consoleLog("only before 2--> ${beforeFixtureExecutionResultRecord.fixtureExecutionResults}")

            if (validatorResult is Result.Failure) {
                return ContractTestExecutionResult(
                    result = validatorResult.withBindings(testScenario.bindings, response),
                    request = request,
                    response = response,
                    ctrfFixtureExecutionResult = CtrfFixtureExecutionRecord(beforeFixtureExecutionResultRecord.fixtureExecutionResults)

                )
            }

            val testResult = validatorResult ?: testResult(request, response, testScenario, flagsBased)
            val responseHandler = response.getResponseHandlerIfExists()
            if (testResult is Result.Failure && responseHandler == null) {
                return ContractTestExecutionResult(
                    result = testResult.withBindings(testScenario.bindings, response),
                    request = request,
                    response = response,
                    ctrfFixtureExecutionResult = CtrfFixtureExecutionRecord(beforeFixtureExecutionResultRecord.fixtureExecutionResults)

                )
            }
            consoleLog("only before -->3 ${beforeFixtureExecutionResultRecord.fixtureExecutionResults}")

            val responseToCheckAndStore = when (responseHandler) {
                null -> response
                else -> {
                    val client = if (testExecutor is LegacyHttpClient) testExecutor.copy() else testExecutor
                    when (val handlerResult = responseHandler.handle(request, response, scenario, client)) {
                        is ResponseHandlingResult.Continue -> handlerResult.response
                        is ResponseHandlingResult.Stop -> {
                            val bindingResponse = handlerResult.response ?: response
                            return ContractTestExecutionResult(
                                result = handlerResult.result.withBindings(testScenario.bindings, bindingResponse),
                                request = request,
                                response = bindingResponse,
                                ctrfFixtureExecutionResult = CtrfFixtureExecutionRecord(beforeFixtureExecutionResultRecord.fixtureExecutionResults)

                            )
                        }
                    }
                }
            }
            consoleLog("only before --> 5${beforeFixtureExecutionResultRecord.fixtureExecutionResults}")


            val result = validators.asSequence().mapNotNull {
                it.postValidate(testScenario, originalScenario, request, responseToCheckAndStore)
            }.firstOrNull() ?: Result.Success()

            testScenario.exampleRow?.let { ExampleProcessor.store(it, request, responseToCheckAndStore) }
            var afterFixtureExecutionResultRecord: FixtureExecutionResultRecords? = null
            if(result !is Result.Failure) {

                afterFixtureExecutionResultRecord = fixtureExecutionResult(AFTER_FIXTURE_DISCRIMINATOR_KEY)
                val afterFixtureExecutionResult = afterFixtureExecutionResultRecord.result
                if (afterFixtureExecutionResult.isSuccess().not()) {
                    return ContractTestExecutionResult(
                        result = afterFixtureExecutionResult.withBindings(testScenario.bindings, response),
                        request = request,
                        response = response,
                        ctrfFixtureExecutionResult = CtrfFixtureExecutionRecord(beforeFixtureExecutionResultRecord.fixtureExecutionResults , afterFixtureExecutionResultRecord?.fixtureExecutionResults)
                    )
                }
            }

            consoleLog("before --> ${beforeFixtureExecutionResultRecord.fixtureExecutionResults}, after --> ${afterFixtureExecutionResultRecord?.fixtureExecutionResults}")
            return ContractTestExecutionResult(
                result = result.withBindings(testScenario.bindings, response),
                request = request,
                response = response,
                ctrfFixtureExecutionResult = CtrfFixtureExecutionRecord(beforeFixtureExecutionResultRecord.fixtureExecutionResults , afterFixtureExecutionResultRecord?.fixtureExecutionResults)
            )
        } catch (exception: Throwable) {
            return ContractTestExecutionResult(
                result = Result.Failure(
                    exceptionCauseMessage(exception),
                    failureReason = exception.connectivityFailureReason()
                ).updateScenario(testScenario)
            )
        }
    }

    private fun Throwable.connectivityFailureReason(): FailureReason? {
        return if (isConnectivityFailure()) FailureReason.ConnectivityFailure else null
    }

    private fun Throwable.isConnectivityFailure(): Boolean {
        return generateSequence(this) { it.cause }.any {
            it is ConnectException ||
                it is SocketTimeoutException ||
                it is HttpRequestTimeoutException ||
                it is UnknownHostException ||
                it is NoRouteToHostException ||
                it is SSLException
        }
    }

    private fun fixtureExecutionResult(fixtureDiscriminatorKey: String): FixtureExecutionResultRecords {
        val row = scenario.exampleRow ?: return FixtureExecutionResultRecords(
            fixtureExecutionResults = emptyList(),
            result = Result.Success()
        )
        val scenarioStub = row.scenarioStub ?: return FixtureExecutionResultRecords(
            fixtureExecutionResults = emptyList(),
            result = Result.Success()
        )
        val id = scenarioStub.id.orEmpty()
        val fixtures = when (fixtureDiscriminatorKey) {
            BEFORE_FIXTURE_DISCRIMINATOR_KEY -> scenarioStub.beforeFixtures
            else -> scenarioStub.afterFixtures
        }

        val res = ServiceLoader.load(OpenAPIFixtureExecutor::class.java)
            .firstOrNull()?.execute(id, fixtures, fixtureDiscriminatorKey) ?: FixtureExecutionResultRecords(
            fixtureExecutionResults = emptyList(),
            result = Result.Success()
        )
        consoleLog("executed $fixtureDiscriminatorKey Fixture --> $res")
        return res
    }

    private fun testResult(
        request: HttpRequest,
        response: HttpResponse,
        testScenario: Scenario,
        flagsBased: FlagsBased
    ): Result {
        val result =
            testScenario.matchesResponse(
                request,
                response,
                SpecificationAndResponseMismatch,
                flagsBased.unexpectedKeyCheck ?: ValidateUnexpectedKeys,
            )

        if (result is Result.Success && result.isPartialSuccess()) {
            logger.log("    PARTIAL SUCCESS: ${result.partialSuccessMessage}")
            logger.newLine()
        }

        return result
    }

    private fun HttpResponse.getResponseHandlerIfExists(): ResponseHandler? {
        if (scenario.isNegative) return null
        return responseHandlerRegistry.getHandlerFor(this, scenario)
    }
}

private fun LogMessage.withComment(comment: String?): LogMessage {
    return if (this is HttpLogMessage) {
        this.copy(comment = comment)
    } else {
        this
    }
}
