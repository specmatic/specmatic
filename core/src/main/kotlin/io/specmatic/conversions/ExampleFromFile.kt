package io.specmatic.conversions

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.test.ExampleProcessor
import java.io.File
import java.net.URI

class ExampleFromFile(private val scenarioStub: ScenarioStub, val json: JSONObjectValue, val file: File) {

    companion object {
        fun fromFile(file: File): ReturnValue<ExampleFromFile> {
            if (SchemaExample.matchesFilePattern(file)) {
                return HasFailure("Skipping file ${file.canonicalPath}, because it contains schema-based example")
            }

            return HasValue(ExampleFromFile(file))
        }
    }

    constructor(file: File): this(
        scenarioStub = ScenarioStub.readFromFile(file),
        json = readValueAs<JSONObjectValue>(file), file = file
    )

    constructor(json: JSONObjectValue, file: File): this(
        scenarioStub = ScenarioStub.parse(json),
        json = json, file = file
    )

    fun toRow(specmaticConfig: SpecmaticConfig = SpecmaticConfig()): Row {
        logger.log("Loading test file ${this.expectationFilePath}")

        val examples: Map<String, String> = request.headers
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

    val expectationFilePath: String = file.canonicalPath
    @Suppress("MemberVisibilityCanBePrivate") // Used in openapi-module
    val testName: String = scenarioStub.name ?: file.nameWithoutExtension

    val request: HttpRequest = scenarioStub.requestElsePartialRequest()
    val requestPath: String? = request.path?.let(::URI)?.path
    val requestMethod: String? = request.method
    val queryParams: Map<String, String> = request.queryParams.asValueMap().mapValues { it.value.toStringLiteral() }
    val requestContentType: String? = request.headers.getCaseInsensitive(CONTENT_TYPE)?.split(";")?.firstOrNull()
    val requestBody: Value? = request.body.takeUnless { it === EmptyString }

    val response: HttpResponse = scenarioStub.responseElsePartialResponse()
    val responseStatus: Int? = response.status.takeUnless { it == 0 }
    val responseContentType: String? = response.headers.getCaseInsensitive(CONTENT_TYPE)?.split(";")?.firstOrNull()
    val responseBody: Value? = response.body.takeUnless { it === EmptyString }

    fun isPartial(): Boolean = scenarioStub.isPartial()
    fun isInvalid(): Boolean = scenarioStub.isInvalid()
    private fun <T> Map<String, T>.getCaseInsensitive(key: String): T? {
        return this.asSequence().find { it.key.lowercase() == key.lowercase() }?.value
    }
}
