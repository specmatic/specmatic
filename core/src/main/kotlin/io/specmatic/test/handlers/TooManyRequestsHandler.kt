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
) : ResponseHandler {
    override fun canHandle(response: HttpResponse, scenario: Scenario): Boolean {
        val isTooManyRequestsPossible = feature.isResponseStatusPossible(scenario, HttpStatusCode.TooManyRequests.value)

        return response.status == HttpStatusCode.TooManyRequests.value &&
                isTooManyRequestsPossible
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
        var lastRetryResponse: HttpResponse? = null
        val responseMonitor = ResponseMonitor(
            requestGeneratorStrategy = MonitorRequestGeneratorStrategy.ReuseRequest(testScenario, request),
            initialDelayContext = response,
            retryHandler = retryHandler,
            onResponse = { response ->
                lastRetryResponse = response

                if (response.status == HttpStatusCode.TooManyRequests.value) {
                    val retryResponseResult = processingScenario.matches(response)
                    if (retryResponseResult is Result.Failure) {
                        return@ResponseMonitor RetryResult.Stop(
                            MonitorResult.Failure(
                                failure = Result.Failure(
                                    message = "Response doesn't match processing scenario",
                                    cause = retryResponseResult,
                                ).updateScenario(processingScenario),
                                response = response,
                            )
                        )
                    }

                    return@ResponseMonitor RetryResult.Continue(response)
                }

                val matchResult = matchingScenarios.findMatching(response)
                if (matchResult is ReturnFailure) {
                    logger.debug("Response didn't match any valid scenarios")
                    logger.debug("")
                    logger.debug(matchResult.toFailure().reportString())
                }

                matchResult.realise(
                    hasValue = { _, _ -> RetryResult.Stop(MonitorResult.Success(response)) },
                    orException = { e -> RetryResult.Stop(MonitorResult.Failure(e.toFailure(), response)) },
                    orFailure = { f -> RetryResult.Stop(MonitorResult.Failure(f.toFailure(), response)) },
                )
            },
        )

        return when (val monitorResult = responseMonitor.waitForResponse(testExecutor)) {
            is MonitorResult.Success -> ResponseHandlingResult.Continue(
                response = monitorResult.response,
                recordedResponseOverride = monitorResult.response.takeUnless {
                    testScenario.status == HttpStatusCode.TooManyRequests.value
                },
            )
            is MonitorResult.Failure -> ResponseHandlingResult.Stop(
                monitorResult.failure.updateScenario(testScenario),
                monitorResult.response ?: lastRetryResponse,
            )
        }
    }

    private fun findMatchingScenarios(testScenario: Scenario): List<Scenario> {
        return if (testScenario.status == HttpStatusCode.TooManyRequests.value) {
            feature.scenarios.filter {
                it.path == testScenario.path && it.method == testScenario.method && it.isA2xxScenario()
            }
        } else {
            listOf(testScenario)
        }
    }

    private fun List<Scenario>.findMatching(response: HttpResponse): ReturnValue<Result> {
        val results = this.map { scenario ->
            scenario.matches(httpResponse = response).updateScenario(scenario)
        }

        return results.firstOrNull { it.isSuccess() }?.let(::HasValue) ?: HasFailure(
            failure = Result.fromFailures(results.filterIsInstance<Result.Failure>().toList()) as Result.Failure,
            message = "Invalid response received on retry",
        )
    }

    private fun getProcessingScenario(): Scenario? {
        return feature.scenarioAssociatedTo(
            path = originalScenario.path,
            method = originalScenario.method,
            responseStatusCode = HttpStatusCode.TooManyRequests.value,
        )
    }
}
