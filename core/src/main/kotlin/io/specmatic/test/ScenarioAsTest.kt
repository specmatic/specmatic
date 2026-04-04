package io.specmatic.test

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.LoggingScope
import io.specmatic.core.log.TestFlowDiagnostics
import io.specmatic.core.log.logger
import io.specmatic.core.log.newLoggingEnabled
import io.specmatic.core.matchers.MatcherEngine
import io.specmatic.core.pattern.ContractException
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
        val (result, request, response) = executionResult
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
            )
        )
    }

    override fun testDescription(): String {
        return scenario.testDescription()
    }

    override fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): ContractTestExecutionResult {
        val log: (LogMessage) -> Unit = { logMessage ->
            emitScenarioLog(logMessage)
        }

        val httpClient = LegacyHttpClient(testBaseURL, log = log, timeoutInMilliseconds = timeoutInMilliseconds)

        return runTest(httpClient)
    }

    override fun runTest(testExecutor: TestExecutor): ContractTestExecutionResult {
        startTime = Instant.now()
        val newExecutor = if (testExecutor is HttpClient) {
            val log: (LogMessage) -> Unit = { logMessage ->
                emitScenarioLog(logMessage)
            }

            testExecutor.withLogger(log)
        } else {
            testExecutor
        }

        val executionResult = executeTestAndReturnResultAndResponse(scenario, newExecutor, flagsBased)
        endTime = Instant.now()
        return executionResult.copy(result = executionResult.result.updateScenario(scenario))
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
            val beforeFixtureExecutionResult = fixtureExecutionResult(BEFORE_FIXTURE_DISCRIMINATOR_KEY)
            if (beforeFixtureExecutionResult.isSuccess().not()) {
                val result = beforeFixtureExecutionResult.updateScenario(testScenario)
                logFailure(
                    summary = "Scenario setup failed before the request could be sent",
                    remediation = "Fix the before-fixture data or setup hooks used by this scenario.",
                    result = result,
                )
                return ContractTestExecutionResult(result = result)
            }
            val request = testScenario.generateHttpRequest(flagsBased).let {
                workflow.updateRequest(it, originalScenario).adjustPayloadForContentType()
            }.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, testScenario.status.toString())

            testExecutor.setServerState(testScenario.serverState)
            testExecutor.preExecuteScenario(testScenario, request)
            val response = testExecutor.execute(request)

            val responseBodyFromExample = testScenario.responseBodyFromExample()

            if(responseBodyFromExample != null) {
                matcherEngine?.let {
                    val matchesResult = it.matchResponseValue(
                        responseBodyFromExample,
                        response.body,
                        testScenario.resolver
                    )
                    if(matchesResult is Result.Failure) {
                        logFailure(
                            summary = "Response body did not match the example linked to this scenario",
                            remediation = "Update the response body example or fix the service response so they agree.",
                            result = matchesResult,
                            request = request,
                            response = response,
                        )
                        return ContractTestExecutionResult(
                            result = matchesResult.withBindings(testScenario.bindings, response),
                            request = request,
                            response = response
                        )
                    }
                }
            }

            //TODO: Review - Do we need workflow anymore
            workflow.extractDataFrom(response, originalScenario)
            val validatorResult = validators.asSequence().mapNotNull { it.validate(scenario, response) }.firstOrNull()

            if (validatorResult is Result.Failure) {
                logFailure(
                    summary = "A response validator rejected the scenario response",
                    remediation = "Inspect the validator rules and adjust either the validator or the response payload.",
                    result = validatorResult,
                    request = request,
                    response = response,
                )
                return ContractTestExecutionResult(
                    result = validatorResult.withBindings(testScenario.bindings, response),
                    request = request,
                    response = response
                )
            }

            val testResult = validatorResult ?: testResult(request, response, testScenario, flagsBased)
            val responseHandler = response.getResponseHandlerIfExists()
            if (testResult is Result.Failure && responseHandler == null) {
                logFailure(
                    summary = "The service response did not match the contract",
                    remediation = "Compare the failing breadcrumb and rule violations above with the actual response from the service.",
                    result = testResult,
                    request = request,
                    response = response,
                )
                return ContractTestExecutionResult(
                    result = testResult.withBindings(testScenario.bindings, response),
                    request = request,
                    response = response
                )
            }

            val responseToCheckAndStore = when (responseHandler) {
                null -> response
                else -> {
                    val client = if (testExecutor is LegacyHttpClient) testExecutor.copy() else testExecutor
                    when (val handlerResult = responseHandler.handle(request, response, scenario, client)) {
                        is ResponseHandlingResult.Continue -> handlerResult.response
                        is ResponseHandlingResult.Stop -> {
                            val bindingResponse = handlerResult.response ?: response
                            logFailure(
                                summary = "A response handler stopped this scenario",
                                remediation = "Inspect the response handler logic and the captured response before rerunning the test.",
                                result = handlerResult.result,
                                request = request,
                                response = bindingResponse,
                            )
                            return ContractTestExecutionResult(
                                result = handlerResult.result.withBindings(testScenario.bindings, bindingResponse),
                                request = request,
                                response = bindingResponse
                            )
                        }
                    }
                }
            }

            val result = validators.asSequence().mapNotNull {
                it.postValidate(testScenario, originalScenario, request, responseToCheckAndStore)
            }.firstOrNull() ?: Result.Success()

            testScenario.exampleRow?.let { ExampleProcessor.store(it, request, responseToCheckAndStore) }

            if(result !is Result.Failure) {
                val afterFixtureExecutionResult = fixtureExecutionResult(AFTER_FIXTURE_DISCRIMINATOR_KEY)
                if (afterFixtureExecutionResult.isSuccess().not()) {
                    logFailure(
                        summary = "Scenario cleanup failed after the response was validated",
                        remediation = "Fix the after-fixture logic so the scenario can clean up successfully.",
                        result = afterFixtureExecutionResult,
                        request = request,
                        response = response,
                    )
                    return ContractTestExecutionResult(
                        result = afterFixtureExecutionResult.withBindings(testScenario.bindings, response),
                        request = request,
                        response = response
                    )
                }
            }
            return ContractTestExecutionResult(
                result = result.withBindings(testScenario.bindings, response),
                request = request,
                response = response
            )
        } catch (exception: Throwable) {
            logUnexpectedException(exception)
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

    private fun fixtureExecutionResult(fixtureDiscriminatorKey: String): Result {
        val row = scenario.exampleRow ?: return Result.Success()
        val scenarioStub = row.scenarioStub ?: return Result.Success()
        val id = scenarioStub.id.orEmpty()
        val fixtures = when (fixtureDiscriminatorKey) {
            BEFORE_FIXTURE_DISCRIMINATOR_KEY -> scenarioStub.beforeFixtures
            else -> scenarioStub.afterFixtures
        }
        return ServiceLoader.load(OpenAPIFixtureExecutor::class.java)
            .firstOrNull()?.execute(id, fixtures, fixtureDiscriminatorKey) ?: Result.Success()
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

    private fun logFailure(
        summary: String,
        remediation: String,
        result: Result,
        request: HttpRequest? = null,
        response: HttpResponse? = null,
    ) {
        val failure = result as? Result.Failure ?: return
        TestFlowDiagnostics.scenarioFailed(
            name = scenario.testDescription(),
            summary = summary,
            remediation = remediation,
            contractPath = scenario.specification,
            failure = failure.updateScenario(scenario),
            details = buildString {
                request?.let { appendLine("Request: ${it.method} ${it.path}") }
                response?.let { append("Response status: ${it.status}") }
            }.trim().ifBlank { null },
        )
    }

    private fun logUnexpectedException(exception: Throwable) {
        when (exception) {
            is ContractException -> TestFlowDiagnostics.scenarioFailed(
                name = scenario.testDescription(),
                summary = exception.summary(),
                remediation = "Fix the scenario setup, contract, or request generation issue described above and rerun the test.",
                contractPath = scenario.specification,
                throwable = exception,
            )
            else -> TestFlowDiagnostics.internalError(
                summary = "Unexpected error while executing scenario ${scenario.testDescription()}",
                throwable = exception,
                remediation = "Inspect the exception and scenario data, then rerun once the issue is fixed.",
                context = mapOf(
                    "scenario" to scenario.testDescription(),
                    "contract" to scenario.specification.orEmpty(),
                ).filterValues { it.isNotBlank() },
            )
        }
    }

    private fun emitScenarioLog(logMessage: LogMessage) {
        val annotatedLogMessage = logMessage.withComment(this.annotations)

        if (newLoggingEnabled() && LoggingScope.executionContext().mode == io.specmatic.core.log.ExecutionMode.TEST) {
            when (annotatedLogMessage) {
                is HttpLogMessage -> TestFlowDiagnostics.httpInteraction(annotatedLogMessage)
                else -> TestFlowDiagnostics.scenarioExecutionDetail(
                    name = scenario.testDescription(),
                    summary = "Scenario execution detail",
                    details = annotatedLogMessage.toLogString(),
                    contractPath = scenario.specification,
                )
            }
            return
        }

        logger.log(annotatedLogMessage)
    }
}

private fun LogMessage.withComment(comment: String?): LogMessage {
    return if (this is HttpLogMessage) {
        this.copy(comment = comment)
    } else {
        this
    }
}
