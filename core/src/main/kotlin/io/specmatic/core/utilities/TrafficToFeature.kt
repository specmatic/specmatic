package io.specmatic.core.utilities

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpQueryParamPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.MultiPartFormDataPattern
import io.specmatic.core.NamedStub
import io.specmatic.core.NoBodyValue
import io.specmatic.core.Scenario
import io.specmatic.core.adjustPayloadForContentType
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.partitionOnContentType
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.EmptyStringPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Examples
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.withPatternDelimiters
import io.specmatic.core.pattern.withoutPatternDelimiters
import io.specmatic.core.queryParamsToScalarMap
import io.specmatic.core.stringMapToScalarMap
import io.specmatic.core.stringMapToValueMap
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType

internal fun featureFromTraffic(featureName: String, namedStubs: List<NamedStub>): Feature = Feature(
    scenarios = namedStubs.map(::scenarioFromTraffic),
    name = featureName,
    protocol = SpecmaticProtocol.HTTP,
)

private data class RequestInference(
    val pattern: HttpRequestPattern,
    val types: Map<String, Pattern>,
)

private data class ResponseInference(
    val pattern: HttpResponsePattern,
    val types: Map<String, Pattern>,
)

private data class ResponseBodyInference(
    val pattern: Pattern,
    val types: Map<String, Pattern>,
)

private fun scenarioFromTraffic(namedStub: NamedStub): Scenario {
    val request = namedStub.stub.requestElsePartialRequest()
    val response = namedStub.stub.response
    val requestInference = inferRequest(request)
    val responseInference = inferResponse(response, requestInference.types)
    val namedPatterns = responseInference.types.mapKeys { (name, _) ->
        withPatternDelimiters(withoutPatternDelimiters(name))
    }
    val exampleRow = Row(
        name = namedStub.name,
        requestExample = request.asTrafficExample(),
        responseExample = response.copy(headers = response.headers.withoutContentTypeParameters()),
        scenarioStub = namedStub.stub,
    )

    return Scenario(
        name = namedStub.name,
        httpRequestPattern = requestInference.pattern,
        httpResponsePattern = responseInference.pattern,
        examples = listOf(Examples(exampleRow.columnNames, listOf(exampleRow))),
        patterns = namedPatterns,
        protocol = SpecmaticProtocol.HTTP,
        specType = SpecType.OPENAPI,
    )
}

private fun HttpRequest.asTrafficExample(): HttpRequest = copy(
    headers = headers.withoutContentTypeParameters(),
    body = if (body == NoBodyValue) EmptyString else body,
)

private fun Map<String, String>.withoutContentTypeParameters(): Map<String, String> = mapValues { (name, value) ->
    if (name.equals("Content-Type", ignoreCase = true)) value.substringBefore(';').trim() else value
}

private fun inferRequest(request: HttpRequest): RequestInference {
    val method = request.method ?: throw ContractException("Can't generate a spec file without the http method.")
    val path = request.path ?: throw ContractException("Can't generate a contract without the url.")
    val (queryPatterns, queryTypes) = inferPatterns(queryParamsToScalarMap(request.queryParams), emptyMap())
    val (contentType, otherHeaders) = partitionOnContentType(request.headers)
    val (headerPatterns, headerTypes) = inferPatterns(stringMapToScalarMap(otherHeaders), queryTypes)
    val headersWithContentType = contentType?.value?.substringBefore(';')?.let { value ->
        headerPatterns + (contentType.key to ExactValuePattern(StringValue(value)))
    } ?: headerPatterns

    val bodyInference = inferRequestPayload(request, headerTypes)
    return RequestInference(
        pattern = HttpRequestPattern(
            headersPattern = HttpHeadersPattern(headersWithContentType),
            httpPathPattern = buildHttpPathPattern(path.replace(" ", "%20")),
            httpQueryParamPattern = HttpQueryParamPattern(queryPatterns.mapValues { (_, pattern) ->
                QueryParameterScalarPattern(pattern)
            }),
            method = method,
            body = bodyInference.body,
            formFieldsPattern = bodyInference.formFields,
            multiPartFormDataPattern = bodyInference.multiPart,
        ),
        types = bodyInference.types,
    )
}

private data class RequestPayloadInference(
    val body: Pattern = EmptyStringPattern,
    val formFields: Map<String, Pattern> = emptyMap(),
    val multiPart: List<MultiPartFormDataPattern> = emptyList(),
    val types: Map<String, Pattern>,
)

private fun inferRequestPayload(request: HttpRequest, types: Map<String, Pattern>): RequestPayloadInference = when {
    request.multiPartFormData.isNotEmpty() -> RequestPayloadInference(
        multiPart = request.multiPartFormData.map { it.inferType() },
        types = types,
    )
    request.formFields.isNotEmpty() -> {
        val (patterns, updatedTypes) = inferPatterns(stringMapToValueMap(request.formFields), types)
        RequestPayloadInference(formFields = patterns, types = updatedTypes)
    }
    request.body == EmptyString || request.body == NoBodyValue -> RequestPayloadInference(types = types)
    else -> {
        val declaration = request.body.toPatternDeclaration("RequestBody", types)
        RequestPayloadInference(body = declaration.pattern, types = declaration.types)
    }
}

private fun inferResponse(response: HttpResponse, initialTypes: Map<String, Pattern>): ResponseInference {
    if (response.status <= 0) throw ContractException("Can't generate a contract without a response status")

    val (contentType, otherHeaders) = partitionOnContentType(response.headers)
    val (headerPatterns, headerTypes) = inferPatterns(stringMapToScalarMap(otherHeaders), initialTypes)
    val headersWithContentType = contentType?.value?.substringBefore(';')?.let { value ->
        headerPatterns + (contentType.key to ExactValuePattern(StringValue(value)))
    } ?: headerPatterns
    val payload = adjustPayloadForContentType(response.body, response.headers)
    val bodyInference = if (payload == EmptyString) {
        ResponseBodyInference(EmptyStringPattern, headerTypes)
    } else {
        val declaration = payload.toPatternDeclaration("ResponseBody", headerTypes)
        ResponseBodyInference(declaration.pattern, declaration.types)
    }

    return ResponseInference(
        pattern = HttpResponsePattern(HttpHeadersPattern(headersWithContentType), response.status, bodyInference.pattern),
        types = bodyInference.types,
    )
}

private fun inferPatterns(
    values: Map<String, Value>,
    initialTypes: Map<String, Pattern>,
): Pair<Map<String, Pattern>, Map<String, Pattern>> =
    values.entries.fold(emptyMap<String, Pattern>() to initialTypes) { (patterns, types), (name, value) ->
        val declaration = value.toPatternDeclaration(name, types)
        patterns + (name to declaration.pattern) to declaration.types
    }
