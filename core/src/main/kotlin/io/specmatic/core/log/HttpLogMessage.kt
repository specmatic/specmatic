package io.specmatic.core.log

import io.specmatic.core.*
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.stub.HttpStubResponse
import java.io.File

data class HttpLogMessage(
    var requestTime: CurrentDate = CurrentDate(),
    var request: HttpRequest = HttpRequest(),
    var responseTime: CurrentDate? = null,
    var response: HttpResponse? = null,
    var contractPath: String = "",
    var examplePath: String? = null,
    val targetServer: String = "",
    val comment: String? = null,
    var scenario: Scenario? = null,
    var exception: Exception? = null
) : LogMessage {

    fun addRequestWithCurrentTime(httpRequest: HttpRequest) {
        requestTime = CurrentDate()
        this.request = httpRequest
    }

    fun addResponseWithCurrentTime(httpResponse: HttpResponse) {
        responseTime = CurrentDate()
        this.response = httpResponse
    }

    fun addException(exception: Exception) {
        this.exception = exception
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
            val exampleLine = examplePath?.let { "${linePrefix}Example matched: $examplePath" }

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
            request.toLogString("$linePrefix$linePrefix"),
            "",
            "${linePrefix}Response at $responseTime",
            response?.toLogString("$linePrefix$linePrefix")
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
        examplePath = stubResponse.examplePath
        scenario = stubResponse.scenario
    }

    fun logStartRequestTime() {
        this.requestTime = CurrentDate()
    }

    fun isTestLog(): Boolean {
        return  scenario != null
    }

    fun toResult(): TestResult {
        return when {
            this.examplePath != null || this.scenario != null && response?.status !in invalidRequestStatuses -> TestResult.Success
            scenario == null -> TestResult.MissingInSpec
            else -> TestResult.Failed
        }
    }

    fun toDetails(): String {
        return when {
            this.examplePath != null -> "Request Matched Example: ${this.examplePath}"
            this.scenario != null && response?.status !in invalidRequestStatuses -> "Request Matched Contract ${scenario?.apiDescription}"
            this.exception != null -> "Invalid Request\n${exception?.let(::exceptionCauseMessage)}"
            else -> response?.body?.toStringLiteral() ?: "Request Didn't Match Contract"
        }
    }

    fun toName(): String {
        val scenario = this.scenario ?: return "Unknown Request"
        return scenario.copy(exampleName = this.examplePath?.let(::File)?.name).testDescription()
    }
}