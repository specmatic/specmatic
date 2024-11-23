package io.specmatic.test

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.*
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.Value

data class ScenarioAsTest(
    val scenario: Scenario,
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
) : ContractTest {

    companion object {
        private var id: Value? = null
    }

    override fun toScenarioMetadata() = scenario.toScenarioMetadata()

    override fun testResultRecord(result: Result, response: HttpResponse?): TestResultRecord {
        val resultStatus = result.testResult()

        return TestResultRecord(
            convertPathParameterStyle(scenario.path),
            scenario.method,
            scenario.status,
            resultStatus,
            sourceProvider,
            sourceRepository,
            sourceRepositoryBranch,
            specification,
            serviceType,
            actualResponseStatus = response?.status ?: 0,
            scenarioResult = result
        )
    }

    override fun testDescription(): String {
        return scenario.testDescription()
    }

    override fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): Pair<Result, HttpResponse?> {
        val log: (LogMessage) -> Unit = { logMessage ->
            logger.log(logMessage.withComment(this.annotations))
        }

        val httpClient = HttpClient(testBaseURL, log = log, timeoutInMilliseconds = timeoutInMilliseconds)

        return runTest(httpClient)
    }

    override fun runTest(testExecutor: TestExecutor): Pair<Result, HttpResponse?> {

        val (result, response) = executeTestAndReturnResultAndResponse(scenario, testExecutor, flagsBased)
        return Pair(result.updateScenario(scenario), response)
    }

    override fun plusValidator(validator: ResponseValidator): ScenarioAsTest {
        return this.copy(
            validators = this.validators.plus(validator)
        )
    }

    private fun logComment() {
        if (annotations != null) {
            logger.log(annotations)
        }
    }

    private fun executeTestAndReturnResultAndResponse(
        testScenario: Scenario,
        testExecutor: TestExecutor,
        flagsBased: FlagsBased
    ): Pair<Result, HttpResponse?> {
        val request = testScenario.generateHttpRequest(flagsBased).let {
            workflow.updateRequest(it, originalScenario)
        }

        try {
            val updatedRequest = ExampleProcessor.resolve(request)

            val substitutionResult = originalScenario.httpRequestPattern.matches(updatedRequest, flagsBased.update(originalScenario.resolver))
            if (substitutionResult is Result.Failure && !testScenario.isA4xxScenario() && !testScenario.isNegative) {
                return Pair(substitutionResult.updateScenario(testScenario), null)
            }

            testExecutor.setServerState(testScenario.serverState)
            testExecutor.preExecuteScenario(testScenario, updatedRequest)
            val response = testExecutor.execute(updatedRequest)

            //TODO: Review - Do we need workflow anymore
            workflow.extractDataFrom(response, originalScenario)

            val validatorResult = validators.asSequence().map { it.validate(scenario, response) }.filterNotNull().firstOrNull()
            if (validatorResult is Result.Failure) {
                return Pair(validatorResult.withBindings(testScenario.bindings, response), response)
            }

            val testResult = testResult(updatedRequest, response, testScenario, flagsBased)
            if (testResult is Result.Failure) {
                return Pair(testResult.withBindings(testScenario.bindings, response), response)
            }

            val postValidateResult = validators.asSequence().map { it.postValidate(testScenario, updatedRequest, response) }.filterNotNull().firstOrNull()
            val result = postValidateResult ?: testResult

            testScenario.exampleRow?.let { ExampleProcessor.store(it, updatedRequest, response) }
            return Pair(result.withBindings(testScenario.bindings, response), response)
        } catch (exception: Throwable) {
            return Pair(
                Result.Failure(exceptionCauseMessage(exception))
                .also { failure -> failure.updateScenario(testScenario) }, null)
        }
    }

    private fun testResult(
        request: HttpRequest,
        response: HttpResponse,
        testScenario: Scenario,
        flagsBased: FlagsBased? = null
    ): Result {

        val result = when {
            response.specmaticResultHeaderValue() == "failure" -> Result.Failure(response.body.toStringLiteral()).updateScenario(testScenario)
            else -> testScenario.matches(request, response, ContractAndResponseMismatch, flagsBased?.unexpectedKeyCheck ?: ValidateUnexpectedKeys)
        }

        if (result is Result.Success && result.isPartialSuccess()) {
            logger.log("    PARTIAL SUCCESS: ${result.partialSuccessMessage}")
            logger.newLine()
        }

        return result
    }

}

private fun LogMessage.withComment(comment: String?): LogMessage {
    return if (this is HttpLogMessage) {
        this.copy(comment = comment)
    } else {
        this
    }
}
