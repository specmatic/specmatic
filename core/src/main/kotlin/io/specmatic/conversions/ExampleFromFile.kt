package io.specmatic.conversions

import io.ktor.http.decodeURLPart
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.NoBodyValue
import io.specmatic.core.QueryParameters
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ResponseExample
import io.specmatic.core.pattern.ResponseValueExample
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.attempt
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.URIUtils.parseQuery
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.mock.mockFromJSON
import io.specmatic.test.ExampleProcessor
import java.io.File
import java.net.URI

class ExampleFromFile(val json: JSONObjectValue, val file: File) {
    companion object {
        fun fromFile(file: File): ReturnValue<ExampleFromFile> {
            if (SchemaExample.matchesFilePattern(file)) {
                return HasFailure("Skipping file ${file.canonicalPath}, because it contains schema-based example")
            }
            return HasValue(ExampleFromFile(file))
        }
    }

    fun toRow(specmaticConfig: SpecmaticConfig = SpecmaticConfig()): Row {
        logger.log("Loading test file ${this.expectationFilePath}")

        val examples: Map<String, String> = headers
            .plus(queryParams)
            .plus(requestBody?.let { mapOf("(REQUEST-BODY)" to it.toStringLiteral()) } ?: emptyMap())

        val (columnNames, values) = examples.entries.let { entry ->
            entry.map { it.key } to entry.map { it.value }
        }

        val responseExample: ResponseExample? = response.let { httpResponse ->
            when {
                specmaticConfig.isResponseValueValidationEnabled() -> ResponseValueExample(httpResponse)
                else -> null
            }
        }
        val scenarioStub = mockFromJSON(json.jsonObject)

        return Row(
            columnNames,
            values,
            name = testName,
            fileSource = this.file.canonicalPath,
            exactResponseExample = responseExample,
            responseExampleForAssertion = response,
            requestExample = scenarioStub.getRequestWithAdditionalParamsIfAny(specmaticConfig.getAdditionalExampleParamsFilePath()),
            responseExample = response,
            isPartial = scenarioStub.partial != null
        ).let { ExampleProcessor.resolve(it, ExampleProcessor::ifNotExitsToLookupPattern) }
    }

    constructor(file: File) : this(json = attempt("Error reading example file ${file.canonicalPath}") { parsedJSONObject(file.readText()) }, file = file)

    private fun JSONObjectValue.findByPath(path: String): Value? {
        return  findFirstChildByPath("partial.$path") ?: findFirstChildByPath(path)
    }

    fun isPartial(): Boolean {
        return json.findFirstChildByPath("partial") != null
    }

    fun isInvalid(): Boolean {
        return (requestMethod == null || requestPath == null || responseStatus == null)
    }

    val expectationFilePath: String = file.canonicalPath

    val response: HttpResponse
        get() {
            if(responseBody == null && responseHeaders == null)
                return HttpResponse(
                    responseStatus ?: 0,
                    body = NoBodyValue,
                    headers = emptyMap()
                )

            val body = responseBody ?: EmptyString
            val headers = responseHeaders ?: JSONObjectValue()

            return HttpResponse(responseStatus ?: 0, headers.jsonObject.mapValues { it.value.toStringLiteral() }, body)
        }

    val request: HttpRequest
        get() {
            return HttpRequest(
                method = requestMethod,
                path = requestPath,
                headers = headers,
                body = requestBody ?: NoBodyValue,
                queryParams = QueryParameters(queryParams),
            )
        }

    val responseBody: Value? = attempt("Error reading response body in file ${file.canonicalPath}") {
        json.findByPath("http-response.body")
    }

    val responseHeaders: JSONObjectValue? = attempt("Error reading response headers in file ${file.canonicalPath}") {
        val headers = json.findByPath("http-response.headers") ?: return@attempt null

        if(headers !is JSONObjectValue)
            return@attempt null

        headers
    }

    val responseStatus: Int? = attempt("Error reading status in file ${file.canonicalPath}") {
        json.findByPath("http-response.status")?.toStringLiteral()?.toInt()
    }

    val requestMethod: String? = attempt("Error reading method in file ${file.canonicalPath}") {
        json.findByPath("http-request.method")?.toStringLiteral()
    }

    val requestContentType: String?
        get() {
            val rawContentType = headers.filter { it.key.lowercase() == "content-type" }.values.firstOrNull()
            return rawContentType?.split(";")?.firstOrNull()
        }

    private val rawPath: String? =
        json.findByPath("http-request.path")?.toStringLiteral()?.decodeURLPart()

    val requestPath: String? = attempt("Error reading path in file ${file.canonicalPath}") {
        rawPath?.let { pathOnly(it) }
    }

    private fun pathOnly(requestPath: String): String {
        return URI(requestPath).path ?: ""
    }

    val testName: String = attempt("Error reading expectation name in file ${file.canonicalPath}") {
        json.findByPath("name")?.toStringLiteral() ?: file.nameWithoutExtension
    }

    val queryParams: Map<String, String>
        get() {
            val path = attempt("Error reading path in file ${file.canonicalPath}") {
                rawPath ?: ""
            }

            val uri = URI.create(path)
            val queryParamsFromURL = parseQuery(uri.query)

            val queryParamsFromJSONBlock = attempt("Error reading query params in file ${file.canonicalPath}") {
                (json.findByPath("http-request.query") as JSONObjectValue?)?.jsonObject?.mapValues { (_, value) ->
                    value.toStringLiteral()
                } ?: emptyMap()
            }

            return queryParamsFromURL + queryParamsFromJSONBlock
        }

    val headers: Map<String, String> = attempt("Error reading headers in file ${file.canonicalPath}") {
        (json.findByPath("http-request.headers") as JSONObjectValue?)?.jsonObject?.mapValues { (_, value) ->
            value.toStringLiteral()
        } ?: emptyMap()
    }

    val requestBody: Value? = attempt("Error reading request body in file ${file.canonicalPath}") {
        json.findByPath("http-request.body")
    }

    val responseContentType: String?
        get() {
            return responseHeaders?.let {
                it.jsonObject.filter { entry -> entry.key.lowercase() == "content-type" }.values.firstOrNull()?.toStringLiteral()
            }
        }
}
