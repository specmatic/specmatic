package io.specmatic.test.handlers

import io.ktor.http.*
import io.specmatic.core.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.MonitorRequestGeneratorStrategy
import io.specmatic.test.MonitorResult
import io.specmatic.test.ResponseMonitor
import io.specmatic.test.ResponseMonitor.Link
import io.specmatic.test.TestExecutor
import io.specmatic.test.utils.DelayStrategy
import io.specmatic.test.utils.RetryHandler
import io.specmatic.test.utils.RetryResult

class AcceptedResponseHandler(
    private val feature: Feature,
    private val originalScenario: Scenario,
    private val retryHandler: RetryHandler<MonitorResult, HttpResponse> = RetryHandler(DelayStrategy.RespectRetryAfter())
) : ResponseHandler {
    companion object {
        private const val HEADER_KEY: String = "Link"
        private const val REQUEST_PATH: String = "request"
        private const val RESPONSE_PATH: String = "response"

        fun isMonitorLinkPresent(response: HttpResponse): Boolean = response.headers.containsKey(HEADER_KEY)
    }

    override fun canHandle(response: HttpResponse, scenario: Scenario): Boolean {
        if (response.status != HttpStatusCode.Accepted.value) return false
        if (!isMonitorLinkPresent(response)) return false
        val isAcceptedResponsePossible = feature.isResponseStatusPossible(scenario, HttpStatusCode.Accepted.value)
        val inCaseOfAcceptedAnyOther2xxExists = inCaseOfAcceptedAnyOther2xxExists()
        return inCaseOfAcceptedAnyOther2xxExists && isAcceptedResponsePossible
    }

    override fun handle(request: HttpRequest, response: HttpResponse, testScenario: Scenario, testExecutor: TestExecutor): ResponseHandlingResult {
        val (monitorScenario, monitorLink) = when (val result = getScenarioAndLink(response)) {
            is HasValue -> result.value
            is HasException -> return ResponseHandlingResult.Stop(result.toHasFailure().toFailure())
            is HasFailure -> return ResponseHandlingResult.Stop(result.toFailure())
        }

        val responseMonitor = ResponseMonitor(
            requestGeneratorStrategy = MonitorRequestGeneratorStrategy.MonitorLink(monitorScenario, monitorLink),
            initialDelayContext = response,
            retryHandler = retryHandler,
            onResponse = { response ->
                val responseResult = monitorScenario.matches(response)
                if (responseResult is Result.Failure) {
                    return@ResponseMonitor RetryResult.Continue(response)
                }

                when (val result = response.parseToRequestResponse()) {
                    is HasValue -> RetryResult.Stop(validateMonitorResponse(result.value))
                    is HasException -> RetryResult.Continue(response)
                    is HasFailure -> RetryResult.Continue(response)
                }
            },
        )

        return when (val monitorResult = responseMonitor.waitForResponse(testExecutor)) {
            is MonitorResult.Success -> ResponseHandlingResult.Continue(monitorResult.response)
            is MonitorResult.Failure -> ResponseHandlingResult.Stop(monitorResult.failure.updateScenario(testScenario), monitorResult.response)
        }
    }

    private fun getScenarioAndLink(response: HttpResponse): ReturnValue<Pair<Scenario, Link>> {
        val processingScenario = getProcessingScenario() ?: return HasFailure("No accepted response scenario found for ${originalScenario.defaultAPIDescription}")

        val processingScenarioResult = processingScenario.matches(response).updateScenario(processingScenario)
        if (processingScenarioResult is Result.Failure) {
            return HasFailure(processingScenarioResult, message = "Response doesn't match processing scenario")
        }

        val monitorLink = runCatching { response.extractMonitorLinkFromHeader(HEADER_KEY) }.getOrElse { e -> return HasException(e) }
        val monitorScenario = monitorLink?.let(::monitorScenario) ?: return HasFailure("No monitor scenario found matching link: $monitorLink")
        return HasValue(Pair(monitorScenario, monitorLink))
    }

    private fun validateMonitorResponse(monitorValue: Pair<HttpRequest, HttpResponse>): MonitorResult {
        val (requestFromMonitor, responseFromMonitor) = monitorValue
        val scenariosToConsider = if (originalScenario.status == HttpStatusCode.Accepted.value) {
            feature.scenarios.filter {
                if (it.status == HttpStatusCode.Accepted.value) return@filter false
                it.path == originalScenario.path && it.method == originalScenario.method && it.isA2xxScenario()
            }
        } else {
            listOf(originalScenario)
        }

        val result = scenariosToConsider.findMatching(requestFromMonitor, responseFromMonitor)
        return result.realise(
            hasValue = { _, _ -> MonitorResult.Success(responseFromMonitor) },
            orException = { e -> MonitorResult.Failure(e.toHasFailure().toFailure(), responseFromMonitor) },
            orFailure = { f -> MonitorResult.Failure(f.toFailure(), responseFromMonitor) },
        )
    }

    private fun HttpResponse.extractMonitorLinkFromHeader(key: String): Link? {
        val headerValue = this.headers[key] ?: return null
        return extractLinksFromHeader(headerValue).firstOrNull {
            it.title == "monitor"
        }
    }

    private fun extractLinksFromHeader(headerValue: String): List<Link> =
        headerValue.split(",").map(String::trim).map { link ->
            val parts = link.split(";").map(String::trim)
            val url = parts.getOrNull(0)?.removePrefix("<")?.removeSuffix(">") ?: throw IllegalArgumentException("Invalid $HEADER_KEY Header $headerValue")
            val rel = parts.getOrNull(1)?.removePrefix("rel=")?.removeSurrounding("\"")
            val title = parts.getOrNull(2)?.removePrefix("title=")?.removeSurrounding("\"")
            Link(url, rel, title)
        }

    private fun getProcessingScenario(): Scenario? {
        return feature.scenarioAssociatedTo(
            path = originalScenario.path,
            method = originalScenario.method,
            responseStatusCode = HttpStatusCode.Accepted.value,
            contentType = originalScenario.requestContentType,
        )
    }

    private fun monitorScenario(link: Link): Scenario? {
        return feature.scenarios.firstOrNull {
            if (it.httpRequestPattern.httpPathPattern == null) return@firstOrNull false
            if (it.method != "GET") return@firstOrNull false
            it.httpRequestPattern.matchesPath(link.toPath(), it.resolver) is Result.Success
        }
    }

    private fun HttpResponse.parseToRequestResponse(): ReturnValue<Pair<HttpRequest, HttpResponse>> {
        return runCatching {
            HasValue(this.convertToOriginalRequest() to this.convertToOriginalResponse())
        }.getOrElse {
            return HasException(it)
        }
    }

    private fun HttpResponse.convertToOriginalResponse(): HttpResponse {
        if (this.body !is JSONObjectValue) throw ContractException("Monitor response body is not an object")
        return this.body.toResponse()
    }

    private fun HttpResponse.convertToOriginalRequest(): HttpRequest {
        if (this.body !is JSONObjectValue) throw ContractException("Monitor response body is not an object")
        return this.body.toRequest()
    }

    private fun JSONObjectValue.toRequest(): HttpRequest {
        val requestObject = this.jsonObject[REQUEST_PATH] as? JSONObjectValue ?: throw ContractException(breadCrumb = REQUEST_PATH, errorMessage = "Expected a json object")
        return HttpRequest(
            path = originalScenario.path,
            method = requestObject.getString("method"),
            headers = requestObject.getMapOrEmpty("header"),
            body = requestObject.jsonObject["body"] ?: NullValue,
        )
    }

    private fun JSONObjectValue.toResponse(): HttpResponse {
        val responseObject = this.jsonObject[RESPONSE_PATH] as? JSONObjectValue ?: throw ContractException(
            breadCrumb = RESPONSE_PATH,
            errorMessage = "Expected a json object",
        )

        val statusCode = responseObject.findFirstChildByName("statusCode")?.toStringLiteral()?.toIntOrNull() ?: throw ContractException(
            breadCrumb = RESPONSE_PATH,
            errorMessage = "Missing or invalid status code",
        )

        return HttpResponse(
            status = statusCode,
            headers = responseObject.getMapOrEmpty("header"),
            body = responseObject.jsonObject["body"] ?: NullValue,
        )
    }

    private fun JSONObjectValue.getMapOrEmpty(path: String): Map<String, String> {
        val value = this.jsonObject[path] as? JSONArrayValue ?: return emptyMap()
        return value.list.mapNotNull {
            val header = it as? JSONObjectValue ?: return@mapNotNull null
            val headerName = header.jsonObject["name"] as? StringValue ?: return@mapNotNull null
            val headerValue = header.jsonObject["value"] as? StringValue ?: return@mapNotNull null
            headerName.string to headerValue.string
        }.toMap()
    }

    private fun inCaseOfAcceptedAnyOther2xxExists(): Boolean {
        if (originalScenario.status != HttpStatusCode.Accepted.value) return true
        return feature.scenarios.find {
            if (it.status == HttpStatusCode.Accepted.value) return@find false
            it.path == originalScenario.path && it.method == originalScenario.method && it.isA2xxScenario()
        } != null
    }

    fun List<Scenario>.findMatching(request: HttpRequest, response: HttpResponse): ReturnValue<Result> {
        val results = this.map { scenario ->
            scenario.matches(
                httpRequest = request,
                httpResponse = response,
                mismatchMessages = DefaultMismatchMessages,
                flagsBased = feature.flagsBased,
                disableOverrideKeyCheck = false,
            ).breadCrumb("MONITOR").updateScenario(scenario)
        }

        return results.firstOrNull { it.isSuccess() }?.let(::HasValue) ?: HasFailure(
            failure = Result.fromFailures(results.filterIsInstance<Result.Failure>().toList()) as Result.Failure,
            message = "Invalid request or response payload in the monitor response",
        )
    }
}
