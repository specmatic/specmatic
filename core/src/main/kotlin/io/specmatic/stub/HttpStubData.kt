package io.specmatic.stub

import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.attempt
import io.specmatic.core.utilities.ExternalCommand
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.mock.ScenarioStub

class ExampleAndRequestMismatchMessages(val exampleName: String?) : MismatchMessages {
    private val exampleNamePart = if (exampleName == null) "" else " \"$exampleName\""

    override fun mismatchMessage(expected: String, actual: String): String {
        return "Example$exampleNamePart expected $expected but request contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the request was not in the example$exampleNamePart"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Example$exampleNamePart expected mandatory $keyLabel \"$keyName\" to be present but was missing from the request"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional $keyLabel \"$keyName\" from example$exampleNamePart to be present but was missing from the request"
    }
}

data class HttpStubData(
    val requestType: HttpRequestPattern,
    val response: HttpResponse,
    val resolver: Resolver,
    val responsePattern: HttpResponsePattern,
    val contractPath: String = "",
    val feature: Feature? = null,
    val scenario: Scenario? = null,
    private val originalRequest: HttpRequest? = null,
    val scenarioStub: ScenarioStub? = null
) {
    val name = scenarioStub?.name
    val partial = scenarioStub?.partial
    val data = scenarioStub?.data ?: JSONObjectValue()
    val examplePath = scenarioStub?.filePath
    val stubToken = scenarioStub?.stubToken
    val requestBodyRegex = scenarioStub?.requestBodyRegex
    val delayInMilliseconds = scenarioStub?.delayInMilliseconds

    private val stubMatcher: HttpStubMatcher? by lazy { HttpStubMatcherFactory.load()?.create(this) }
    private val defaultMismatchMessages: MismatchMessages = ExampleAndRequestMismatchMessages(name)

    fun resolveOriginalRequest(): HttpRequest? {
        return partial?.request ?: originalRequest
    }

    val stubType: StubType get () {
        if(partial != null) {
            return StubType.Partial
        }

        return StubType.Exact
    }
    val matchFailure: Boolean
        get() = response.headers[SPECMATIC_RESULT_HEADER] == "failure"

    fun softCastResponseToXML(httpRequest: HttpRequest): HttpStubData = when {
        response.externalisedResponseCommand.isNotEmpty() -> invokeExternalCommand(httpRequest).copy(contractPath = contractPath)
        else -> this.copy(response = response.adjustPayloadForContentType())
    }

    fun utilize(): Boolean {
        if(stubMatcher == null) return this.stubToken != null
        return stubMatcher?.utilize() == true
    }

    fun matches(
        httpRequest: HttpRequest,
        mismatchMessages: MismatchMessages = defaultMismatchMessages,
    ): Result {
        val exampleMatchResult = matchExample(httpRequest, mismatchMessages)
        if (exampleMatchResult is Result.Failure) return exampleMatchResult

        return stubMatcher?.matches(httpRequest) ?: Result.Success()
    }

    private fun invokeExternalCommand(httpRequest: HttpRequest): HttpStubData {
        val result = executeExternalCommand(
            response.externalisedResponseCommand,
            mapOf("SPECMATIC_REQUEST" to """'${httpRequest.toJSON().toUnformattedStringLiteral()}'"""),
        )

        val externalCommandResponse = attempt {
            val responseMap = jsonStringToValueMap(result)
            HttpResponse.fromJSON(responseMap)
        }

        val responseMatches = responsePattern.matchesResponse(externalCommandResponse, resolver.copy(mismatchMessages = SpecificationExternalResponseMismatch))
        return when {
            !responseMatches.isSuccess() -> {
                val errorMessage =
                    """Response returned by ${response.externalisedResponseCommand} not in line with specification for ${httpRequest.method} ${httpRequest.path}:\n${responseMatches.reportString()}"""
                logger.log(errorMessage)
                throw ContractException(errorMessage)
            }
            else -> {
                this.copy(response = externalCommandResponse)
            }
        }
    }

    private fun matchExample(
        httpRequest: HttpRequest,
        mismatchMessages: MismatchMessages = defaultMismatchMessages,
    ): Result {
        return requestType.matches(
            httpRequest,
            resolver.disableOverrideUnexpectedKeyCheck().copy(mismatchMessages = mismatchMessages),
            requestBodyReqex = requestBodyRegex,
        )
    }
}

fun executeExternalCommand(command: String, envParams: Map<String, String>): String {
    logger.debug("Executing: $command with EnvParams: $envParams")
    return ExternalCommand(command, ".", envParams).executeAsSeparateProcess()
}

data class StubDataItems(val http: List<HttpStubData> = emptyList())

enum class StubType { Exact, Partial }
