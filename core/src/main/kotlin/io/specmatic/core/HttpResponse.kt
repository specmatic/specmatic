package io.specmatic.core

import io.ktor.http.*
import io.specmatic.conversions.guessType
import io.specmatic.core.GherkinSection.Then
import io.specmatic.core.filters.caseInsensitiveContains
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.*

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
    ) : this(status, headers, body?.let { parsedValue(it) } ?: EmptyString)

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

    fun withoutSpecmaticHeaders(): HttpResponse {
        val withoutSpecmaticHeaders = this.headers.filterNot {
            it.key.startsWith(SPECMATIC_HEADER_PREFIX)
        }

        return this.copy(headers = withoutSpecmaticHeaders)
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
            val bodyValue = parsedValue(body)
            return HttpResponse(status, mutableMapOf(CONTENT_TYPE to bodyValue.httpContentType), bodyValue)
        }

        fun fromJSON(jsonObject: Map<String, Value>): HttpResponse {
            val body = jsonObject["body"]
            if (body is NullValue)
                throw ContractException("Either body should have a value or the key should be absent from http-response")

            return HttpResponse(
                nativeInteger(jsonObject, "status")
                    ?: throw ContractException("http-response must contain a key named status, whose value is the http status in the response"),
                nativeStringStringMap(jsonObject, "headers").toMutableMap(),
                jsonObject.getOrDefault("body", NoBodyValue),
                jsonObject.getOrDefault("externalisedResponseCommand", "").toString()
            )
        }
    }

    fun withoutDynamicHeaders(): HttpResponse = copy(headers = headers.withoutDynamicHeaders())

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

fun isVanillaPatternToken(token: String) = isPatternToken(token) && token.indexOf(':') < 0

fun nativeInteger(json: Map<String, Value>, key: String): Int? {
    val keyValue = json[key] ?: return null

    val errorMessage = "$key must be an integer"
    if (keyValue is StringValue)
        return try {
            keyValue.string.toInt()
        } catch (e: Throwable) {
            throw ContractException(errorMessage)
        }

    if (keyValue !is NumberValue)
        throw ContractException("Expected $key to be a string value")

    return try {
        keyValue.number.toInt()
    } catch (e: Throwable) {
        throw ContractException(errorMessage)
    }
}


val responseHeadersToExcludeFromConversion = listOf("Vary", SPECMATIC_RESULT_HEADER)

fun toGherkinClauses(
    response: HttpResponse,
    types: Map<String, Pattern> = emptyMap()
): Triple<List<GherkinClause>, Map<String, Pattern>, ExampleDeclarations> {
    return try {
        val cleanedUpResponse = dropContentAndCORSResponseHeaders(response)

        return Triple(
            emptyList<GherkinClause>(),
            types,
            DiscardExampleDeclarations()
        ).let { (clauses, types, examples) ->
            val status = when {
                cleanedUpResponse.status > 0 -> cleanedUpResponse.status
                else -> throw ContractException("Can't generate a contract without a response status")
            }
            Triple(clauses.plus(GherkinClause("status $status", Then)), types, examples)
        }.let { (clauses, types, _) ->
            val contentTypeHeader: List<GherkinClause> = response.headers.entries.find {
                it.key.equals(CONTENT_TYPE, ignoreCase = true)
            }?.let {
                val contentType = it.value.split(";")[0]
                listOf(GherkinClause("response-header ${it.key} $contentType", Then))
            } ?: emptyList()

            val (newClauses, newTypes, _) = headersToGherkin(
                cleanedUpResponse.headers,
                "response-header",
                types,
                DiscardExampleDeclarations(),
                Then
            )
            Triple(clauses.plus(newClauses).plus(contentTypeHeader), newTypes, DiscardExampleDeclarations())
        }.let { (clauses, types, examples) ->
            when (val result = responseBodyToGherkinClauses("ResponseBody", guessType(cleanedUpResponse.body), types)) {
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

fun dropContentAndCORSResponseHeaders(response: HttpResponse) =
    response.copy(headers = response.headers.filterNot {
        it.key in responseHeadersToExcludeFromConversion || it.key.lowercase() in listOf("content-type", "content-encoding", "content-length", "content-disposition") || it.key.startsWith("Access-Control-")
    })
