package io.specmatic.test

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import kotlin.math.pow

interface Sleeper {
    fun sleep(milliSeconds: Long)
}

val DefaultSleeper = object : Sleeper {
    override fun sleep(milliSeconds: Long) = Thread.sleep(milliSeconds)
}

class ResponseMonitor(
    private val feature: Feature, val originalScenario: Scenario, private val response: HttpResponse,
    private val maxRetry: Int = 3, private val backOffDelay: Long = 1000, private val sleeper: Sleeper = DefaultSleeper
) {
    companion object {
        private const val headerKey: String = "Link"
        private const val requestPath: String = "request"
        private const val responsePath: String = "response"

        fun isMonitorLinkPresent(response: HttpResponse): Boolean {
            return response.headers.containsKey(headerKey)
        }
    }

    fun waitForResponse(executor: TestExecutor): ReturnValue<HttpResponse> {
        val (monitorScenario, monitorLink) = when(val result = getScenarioAndLink()) {
            is HasValue -> result.value
            is HasException -> return result.cast()
            is HasFailure -> return result.cast()
        }

        repeat(maxRetry) { count ->
            try {
                val response = checkStatus(monitorScenario, executor, monitorLink)
                monitorScenario.matches(response).throwOnFailure()

                val monitorComplete = response.checkCompletion()
                if (monitorComplete is HasValue) return validateMonitorResponse(monitorComplete)

                val delay = getBackOffDelay(count)
                if (count < maxRetry - 1) sleeper.sleep(delay)
            } catch (e: Exception) { return HasException(e) }
        }

        return HasFailure("Max retries exceeded, monitor link: $monitorLink")
    }

    private fun validateMonitorResponse(monitorValue: HasValue<Pair<HttpRequest, HttpResponse>>): ReturnValue<HttpResponse> {
        val (requestFromMonitor, responseFromMonitor) = monitorValue.value
        val result = originalScenario.matches(
            httpRequest = requestFromMonitor,
            httpResponse = responseFromMonitor,
            mismatchMessages = DefaultMismatchMessages,
            flagsBased = feature.flagsBased,
            disableOverrideKeyCheck = false
        ).breadCrumb("MONITOR")

        return if (result is Result.Failure) HasFailure(result, message = "Monitor request / response doesn't match scenario")
        else HasValue(responseFromMonitor)
    }

    private fun getScenarioAndLink(): ReturnValue<Pair<Scenario, Link>> {
        val processingScenario = getProcessingScenario() ?:
            return HasFailure("No accepted response scenario found for ${originalScenario.defaultAPIDescription}")

        val processingScenarioResult = processingScenario.matches(response)
        if (processingScenarioResult is Result.Failure) {
            return HasFailure(processingScenarioResult, message = "Response doesn't match processing scenario")
        }

        val monitorLink = response.extractMonitorLinkFromHeader(headerKey)
        val monitorScenario = monitorLink?.monitorScenario() ?:
            return HasFailure("No monitor scenario found matching link: $monitorLink")

        return HasValue(Pair(monitorScenario, monitorLink))
    }

    private fun checkStatus(monitorScenario: Scenario, executor: TestExecutor, monitorLink: Link): HttpResponse {
        val request = monitorScenario.generateHttpRequest().updatePath(monitorLink.toPath())
        return executor.execute(request)
    }

    private fun extractLinksFromHeader(headerValue: String): List<Link> {
        return headerValue.split(",").map { it.trim() }
            .map { link ->
                val parts = link.split(";").map { it.trim() }
                val url = parts[0].removePrefix("<").removeSuffix(">")
                val rel = parts[1].removePrefix("rel=").removeSurrounding("\"")
                val title = parts.getOrNull(2)?.removePrefix("title=")?.removeSurrounding("\"")
                Link(url, rel, title)
            }
    }

    private fun getBackOffDelay(retryCount: Int): Long {
        return backOffDelay * 2.0.pow(retryCount).toLong()
    }

    private fun HttpResponse.convertToOriginalResponse(): HttpResponse {
        if (this.body !is JSONObjectValue) throw ContractException("Monitor response body is not an object")
        return this.body.toResponse()
    }

    private fun HttpResponse.convertToOriginalRequest(): HttpRequest {
        if (this.body !is JSONObjectValue) throw ContractException("Monitor response body is not an object")
        return this.body.toRequest()
    }

    private fun HttpResponse.checkCompletion(): ReturnValue<Pair<HttpRequest, HttpResponse>> {
        val (request, response) = runCatching {
            this.convertToOriginalRequest() to this.convertToOriginalResponse()
        }.getOrElse { return HasException(it) }
        return HasValue(request to response)
    }

    private fun HttpResponse.extractMonitorLinkFromHeader(key: String): Link? {
        val headerValue = this.headers[key] ?: return null
        return extractLinksFromHeader(headerValue).firstOrNull {
            it.title == "monitor"
        }
    }

    private fun getProcessingScenario(): Scenario? {
        if (!feature.isAcceptedResponsePossible(originalScenario)) return null
        return feature.scenarioAssociatedTo(
            path = originalScenario.path, method = originalScenario.method,
            responseStatusCode = 202, contentType = originalScenario.requestContentType
        )
    }

    private fun JSONObjectValue.toRequest(): HttpRequest {
        val requestObject = this.jsonObject[requestPath] as? JSONObjectValue
            ?: throw ContractException(breadCrumb = requestPath, errorMessage = "Expected a json object")
        return HttpRequest(
            path = originalScenario.path,
            method = requestObject.getString("method"),
            headers = requestObject.getMapOrEmpty("header"),
            body = requestObject.jsonObject["body"] ?: NullValue
        )
    }

    private fun JSONObjectValue.toResponse(): HttpResponse {
        val responseObject = this.jsonObject[responsePath] as? JSONObjectValue?: throw ContractException(
            breadCrumb = responsePath,
            errorMessage = "Expected a json object"
        )

        val statusCode = responseObject.findFirstChildByName("statusCode")?.toStringLiteral()?.toIntOrNull() ?: throw ContractException(
            breadCrumb = responsePath,
            errorMessage = "Missing or invalid status code"
        )

        return HttpResponse(
            status = statusCode,
            headers = responseObject.getMapOrEmpty("header"),
            body = responseObject.jsonObject["body"] ?: NullValue
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

    private fun Link.monitorScenario(): Scenario? {
        return feature.scenarios.firstOrNull {
            if(it.httpRequestPattern.httpPathPattern == null) return@firstOrNull false
            it.httpRequestPattern.matchesPath(this.toPath(), it.resolver) is Result.Success
        }
    }

    data class Link(val url: String, val rel: String, val title: String? = null) {
        fun toPath(): String {
            return url
        }
    }
}