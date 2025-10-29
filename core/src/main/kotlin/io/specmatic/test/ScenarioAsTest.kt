package io.specmatic.test

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.Value
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.test.handlers.ResponseHandler
import io.specmatic.test.handlers.ResponseHandlerRegistry
import io.specmatic.test.handlers.ResponseHandlingResult
import java.time.Duration
import java.time.Instant

data class ScenarioAsTest(
    val scenario: Scenario,
    private val feature: Feature,
    private val flagsBased: FlagsBased,
    private val sourceProvider: String? = null,
    private val sourceRepository: String? = null,
    private val sourceRepositoryBranch: String? = null,
    private val specification: String? = null,
    private val serviceType: String? = null,
    private val annotations: String? = null,
    private val validators: List<ResponseValidator> = emptyList(),
    private val originalScenario: Scenario,
    private val workflow: Workflow = Workflow(),
    private val responseHandlerRegistry: ResponseHandlerRegistry = ResponseHandlerRegistry(feature, originalScenario)
) : ContractTest {

    companion object {
        private var id: Value? = null
    }

    private var startTime: Instant? = null
    private var endTime: Instant? = null

    override fun toScenarioMetadata() = scenario.toScenarioMetadata()

    override fun testResultRecord(result: Result, response: HttpResponse?): TestResultRecord {
        val resultStatus = result.testResult()

        return TestResultRecord(
            convertPathParameterStyle(scenario.path),
            method = scenario.method,
            requestContentType = scenario.requestContentType,
            responseStatus = scenario.status,
            result = resultStatus,
            sourceProvider = sourceProvider,
            sourceRepository = sourceRepository,
            sourceRepositoryBranch = sourceRepositoryBranch,
            specification = specification,
            serviceType = serviceType,
            actualResponseStatus = response?.status ?: 0,
            scenarioResult = result,
            soapAction = scenario.httpRequestPattern.getSOAPAction().takeIf { scenario.isGherkinScenario },
            isGherkin = scenario.isGherkinScenario,
            duration = Duration.between(startTime, Instant.now()).toMillis()
        )
    }

    override fun testDescription(): String {
        return scenario.testDescription()
    }

    override fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): Pair<Result, HttpResponse?> {
        val log: (LogMessage) -> Unit = { logMessage ->
            logger.log(logMessage.withComment(this.annotations))
        }

        val httpClient = LegacyHttpClient(testBaseURL, log = log, timeoutInMilliseconds = timeoutInMilliseconds)

        return runTest(httpClient)
    }

    override fun runTest(testExecutor: TestExecutor): Pair<Result, HttpResponse?> {
        startTime = Instant.now()
        val newExecutor = if (testExecutor is HttpClient) {
            val log: (LogMessage) -> Unit = { logMessage ->
                logger.log(logMessage.withComment(this.annotations))
            }

            testExecutor.withLogger(log)
        } else {
            testExecutor
        }

        val (result, response) = executeTestAndReturnResultAndResponse(scenario, newExecutor, flagsBased)
        endTime = Instant.now()
        return Pair(result.updateScenario(scenario), response)
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
    ): Pair<Result, HttpResponse?> {
        try {
            val request = testScenario.generateHttpRequest(flagsBased).let {
                workflow.updateRequest(it, originalScenario)
            }.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, testScenario.status.toString())

            testExecutor.setServerState(testScenario.serverState)
            testExecutor.preExecuteScenario(testScenario, request)
            val response = testExecutor.execute(request)

            //TODO: Review - Do we need workflow anymore
            workflow.extractDataFrom(response, originalScenario)
            val validatorResult = validators.asSequence().map { it.validate(scenario, response) }.filterNotNull().firstOrNull()
            if (validatorResult is Result.Failure) {
                return Pair(validatorResult.withBindings(testScenario.bindings, response), response)
            }

            val testResult = validatorResult ?: testResult(request, response, testScenario, flagsBased)
            val responseHandler = response.getResponseHandlerIfExists()
            if (testResult is Result.Failure && responseHandler == null) {
                return Pair(testResult.withBindings(testScenario.bindings, response), response)
            }

            val responseToCheckAndStore = when (responseHandler) {
                null -> response
                else -> {
                    val client = if (testExecutor is LegacyHttpClient) testExecutor.copy() else testExecutor
                    when (val handlerResult = responseHandler.handle(request, response, scenario, client)) {
                        is ResponseHandlingResult.Continue -> handlerResult.response
                        is ResponseHandlingResult.Stop -> {
                            val bindingResponse = handlerResult.response ?: response
                            return Pair(handlerResult.result.withBindings(testScenario.bindings, bindingResponse), bindingResponse)
                        }
                    }
                }
            }

            val result = validators.asSequence().mapNotNull {
                it.postValidate(testScenario, originalScenario, request, responseToCheckAndStore)
            }.firstOrNull() ?: Result.Success()

            testScenario.exampleRow?.let { ExampleProcessor.store(it, request, responseToCheckAndStore) }
            return Pair(result.withBindings(testScenario.bindings, response), response)
        } catch (exception: Throwable) {
            return Pair(
                Result.Failure(exceptionCauseMessage(exception))
                    .also { failure -> failure.updateScenario(testScenario) }, null
            )
        }
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
                ContractAndResponseMismatch,
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
