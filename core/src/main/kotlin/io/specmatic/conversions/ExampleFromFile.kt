package io.specmatic.conversions

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.examples.preprocessor.PreProcessorAttributes
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.log.LogStrategy
import io.specmatic.core.pattern.*
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import java.io.File
import java.net.URI

class ExampleFromFile(private val scenarioStub: ScenarioStub, val file: File) {
    companion object {
        fun fromFile(file: File, strictMode: Boolean = true): ReturnValue<ExampleFromFile> {
            if (SchemaExample.matchesFilePattern(file)) {
                return HasFailure("Skipping file ${file.canonicalPath}, because it contains schema-based example")
            }

            return runCatching {
                ExampleFromFile(file, strictMode)
            }.map(::HasValue).getOrElse(::HasException)
        }
    }

    constructor(file: File, strictMode: Boolean = true): this(scenarioStub = ScenarioStub.readFromFile(file, strictMode), file = file)
    constructor(json: JSONObjectValue, file: File, strictMode: Boolean = true): this(scenarioStub = ScenarioStub.parse(json, strictMode, file.path), file = file)
    constructor(scenarioStub: ScenarioStub) : this(scenarioStub, File(scenarioStub.filePath.orEmpty()))

    fun toRow(specmaticConfig: SpecmaticConfig = SpecmaticConfig(), logger: LogStrategy = io.specmatic.core.log.logger): Row {
        logger.log("Loading test file ${this.expectationFilePath}")

        val normalizedHeaders = request.headers.mapKeys { (key, _) ->
            if (key.equals("SOAPAction", ignoreCase = true)) "SOAPAction" else key
        }

        val examples: Map<String, String> = normalizedHeaders
            .plus(queryParams)
            .plus(requestBody?.let { mapOf("(REQUEST-BODY)" to it.toStringLiteral()) } ?: emptyMap())

        val (columnNames, values) = examples.entries.let { entry ->
            entry.map { it.key } to entry.map { it.value }
        }

        return Row(
            columnNames,
            values,
            name = testName,
            fileSource = this.file.canonicalPath,
            requestExample = scenarioStub.getRequestWithAdditionalParamsIfAny(specmaticConfig.getAdditionalExampleParamsFilePath()),
            responseExample = response,
            isPartial = scenarioStub.partial != null,
            scenarioStub = scenarioStub
        )
    }

    val json: JSONObjectValue = scenarioStub.rawJsonData
    val validationErrors: Result = scenarioStub.validationErrors
    val expectationFilePath: String = file.canonicalPath
    @Suppress("MemberVisibilityCanBePrivate") // Used in openapi-module
    val testName: String = scenarioStub.name ?: file.nameWithoutExtension
    @Suppress("MemberVisibilityCanBePrivate", "unused") // Used in openapi-module
    val preProcessorAttributes: PreProcessorAttributes = scenarioStub.preProcessorAttributes

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
        return this.asSequence().find { it.key.equals(key, ignoreCase = true) }?.value
    }
}
