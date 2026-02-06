package io.specmatic.stub

import io.specmatic.core.*
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.log.logger
import io.specmatic.core.matchers.CompositeMatcher
import io.specmatic.core.matchers.Matcher
import io.specmatic.core.matchers.MatcherContext
import io.specmatic.core.matchers.MatcherResult
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.attempt
import io.specmatic.core.pattern.unwrapOrContractException
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.utilities.ExternalCommand
import io.specmatic.core.utilities.Transactional
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
    val partial = scenarioStub?.let { it.partial?.copy(response = it.partial.response) }
    val data = scenarioStub?.data ?: JSONObjectValue()
    val examplePath = scenarioStub?.filePath
    val stubToken = scenarioStub?.stubToken
    val requestBodyRegex = scenarioStub?.requestBodyRegex
    val delayInMilliseconds = scenarioStub?.delayInMilliseconds

    private val matcher: CompositeMatcher? by lazy { buildMatcherFromRequest() }
    private val sharedState: Transactional<ObjectValueOperator> = Transactional(ObjectValueOperator())
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
        if (matcher == null) return stubToken != null
        sharedState.commit()
        return false
    }

    fun matches(
        httpRequest: HttpRequest,
        mismatchMessages: MismatchMessages = defaultMismatchMessages,
    ): Result {
        val exampleMatchResult = matchExample(httpRequest, mismatchMessages)
        if (exampleMatchResult is Result.Failure) return exampleMatchResult

        sharedState.rollback()
        val updatedSharedState = matchesMatcher(httpRequest).unwrapOrReturn { return it.toFailure() }
        sharedState.stage(updatedSharedState)
        return Result.Success()
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

    private fun matchesMatcher(httpRequest: HttpRequest): ReturnValue<ObjectValueOperator> {
        val sharedState = sharedState.getCommitted()
        val requestMatcher = matcher ?: return HasValue(sharedState)
        val scenario = scenario ?: return HasValue(sharedState)

        val context = MatcherContext.from(httpRequest, sharedState, scenario)
        return when (val result = requestMatcher.match(context).checkOverArchingExhaustion()) {
            is MatcherResult.Success -> HasValue(result.context.finalizeSharedState())
            is MatcherResult.MisMatch -> HasFailure(result.failure)
            is MatcherResult.Exhausted -> if (this.stubToken != null) {
                HasFailure("transient http mock data matchers have been exhausted")
            } else {
                HasValue(result.context.finalizeSharedState())
            }
        }
    }

    private fun buildMatcherFromRequest(): CompositeMatcher? {
        val requestJson = resolveOriginalRequest()?.toJSON() ?: return null
        val matchers = Matcher.from(requestJson, resolver, "request").unwrapOrContractException()
        if (matchers.isEmpty()) return null
        return CompositeMatcher(BreadCrumb.from(), matchers)
    }
}

fun executeExternalCommand(command: String, envParams: Map<String, String>): String {
    logger.debug("Executing: $command with EnvParams: $envParams")
    return ExternalCommand(command, ".", envParams).executeAsSeparateProcess()
}

data class StubDataItems(val http: List<HttpStubData> = emptyList())

enum class StubType { Exact, Partial }
