package io.specmatic.core

import io.specmatic.core.discriminator.DiscriminatorBasedItem
import io.specmatic.core.discriminator.DiscriminatorBasedValueGenerator
import io.specmatic.core.discriminator.DiscriminatorMetadata
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.stub.softCastValueToXML

const val DEFAULT_RESPONSE_CODE = 1000
const val STATUS_BREAD_CRUMB = "STATUS"

data class HttpResponsePattern(
    val headersPattern: HttpHeadersPattern = HttpHeadersPattern(),
    val status: Int = 0,
    val body: Pattern = EmptyStringPattern,
    val responseValueAssertion: ResponseValueAssertion = AnyResponse
) {
    constructor(response: HttpResponse) : this(HttpHeadersPattern(response.headers.mapValues { stringToPattern(it.value, it.key) }), response.status, response.body.exactMatchElseType())

    fun fillInTheBlanks(resolver: Resolver): HttpResponse {
        return generateResponseWith(
            value = resolver.withCyclePrevention(body, body::generate),
            resolver = resolver
        )
    }

    fun generateResponseV2(resolver: Resolver): List<DiscriminatorBasedItem<HttpResponse>> {
        return attempt(breadCrumb = "RESPONSE") {
            DiscriminatorBasedValueGenerator.generateDiscriminatorBasedValues(
                resolver,
                body
            ).map {
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata(
                        discriminatorProperty = it.discriminatorProperty,
                        discriminatorValue = it.discriminatorValue,
                    ),
                    value = generateResponseWith(it.value, resolver)
                )
            }
        }
    }

    fun generateResponseWithAll(resolver: Resolver): HttpResponse {
        return attempt(breadCrumb = "RESPONSE") {
            val value = attempt(breadCrumb = "BODY") { softCastValueToXML(body.generateWithAll(resolver)) }
            val headers = headersPattern.generateWithAll(resolver).plus(SPECMATIC_RESULT_HEADER to "success").let { headers ->
                when {
                    !headers.containsKey("Content-Type") -> headers.plus("Content-Type" to value.httpContentType)
                    else -> headers
                }
            }
            HttpResponse(status, headers, value)
        }
    }

    fun matchesResponse(response: HttpResponse, resolver: Resolver): Result {
        return response to resolver to
            ::matchStatus then
            ::matchHeaders then
            ::matchResponseBodySchema then
            ::matchExactResponseBodyValue then
            ::summarize otherwise
            ::handleError toResult
            ::returnResult
    }

    fun withResponseExampleValue(row: Row, resolver: Resolver): HttpResponsePattern =
        attempt(breadCrumb = "RESPONSE") {
            val responseExample: ResponseExample = row.exactResponseExample ?: return@attempt this

            val responseExampleMatchResult = matchesResponse(responseExample.responseExample, resolver)

            if(responseExampleMatchResult is Result.Failure)
                throw ContractException("""Error in response in example "${row.name}": ${responseExampleMatchResult.reportString()}""")

            val expectedResponseValue = HttpResponsePattern(
                    responseExample.headersPattern(),
                    responseExample.responseExample.status,
                    responseExample.bodyPattern()
                )

            val responseValueAssertion: ResponseValueAssertion = ValueAssertion(expectedResponseValue)

            this.copy(responseValueAssertion = responseValueAssertion)
        }

    fun matchesMock(response: HttpResponse, resolver: Resolver) = matchesResponse(response, resolver)

    private fun matchStatus(parameters: Pair<HttpResponse, Resolver>): MatchingResult<Pair<HttpResponse, Resolver>> {
        if(status == DEFAULT_RESPONSE_CODE)
            return MatchSuccess(parameters)

        val (response, _) = parameters

        val body = response.body

        return when (response.status) {
            status -> MatchSuccess(parameters)
            else -> MatchFailure(mismatchResult("status $status", "status ${response.status}").copy(breadCrumb = "RESPONSE.STATUS", failureReason = FailureReason.StatusMismatch))
        }
    }

    private fun matchHeaders(parameters: Pair<HttpResponse, Resolver>): MatchingResult<Triple<HttpResponse, Resolver, List<Result.Failure>>> {
        val (response, resolver) = parameters
        return when (val result = headersPattern.matches(response.headers, resolver)) {
            is Result.Failure -> MatchSuccess(
                Triple(
                    response,
                    resolver,
                    listOf(result.breadCrumb("RESPONSE"))
                )
            )
            else -> MatchSuccess(Triple(response, resolver, emptyList()))
        }
    }

    private fun matchResponseBodySchema(parameters: Triple<HttpResponse, Resolver, List<Result.Failure>>): MatchingResult<Triple<HttpResponse, Resolver, List<Result.Failure>>> {
        val (response, resolver, failures) = parameters

        val parsedValue = when (response.body) {
            is StringValue -> try { body.parse(response.body.string, resolver) } catch(e: Throwable) { response.body }
            else -> response.body
        }

        val result = resolver.matchesPattern(null, body, parsedValue).breadCrumb("BODY")
        if(result is Result.Failure) {
            return MatchSuccess(
                Triple(
                    response,
                    resolver,
                    failures.plus(result.breadCrumb("RESPONSE"))
                )
            )
        }

        return MatchSuccess(parameters)
    }

    private fun matchExactResponseBodyValue(parameters: Triple<HttpResponse, Resolver, List<Result.Failure>>): MatchingResult<Triple<HttpResponse, Resolver, List<Result.Failure>>> {
        val (response, resolver, failures) = parameters

        val result = responseValueAssertion.matches(response, resolver.copy(mismatchMessages = valueMismatchMessages))

        if(result is Result.Failure)
            return MatchSuccess(
                Triple(
                    response,
                    resolver,
                    failures.plus(result)
                )
            )

        return MatchSuccess(parameters)
    }

    fun bodyPattern(newBody: Pattern): HttpResponsePattern = this.copy(body = newBody)

    fun encompasses(other: HttpResponsePattern, olderResolver: Resolver, newerResolver: Resolver): Result {
        if(status != other.status)
            return Result.Failure("The status didn't match", breadCrumb = "RESPONSE.STATUS", failureReason = FailureReason.StatusMismatch)

        val headerResult = headersPattern.encompasses(other.headersPattern, Resolver(), Resolver())
        val bodyResult = resolvedHop(body, olderResolver).encompasses(resolvedHop(other.body, newerResolver), olderResolver, newerResolver).breadCrumb("BODY")

        return Result.fromResults(listOf(headerResult, bodyResult)).breadCrumb("RESPONSE")
    }

    fun fromResponseExpectation(response: HttpResponse, resolver: Resolver): HttpResponsePattern {
        val responseHeaders = response.headers.mapValues { stringToPattern(it.value, it.key) }

        val contentTypeHeader = if("content-type" !in responseHeaders.keys.map { it.lowercase() } && headersPattern.contentType != null)
            mapOf("Content-Type" to ExactValuePattern(StringValue(headersPattern.contentType)))
        else
            emptyMap()

        val bodyWithTypeAliases = response.body.exactMatchElseType().let { body.addTypeAliasesToConcretePattern(it, resolver) }

        return HttpResponsePattern(
            HttpHeadersPattern(responseHeaders + contentTypeHeader),
            response.status,
            bodyWithTypeAliases
        )
    }

    fun resolveSubstitutions(substitution: Substitution, response: HttpResponse): HttpResponse {
        val substitutedHeaders = substitution.resolveHeaderSubstitutions(response.headers, headersPattern.pattern).breadCrumb(BreadCrumb.HEADER.value)
        val substitutedBody = body.resolveSubstitutions(substitution, response.body, substitution.resolver).breadCrumb("BODY")

        return substitutedHeaders.combine(substitutedBody) { fullHeaders, fullBody ->
            response.copy(headers = fullHeaders, body = fullBody)
        }.breadCrumb("RESPONSE").value
    }

    fun fillInTheBlanks(partial: HttpResponse, resolver: Resolver): HttpResponse {
        val headers = headersPattern.fillInTheBlanks(
            headers = partial.headers, resolver = resolver.updateLookupPath(BreadCrumb.RESPONSE.value)
        ).breadCrumb(BreadCrumb.HEADER.value)

        val body: ReturnValue<Value> = body.fillInTheBlanks(partial.body, resolver).breadCrumb("BODY")

        return headers.combine(body) { fullHeaders, fullBody ->
            partial.copy(
                headers = fullHeaders,
                body = fullBody
            )
        }.breadCrumb("RESPONSE").value
    }

    private fun generateResponseWith(value: Value, resolver: Resolver): HttpResponse {
        return attempt(breadCrumb = "RESPONSE") {
            val generatedBody = softCastValueToXML(value)
            val headers = headersPattern.generate(
                resolver = resolver.updateLookupPath(BreadCrumb.RESPONSE.value)
            ).plus(SPECMATIC_RESULT_HEADER to "success").let { headers ->
                if ((headers.containsKey("Content-Type").not() && generatedBody.httpContentType.isBlank().not()))
                    headers.plus("Content-Type" to generatedBody.httpContentType)
                else headers
            }
            HttpResponse(status, headers, generatedBody)
        }
    }

    fun withoutOptionality(response: HttpResponse, resolver: Resolver): HttpResponse {
        return response.copy(
            body = body.eliminateOptionalKey(response.body, resolver)
        )
    }

    fun fixResponse(response: HttpResponse, resolver: Resolver): HttpResponse {
        return response.copy(
            status = status,
            headers = headersPattern.fixValue(
                response.headers, resolver.updateLookupPath(BreadCrumb.RESPONSE.value)
            ),
            body = body.fixValue(response.body, resolver)
        )
    }
}

private val valueMismatchMessages = object : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Value mismatch: Expected $expected, got value $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "Value mismatch: $keyLabel $keyName in value was unexpected"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Value mismatch: $keyLabel $keyName was missing"
    }

}
