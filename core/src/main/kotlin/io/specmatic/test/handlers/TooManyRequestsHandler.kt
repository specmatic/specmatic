package io.specmatic.test.handlers

import io.ktor.http.HttpStatusCode
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnFailure
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.test.MonitorRequestGeneratorStrategy
import io.specmatic.test.MonitorResult
import io.specmatic.test.ResponseMonitor
import io.specmatic.test.TestExecutor
import io.specmatic.test.utils.DelayStrategy
import io.specmatic.test.utils.RetryHandler
import io.specmatic.test.utils.RetryResult

class TooManyRequestsHandler(
    private val feature: Feature,
    private val originalScenario: Scenario,
    private val retryHandler: RetryHandler<MonitorResult, HttpResponse> = RetryHandler(DelayStrategy.RespectRetryAfter())
): ResponseHandler {
    override fun canHandle(response: HttpResponse, scenario: Scenario): Boolean {
        val isTooManyRequestsPossible= feature.isResponseStatusPossible(scenario, HttpStatusCode.TooManyRequests.value)
        return scenario.status == HttpStatusCode.TooManyRequests.value
                && response.status == HttpStatusCode.TooManyRequests.value
                && isTooManyRequestsPossible
    }

    override fun handle(request: HttpRequest, response: HttpResponse, testScenario: Scenario, testExecutor: TestExecutor): ResponseHandlingResult {
        val processingScenario = getProcessingScenario()
            ?: return ResponseHandlingResult.Stop(Result.Failure("No tooManyRequests scenario found for ${originalScenario.defaultAPIDescription}"))

        val processingScenarioResult = processingScenario.matches(response)
        if (processingScenarioResult is Result.Failure) {
            return ResponseHandlingResult.Stop(Result.Failure(
                message = "Response doesn't match processing scenario",
                cause = processingScenarioResult,
            ).updateScenario(processingScenario))
        }

        val matchingScenarios = findMatchingScenarios(testScenario)
        val responseMonitor = ResponseMonitor(
            requestGeneratorStrategy = MonitorRequestGeneratorStrategy.ReuseRequest(testScenario, request),
            initialDelayContext = response,
            retryHandler = retryHandler,
            onResponse = { response ->
                val matchResult = matchingScenarios.findMatching(response)
                if (matchResult is ReturnFailure) {
                    logger.debug("Response didn't match any valid scenarios")
                    logger.debug("")
                    logger.debug(matchResult.toFailure().reportString())
                }

                matchResult.realise(
                    hasValue = { _, _ -> RetryResult.Stop(MonitorResult.Success(response)) },
                    orException = { e -> retryResultOnMatchFailure(e, response) },
                    orFailure = { f -> retryResultOnMatchFailure(f, response) },
                )
            },
        )

        return when (val monitorResult = responseMonitor.waitForResponse(testExecutor)) {
            is MonitorResult.Success -> ResponseHandlingResult.Continue(monitorResult.response)
            is MonitorResult.Failure -> ResponseHandlingResult.Stop(monitorResult.failure.updateScenario(testScenario), monitorResult.response)
        }
    }

    private fun findMatchingScenarios(testScenarios: Scenario): List<Scenario> {
        return if (testScenarios.status == HttpStatusCode.TooManyRequests.value) {
            feature.scenarios.filter {
                it.path == testScenarios.path && it.method == testScenarios.method && it.isA2xxScenario()
            }
        } else {
            listOf(originalScenario)
        }
    }

    private fun List<Scenario>.findMatching(response: HttpResponse): ReturnValue<Result> {
        val results = this.map { scenario ->
            scenario.matches(httpResponse = response).updateScenario(scenario)
        }

        return results.firstOrNull { it.isSuccess() }?.let(::HasValue) ?: HasFailure(
            failure = Result.fromFailures(results.filterIsInstance<Result.Failure>().toList()) as Result.Failure,
            message = "Invalid 2xx response received on retry",
        )
    }

    private fun retryResultOnMatchFailure(result: ReturnFailure, response: HttpResponse): RetryResult<MonitorResult, HttpResponse> {
        return if (response.status in 200..299) {
            RetryResult.Stop(MonitorResult.Failure(failure = result.toFailure()))
        } else {
            RetryResult.Continue(response)
        }
    }

    private fun getProcessingScenario(): Scenario? {
        return feature.scenarioAssociatedTo(
            path = originalScenario.path,
            method = originalScenario.method,
            responseStatusCode = HttpStatusCode.TooManyRequests.value,
        )
    }
}
