package io.specmatic.core.log

import io.specmatic.core.*
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.model.TestResult
import io.specmatic.stub.HttpStubResponse
import io.specmatic.stub.InterceptorResult
import java.io.File

data class HttpLogMessage(
    var preHookRequestTime: CurrentDate? = null,
    var preHookRequest: InterceptorResult<HttpRequest>? = null,
    var requestTime: CurrentDate = CurrentDate(),
    var request: HttpRequest = HttpRequest(),
    var responseTime: CurrentDate? = null,
    var response: HttpResponse? = null,
    var postHookResponseTime: CurrentDate? = null,
    var postHookResponse: InterceptorResult<HttpResponse>? = null,
    var contractPath: String = "",
    var exampleName: String? = null,
    var examplePath: String? = null,
    val targetServer: String = "",
    val comment: String? = null,
    var scenario: Scenario? = null,
    var exception: Exception? = null,
    var result: Result? = null,
    val prettyPrint: Boolean = true,
) : LogMessage {
    fun combineLog(): String {
        val request = this.request.toLogString(prettyPrint = prettyPrint).trim('\n')
        val response = this.response?.toLogString(prettyPrint = prettyPrint)?.trim('\n') ?: "No response"

        return "$request\n\n$response"
    }

    fun duration() = (responseTime?.toEpochMillis() ?: requestTime.toEpochMillis()) - requestTime.toEpochMillis()

    fun displayName() = scenario?.testDescription()

    fun addRequestWithCurrentTime(httpRequest: HttpRequest) {
        requestTime = CurrentDate()
        this.request = httpRequest
    }

    fun addResponseWithCurrentTime(httpResponse: HttpResponse) {
        responseTime = CurrentDate()
        this.response = httpResponse
    }

    fun addPreHookRequestWithCurrentTime(interceptorResultForRequest: InterceptorResult<HttpRequest>) {
        this.preHookRequestTime = CurrentDate()
        this.preHookRequest = interceptorResultForRequest
    }

    fun addPostHookResponseWithCurrentTime(interceptorResultForResponse: InterceptorResult<HttpResponse>) {
        this.postHookResponseTime = CurrentDate()
        this.postHookResponse = interceptorResultForResponse
    }

    fun addException(exception: Exception) {
        this.exception = exception
    }

    fun addResult(result: Result) {
        this.result = result
    }

    private fun target(): String {
        return if(targetServer.isNotBlank()) {
            "to $targetServer "
        } else ""
    }

    override fun toLogString(): String {
        val linePrefix = "  "

        val messagePrefix = listOf(
            "",
            "--------------------",
        )

        val commentLines = if(comment != null) {
            listOf(
                linePrefix,
                comment.prependIndent(linePrefix),
                linePrefix,
                "${linePrefix}-----",
                linePrefix
            )
        } else {
            emptyList()
        }

        val contractPathLines = if(contractPath.isNotBlank()) {
            val exampleLine = if (exampleName != null || examplePath != null) {
                "${linePrefix}Example matched: ${exampleName ?: examplePath}"
            } else {
                null
            }

            listOfNotNull(
                "${linePrefix}Contract matched: $contractPath",
                exampleLine,
                ""
            )
        } else {
            emptyList()
        }

        val mainMessage = listOf(
            "${linePrefix}Request ${target()}at $requestTime",
            request.toLogString("$linePrefix$linePrefix", prettyPrint),
            "",
            "${linePrefix}Response at $responseTime",
            response?.toLogString("$linePrefix$linePrefix", prettyPrint)
        )

        val messageSuffix = listOf("")
        return (messagePrefix + commentLines + contractPathLines + mainMessage + messageSuffix).joinToString(System.lineSeparator())
    }

    override fun toJSONObject(): JSONObjectValue {
        val log = mutableMapOf<String, Value>()

        log["requestTime"] = StringValue(requestTime.toLogString())
        log["http-request"] = request.toJSON()
        log["http-response"] = response?.toJSON() ?: JSONObjectValue()
        log["responseTime"] = StringValue(responseTime?.toLogString() ?: "")
        log["contractMatched"] = StringValue(contractPath)

        return JSONObjectValue(log.toMap())
    }

    fun addResponse(stubResponse: HttpStubResponse) {
        addResponseWithCurrentTime(stubResponse.response)
        contractPath = stubResponse.contractPath
        exampleName = stubResponse.exampleName
        examplePath = stubResponse.examplePath
        scenario = stubResponse.scenario
    }

    fun isInternalControlRequestForMockEvent(): Boolean {
        val path = request.path ?: return false
        val method = request.method

        return path.startsWith("/_$APPLICATION_NAME_LOWER_CASE/") ||
            path == "/swagger/v1/swagger.yaml" ||
            (method.equals("HEAD", ignoreCase = true) && path == "/") ||
            (method.equals("GET", ignoreCase = true) && path == "/actuator/health")
    }

    fun logStartRequestTime() {
        this.requestTime = CurrentDate()
    }

    fun isTestLog(): Boolean {
        return  scenario != null
    }

    fun toResult(): TestResult {
        return when {
            this.exampleName != null || this.examplePath != null -> TestResult.Success
            this.scenario != null && response?.status !in invalidRequestStatuses -> TestResult.Success
            scenario == null -> TestResult.MissingInSpec
            else -> TestResult.Failed
        }
    }

    fun toDetails(): String {
        return when {
            this.exampleName != null || this.examplePath != null -> "Request Matched Example: ${this.exampleName ?: this.examplePath}"
            this.scenario != null && response?.status !in invalidRequestStatuses -> "Request Matched Contract ${scenario?.defaultAPIDescription}"
            this.exception != null -> "Invalid Request\n${exception?.let(::exceptionCauseMessage)}"
            else -> response?.body?.toStringLiteral() ?: "Request Didn't Match Contract"
        }
    }

    fun toName(): String {
        val scenario = this.scenario ?: return "Unknown Request"
        return when {
            this.exampleName != null -> scenario.copy(exampleName = this.exampleName).testDescription()
            this.examplePath != null -> scenario.copy(exampleName = this.examplePath?.let(::File)?.name).testDescription()
            else -> scenario.testDescription()
        }
    }
}
