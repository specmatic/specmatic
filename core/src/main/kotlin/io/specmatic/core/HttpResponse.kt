package io.specmatic.core

import io.ktor.http.*
import io.specmatic.conversions.guessType
import io.specmatic.core.GherkinSection.Then
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.isXML
import io.specmatic.core.value.*
import io.specmatic.mock.FuzzyExampleJsonValidator
import io.specmatic.mock.getIntOrNull
import io.specmatic.mock.getJSONObjectValueOrNull
import io.specmatic.mock.getStringOrNull

private const val SPECMATIC_HEADER_PREFIX = "X-$APPLICATION_NAME-"
const val SPECMATIC_RESULT_HEADER = "${SPECMATIC_HEADER_PREFIX}Result"
internal const val SPECMATIC_EMPTY_HEADER = "${SPECMATIC_HEADER_PREFIX}Empty"
internal const val SPECMATIC_TYPE_HEADER = "${SPECMATIC_HEADER_PREFIX}Type"

data class HttpResponse(
    val status: Int = 0,
    val headers: Map<String, String> = mapOf(CONTENT_TYPE to "text/plain"),
    val body: Value = EmptyString,
    val externalisedResponseCommand: String = ""
) {
    constructor(
        status: Int = 0,
        body: String? = "",
        headers: Map<String, String> = mapOf(CONTENT_TYPE to "text/plain")
    ) : this(status, headers, body?.let { 
        val contentType = headers[CONTENT_TYPE]
        parsedValue(it, contentType) 
    } ?: EmptyString)

    constructor(
        status: Int = 0,
        body: Value,
    ) : this(status, headers = mapOf(CONTENT_TYPE to body.httpContentType), body = body)

    private val statusText: String
        get() =
            when (status) {
                0 -> ""
                else -> HttpStatusCode.fromValue(status).description
            }

    fun containsHeader(key: String): Boolean {
        return headers.keys.any { it.equals(key, ignoreCase = true) }
    }

    fun getHeader(key: String): String? {
        return headers.filter { it.key.equals(key, ignoreCase = true) }
            .values
            .firstOrNull()
    }

    fun withoutSpecmaticTypeHeader(): HttpResponse {
        return this.copy(headers = this.headers.filterKeys { it != "X-Specmatic-Type" })
    }

    fun specmaticResultHeaderValue(): String =
        this.headers.getOrDefault(SPECMATIC_RESULT_HEADER, "success")

    fun updateBodyWith(content: Value): HttpResponse {
        return copy(body = content, headers = headers.minus(CONTENT_TYPE).plus(CONTENT_TYPE to content.httpContentType))
    }

    fun updateBody(body: Value): HttpResponse = copy(body = body)

    fun toJSON(): JSONObjectValue =
        JSONObjectValue(mutableMapOf<String, Value>().also { json ->
            json["status"] = NumberValue(status)
            json["body"] = body
            if (statusText.isNotEmpty()) json["status-text"] = StringValue(statusText)
            if (headers.isNotEmpty()) json["headers"] = JSONObjectValue(headers.mapValues { StringValue(it.value) })
        })

    fun toLogString(prefix: String = ""): String {
        val statusLine = "$status $statusText"
        val headerString = headers.map { "${it.key}: ${it.value}" }.joinToString("\n")

        val firstPart = listOf(statusLine, headerString).joinToString("\n").trim()

        val formattedBody = formatJson(body.toStringLiteral())

        val responseString = listOf(firstPart, "", formattedBody).joinToString("\n")
        return startLinesWith(responseString, prefix)
    }

    fun selectValue(selector: String): String {
        return when {
            selector.startsWith("response-header.") -> {
                val headerName = selector.removePrefix("response-header.").trim()
                this.headers[headerName]
                    ?: throw ContractException("Couldn't find header name $headerName specified in $selector")
            }

            selector.startsWith("response-body") -> {
                val bodySelector = selector.removePrefix("response-body").trim()
                if (bodySelector.isBlank())
                    this.body.toStringLiteral()
                else {
                    if (this.body !is JSONObjectValue)
                        throw ContractException("JSON selector can only be used for JSON body")

                    val jsonBodySelector = bodySelector.removePrefix(".")
                    this.body.findFirstChildByPath(jsonBodySelector)?.toStringLiteral()
                        ?: throw ContractException("JSON selector $selector was not found")
                }
            }

            else -> throw ContractException("Selector $selector is unexpected. It must either start with response-header or response-body.")
        }
    }

    fun export(bindings: Map<String, String>): Map<String, String> {
        return bindings.entries.fold(emptyMap()) { acc, setter ->
            acc.plus(setter.key to selectValue(setter.value))
        }
    }

    fun withRandomResultHeader(): HttpResponse {
        return this.copy(headers = this.headers.plus(SPECMATIC_TYPE_HEADER to "random"))
    }

    fun withoutSpecmaticResultHeader(): HttpResponse {
        return this.copy(headers = this.headers.minus(SPECMATIC_RESULT_HEADER))
    }

    companion object {
        val ERROR_400 = HttpResponse(400, "This request did not match any scenario.", emptyMap())
        val OK = HttpResponse(200, emptyMap())
        fun ok(body: Number): HttpResponse {
            val bodyValue = NumberValue(body)
            return HttpResponse(200, mapOf(CONTENT_TYPE to bodyValue.httpContentType), bodyValue)
        }

        fun ok(body: String): HttpResponse {
            val bodyValue = StringValue(body)
            return HttpResponse(200, mapOf(CONTENT_TYPE to bodyValue.httpContentType), bodyValue)
        }

        fun ok(body: Value) = HttpResponse(200, mapOf(CONTENT_TYPE to body.httpContentType), body)
        val EMPTY = HttpResponse(0, emptyMap())

        fun jsonResponse(jsonData: String?): HttpResponse {
            return HttpResponse(200, jsonData, mapOf(CONTENT_TYPE to "application/json"))
        }

        fun xmlResponse(xmlData: String?): HttpResponse {
            return HttpResponse(200, xmlData, mapOf(CONTENT_TYPE to "application/xml"))
        }

        fun from(status: Int, body: String?) = bodyToHttpResponse(body, status)

        private fun bodyToHttpResponse(body: String?, status: Int): HttpResponse {
            val contentType = "text/plain" // Default content type when not specified
            val bodyValue = parsedValue(body, contentType)
            return HttpResponse(status, mutableMapOf(CONTENT_TYPE to bodyValue.httpContentType), bodyValue)
        }

        fun fromJSON(jsonObject: Map<String, Value>): HttpResponse {
            FuzzyExampleJsonValidator.matchesResponse(jsonObject).throwOnFailure()
            return fromJSONLenient(jsonObject)
        }

        fun fromJSONLenient(jsonObject: Map<String, Value>): HttpResponse {
            val status = getIntOrNull("status", jsonObject)
            val headers = getJSONObjectValueOrNull("headers", jsonObject)
            val command = getStringOrNull("externalisedResponseCommand", jsonObject)
            val rawBody = jsonObject["body"]

            return HttpResponse(
                status = status ?: 0,
                headers = headers.orEmpty().mapValues { it.value.toUnformattedString() },
                body = if (rawBody is NullValue) NoBodyValue else (rawBody ?: NoBodyValue),
                externalisedResponseCommand = command.orEmpty()
            ).adjustPayloadForContentType()
        }
    }

    fun adjustPayloadForContentType(): HttpResponse {
        return adjustPayloadForContentType(this.headers)
    }

    fun adjustPayloadForContentType(requestHeaders: Map<String, String> = emptyMap()): HttpResponse {
        if (!isXML(headers) && !isXML(requestHeaders)) {
            return this
        }

        val parsedBody = body as? XMLNode ?: runCatching { toXMLNode(body.toStringLiteral()) }.getOrDefault(body)
        return copy(body = parsedBody.adjustValueForXMLContentType())
    }

    fun dropIrrelevantHeaders(): HttpResponse = withoutTransportHeaders().withoutConversionHeaders().withoutSpecmaticHeaders()

    fun withoutTransportHeaders(): HttpResponse = copy(headers = headers.withoutTransportHeaders())

    fun withoutSpecmaticHeaders(): HttpResponse = copy(headers = dropSpecmaticHeaders(headers))

    fun withoutConversionHeaders(): HttpResponse = copy(headers = dropConversionExcludedHeaders(headers))

    fun isNotEmpty(): Boolean {
        val bodyIsEmpty = body == NoBodyValue
        val headersIsEmpty = headers.isEmpty() ||  headersHasOnlyTextPlainContentTypeHeader()

        val responseIsEmpty = bodyIsEmpty && headersIsEmpty

        return !responseIsEmpty
    }

    private fun headersHasOnlyTextPlainContentTypeHeader() = headers.size == 1 && headers[CONTENT_TYPE] == "text/plain"

    fun checkIfAllRootLevelKeysAreAttributeSelected(
        attributeSelectedFields: Set<String>,
        resolver: Resolver
    ): Result {
        if (body !is JSONComposite) return Result.Success()

        return body.checkIfAllRootLevelKeysAreAttributeSelected(
            attributeSelectedFields,
            resolver
        ).breadCrumb("RESPONSE.BODY")
    }
}

fun toGherkinClauses(
    response: HttpResponse,
    types: Map<String, Pattern> = emptyMap()
): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
    return try {
        return Triple(
            emptyList<GherkinClause>(),
            types,
            DiscardExampleDeclarations()
        ).let { (clauses, types, examples) ->
            val status = when {
                response.status > 0 -> response.status
                else -> throw ContractException("Can't generate a contract without a response status")
            }
            Triple(clauses.plus(GherkinClause("status $status", Then)), types, examples)
        }.let { (clauses, types, _) ->
            val (contentTypeEntry, restHeaders) = partitionOnContentType(response.headers)
            val contentTypHeaderClause = contentTypeEntry?.let { (key, value) ->
                val contentType = value.split(";").firstOrNull()

                if (contentType == null) {
                    if (value.isBlank()) {
                        logger.log("WARNING: Content-Type header for ${response.status} response is blank")
                    } else {
                        logger.log("WARNING: Could not parse content type from header value '$value'")
                    }

                    return@let null
                }

                listOf(GherkinClause("response-header $key $contentType", Then))
            }.orEmpty()

            val (newClauses, newTypes, _) = headersToGherkin(
                restHeaders,
                "response-header",
                types,
                DiscardExampleDeclarations(),
                Then
            )
            Triple(clauses.plus(newClauses).plus(contentTypHeaderClause), newTypes, DiscardExampleDeclarations())
        }.let { (clauses, types, examples) ->
            when (val result = responseBodyToGherkinClauses("ResponseBody", guessType(response.body), types)) {
                null -> Triple(clauses, types, examples)
                else -> {
                    val (newClauses, newTypes, _) = result
                    Triple(clauses.plus(newClauses), newTypes, DiscardExampleDeclarations())
                }
            }
        }
    } catch (e: NotImplementedError) {
        Triple(emptyList(), types, DiscardExampleDeclarations())
    }
}

fun dropConversionExcludedHeaders(headers: Map<String, String>): Map<String, String> {
    val headersToExcludeFromConversion =
        listOf(
            HttpHeaders.ContentDisposition,
            HttpHeaders.ContentEncoding,
            HttpHeaders.Vary,
        )
    return headers.minusIgnoringCase(headersToExcludeFromConversion).filterKeys { !it.startsWith("Access-Control-") }
}

fun dropSpecmaticHeaders(headers: Map<String, String>): Map<String, String> {
    return headers.filterKeys { !it.contains(APPLICATION_NAME, ignoreCase = true) }
}

fun <T> partitionOnContentType(headers: Map<String, T>): Pair<Map.Entry<String, T>?, Map<String, T>> {
    val contentTypeEntry = headers.entries.find { it.key.equals(CONTENT_TYPE, ignoreCase = true) }
    if (contentTypeEntry == null) return null to headers
    return contentTypeEntry to headers.minus(contentTypeEntry.key)
}

fun <T> Map<String, T>.minusIgnoringCase(keys: Iterable<String>): Map<String, T> {
    val caseInsensitiveKeys = keys.map(String::lowercase).toSet()
    return this.filterKeys { it.lowercase() !in caseInsensitiveKeys }
}
