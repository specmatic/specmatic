package io.specmatic.test

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.Value
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import io.specmatic.test.handlers.ResponseHandler
import io.specmatic.test.handlers.ResponseHandlerRegistry
import io.specmatic.test.handlers.ResponseHandlingResult
import io.specmatic.test.matchers.MatcherExecutor
import java.time.Instant
import java.util.ServiceLoader
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
                return ContractTestExecutionResult(result = beforeFixtureExecutionResult)
            }
            val request = testScenario.generateHttpRequest(flagsBased).let {
                workflow.updateRequest(it, originalScenario).adjustPayloadForContentType()
            }.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, testScenario.status.toString())

            testExecutor.setServerState(testScenario.serverState)
            testExecutor.preExecuteScenario(testScenario, request)
            val response = testExecutor.execute(request)

            val responseBodyFromExample = testScenario.exampleRow?.scenarioStub?.response?.body
            val matcherExecutor = ServiceLoader.load(MatcherExecutor::class.java).firstOrNull()
            if(responseBodyFromExample != null && matcherExecutor != null) {
                val matchesResult = matcherExecutor.matchesResult(
                    responseBodyFromExample,
                    response.body,
                    testScenario.resolver
                )
                if(matchesResult is Result.Failure) {
                    return ContractTestExecutionResult(
                        result = matchesResult.withBindings(testScenario.bindings, response),
                        request = request,
                        response = response
                    )
                }
            }

            //TODO: Review - Do we need workflow anymore
            workflow.extractDataFrom(response, originalScenario)
            val validatorResult = validators.asSequence().mapNotNull { it.validate(scenario, response) }.firstOrNull()

            if(validatorResult !is Result.Failure) {
                val afterFixtureExecutionResult = fixtureExecutionResult(AFTER_FIXTURE_DISCRIMINATOR_KEY)
                if (afterFixtureExecutionResult.isSuccess().not()) {
                    return ContractTestExecutionResult(
                        result = afterFixtureExecutionResult,
                        request = request,
                        response = response
                    )
                }
            }

            if (validatorResult is Result.Failure) {
                return ContractTestExecutionResult(
                    result = validatorResult.withBindings(testScenario.bindings, response),
                    request = request,
                    response = response
                )
            }

            val testResult = validatorResult ?: testResult(request, response, testScenario, flagsBased)
            val responseHandler = response.getResponseHandlerIfExists()
            if (testResult is Result.Failure && responseHandler == null) {
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

            return ContractTestExecutionResult(
                result = result.withBindings(testScenario.bindings, response),
                request = request,
                response = response
            )
        } catch (exception: Throwable) {
            return ContractTestExecutionResult(
                result = Result.Failure(exceptionCauseMessage(exception))
                    .also { failure -> failure.updateScenario(testScenario) }
            )
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
}

private fun LogMessage.withComment(comment: String?): LogMessage {
    return if (this is HttpLogMessage) {
        this.copy(comment = comment)
    } else {
        this
    }
}
