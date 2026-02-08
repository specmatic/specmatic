package io.specmatic.mock

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.value.*
import java.io.File
import java.util.UUID

fun loadDictionary(fileName: String): Map<String,Value> {
    return try {
        parsedJSONObject(File(fileName).readText()).jsonObject
    } catch(e: Throwable) {
        throw ContractException("Error loading $fileName: ${exceptionCauseMessage(e)}")
    }
}

// move elsewhere
data class AdditionalExampleParams(
    val headers: Map<String, String>
)

data class ScenarioStub(
    val request: HttpRequest = HttpRequest(),
    val response: HttpResponse = HttpResponse(0, emptyMap()),
    val delayInMilliseconds: Long? = null,
    val stubToken: String? = null,
    val requestBodyRegex: Regex? = null,
    val data: JSONObjectValue = JSONObjectValue(),
    val filePath: String? = null,
    val partial: ScenarioStub? = null,
    val rawJsonData: JSONObjectValue = JSONObjectValue(),
    val validationErrors: Result = Result.Success(),
    val strictMode: Boolean = true,
    val id: String? = null
) {
    init {
        if (strictMode && !validationErrors.isSuccess()) validationErrors.throwOnFailure()
    }

    val protocol = request.protocol

    fun requestMethod() = request.method ?: partial?.request?.method

    fun requestPath() = request.path ?: partial?.request?.path

    fun responseStatus() = response.status.takeIf { it != 0 } ?: partial?.response?.status

    fun requestElsePartialRequest() = partial?.request ?: request

    fun responseElsePartialResponse() = partial?.response ?: response

    fun response() = getHttpResponse()

    fun isPartial() = partial != null

    fun isInvalid(): Boolean = requestMethod() == null || requestPath() == null || responseStatus() == null

    val name: String? = this.data.jsonObject["name"]?.toStringLiteral() ?: partial?.name

    val nameOrFileName: String? = runCatching { name ?: filePath?.let(::File)?.nameWithoutExtension }.getOrNull()

    fun toJSON(): JSONObjectValue {
        val requestResponse: Map<String, Value> =
            if (partial != null) {
                mapOf(
                    PARTIAL to JSONObjectValue(serializeRequestResponse(partial))
                )
            } else {
                serializeRequestResponse(this)
            }

        return JSONObjectValue(data.jsonObject + requestResponse)
    }

    private fun serializeRequestResponse(scenarioStub: ScenarioStub): Map<String, Value> {
        return mapOf(
            MOCK_HTTP_REQUEST to scenarioStub.request.toJSON(),
            MOCK_HTTP_RESPONSE to scenarioStub.response.toJSON()
        )
    }

    private fun getHttpResponse(): HttpResponse {
        return this.partial?.response ?: this.response
    }

    fun updateRequest(request: HttpRequest): ScenarioStub {
        if (partial != null) {
            return this.copy(partial = this.partial.updateRequest(request))
        }

        return this.copy(request = request)
    }

    fun updateResponse(response: HttpResponse): ScenarioStub {
        if (partial != null) {
            return this.copy(partial = this.partial.updateResponse(response))
        }

        return this.copy(response = response)
    }

    fun getRequestWithAdditionalParamsIfAny(additionalExampleParamsFilePath: String?): HttpRequest {
        val request = requestElsePartialRequest()

        if (additionalExampleParamsFilePath == null)
            return request

        val additionalExampleParamsFile = File(additionalExampleParamsFilePath)

        if (!additionalExampleParamsFile.exists() || !additionalExampleParamsFile.isFile) {
            return request
        }

        try {
            val additionalExampleParams = ObjectMapper().readValue(
                File(additionalExampleParamsFilePath).readText(),
                Map::class.java
            ) as? Map<String, Any>

            if (additionalExampleParams == null) {
                logger.log("WARNING: The content of $additionalExampleParamsFilePath is not a valid JSON object")
                return request
            }

            val additionalHeaders = (
                    (additionalExampleParams["headers"] as? Map<String, Any?>)?.mapValues { (_, value) ->
                        value?.toString() ?: ""
                    }
                        ?: emptyMap()
                    ) as? Map<String, String>

            if (additionalHeaders == null) {
                logger.log("WARNING: The content of \"headers\" in $additionalExampleParamsFilePath is not a valid JSON object")
                return request
            }

            val updatedHeaders = request.headers.plus(additionalHeaders)

            return request.copy(headers = updatedHeaders)
        } catch (e: Exception) {
            logger.log(e, "WARNING: Could not read additional example params file $additionalExampleParamsFilePath")
            return request
        }
    }

    fun findPatterns(input: String): Set<String> {
        val pattern = """\{\{(@\w+)\}\}""".toRegex()
        return pattern.findAll(input).map { it.groupValues[1] }.toSet()
    }

    fun dataTemplateNameOnly(wholeDataTemplateName: String): String {
        return wholeDataTemplateName.split(".").first()
    }

    fun resolveDataSubstitutions(): List<ScenarioStub> {
        return listOf(this)
    }

    private fun replaceInRequestBody(
        value: JSONObjectValue,
        substitutions: Map<String, Map<String, Map<String, Value>>>,
        requestTemplatePatterns: Map<String, Pattern>,
        resolver: Resolver
    ): Value {
        return value.copy(
            jsonObject = value.jsonObject.mapValues {
                replaceInRequestBody(it.key, it.value, substitutions, requestTemplatePatterns, resolver)
            }
        )
    }

    private fun replaceInRequestBody(
        value: JSONArrayValue,
        substitutions: Map<String, Map<String, Map<String, Value>>>,
        requestTemplatePatterns: Map<String, Pattern>,
        resolver: Resolver
    ): Value {
        return value.copy(
            list = value.list.map {
                replaceInRequestBody(value, substitutions, requestTemplatePatterns, resolver)
            }
        )
    }

    private fun substituteStringInRequest(
        value: String,
        substitutions: Map<String, Map<String, Map<String, Value>>>
    ): String {
        return if (value.hasDataTemplate()) {
            val substitutionSetName = value.removeSurrounding("{{", "}}")
            val substitutionSet = substitutions[substitutionSetName]
                ?: throw ContractException("$substitutionSetName does not exist in the data")

            substitutionSet.keys.firstOrNull() ?: throw ContractException("$substitutionSetName in data is empty")
        } else
            value
    }

    private fun replaceInRequestBody(
        key: String,
        value: Value,
        substitutions: Map<String, Map<String, Map<String, Value>>>,
        requestTemplatePatterns: Map<String, Pattern>,
        resolver: Resolver
    ): Value {
        return when (value) {
            is StringValue -> {
                if (value.hasDataTemplate()) {
                    val substitutionSetName = value.string.removeSurrounding("{{", "}}")
                    val substitutionSet = substitutions[substitutionSetName]
                        ?: throw ContractException("$substitutionSetName does not exist in the data")

                    val substitutionKey = substitutionSet.keys.firstOrNull()
                        ?: throw ContractException("$substitutionSetName in data is empty")

                    val pattern = requestTemplatePatterns.getValue(key)

                    pattern.parse(substitutionKey, resolver)
                } else
                    value
            }

            is JSONObjectValue -> {
                replaceInRequestBody(value, substitutions, requestTemplatePatterns, resolver)
            }

            is JSONArrayValue -> {
                replaceInRequestBody(value, substitutions, requestTemplatePatterns, resolver)
            }

            else -> value
        }
    }

    private fun replaceInPath(path: String, substitutions: Map<String, Map<String, Map<String, Value>>>): String {
        val rawPathSegments = path.split("/")
        val pathSegments = rawPathSegments.let { if (it.firstOrNull() == "") it.drop(1) else it }
        val updatedSegments =
            pathSegments.map { if (it.hasDataTemplate()) substituteStringInRequest(it, substitutions) else it }
        val prefix = if (pathSegments.size != rawPathSegments.size) listOf("") else emptyList()

        return (prefix + updatedSegments).joinToString("/")
    }

    private fun replaceInResponseHeaders(
        headers: Map<String, String>,
        substitutions: Map<String, Map<String, Map<String, Value>>>
    ): Map<String, String> {
        return headers.mapValues { (key, value) ->
            substituteStringInResponse(value, substitutions, key)
        }
    }

    private fun replaceInRequestQueryParams(
        queryParams: QueryParameters,
        substitutions: Map<String, Map<String, Map<String, Value>>>
    ): Map<String, String> {
        return queryParams.asMap().mapValues { (key, value) ->
            substituteStringInRequest(value, substitutions)
        }
    }

    private fun replaceInRequestHeaders(
        headers: Map<String, String>,
        substitutions: Map<String, Map<String, Map<String, Value>>>
    ): Map<String, String> {
        return headers.mapValues { (key, value) ->
            substituteStringInRequest(value, substitutions)
        }
    }

    private fun replaceInResponseBody(
        value: JSONObjectValue,
        substitutions: Map<String, Map<String, Map<String, Value>>>
    ): Value {
        return value.copy(
            jsonObject = value.jsonObject.mapValues {
                replaceInResponseBody(it.value, substitutions, it.key)
            }
        )
    }

    private fun replaceInResponseBody(
        value: JSONArrayValue,
        substitutions: Map<String, Map<String, Map<String, Value>>>
    ): Value {
        return value.copy(
            list = value.list.map { item: Value ->
                replaceInResponseBody(item, substitutions, "")
            }
        )
    }

    private fun substituteStringInResponse(
        value: String,
        substitutions: Map<String, Map<String, Map<String, Value>>>,
        key: String
    ): String {
        return if (value.hasDataTemplate()) {
            val dataSetIdentifiers = DataSetIdentifiers(value, key)

            val substitutionSet = substitutions[dataSetIdentifiers.name]
                ?: throw ContractException("${dataSetIdentifiers.name} does not exist in the data")

            val substitutionValue = substitutionSet.values.first()[dataSetIdentifiers.key]
                ?: throw ContractException("${dataSetIdentifiers.name} does not contain a value for ${dataSetIdentifiers.key}")

            substitutionValue.toStringLiteral()
        } else
            value
    }

    class DataSetIdentifiers(rawSetName: String, objectKey: String) {
        val name: String
        val key: String

        init {
            val substitutionSetPieces = rawSetName.removeSurrounding("{{", "}}").split(".")

            name = substitutionSetPieces.getOrNull(0) ?: throw ContractException("Substitution set name {{}} was empty")
            key = substitutionSetPieces.getOrNull(1) ?: objectKey
        }
    }

    private fun replaceInResponseBody(
        value: Value,
        substitutions: Map<String, Map<String, Map<String, Value>>>,
        key: String
    ): Value {
        return when (value) {
            is StringValue -> {
                if (value.hasDataTemplate()) {
                    val dataSetIdentifiers = DataSetIdentifiers(value.string, key)

                    val substitutionSet = substitutions[dataSetIdentifiers.name]
                        ?: throw ContractException("${dataSetIdentifiers.name} does not exist in the data")

                    val substitutionValue = substitutionSet.values.first()[dataSetIdentifiers.key]
                        ?: throw ContractException("${dataSetIdentifiers.name} does not contain a value for ${dataSetIdentifiers.key}")

                    substitutionValue
                } else
                    value
            }

            is JSONObjectValue -> {
                replaceInResponseBody(value, substitutions)
            }

            is JSONArrayValue -> {
                replaceInResponseBody(value, substitutions)
            }

            else -> value
        }
    }

    companion object {
        fun readFromFile(file: File, strictMode: Boolean = true): ScenarioStub {
            return parse(file.readText(Charsets.UTF_8), strictMode).copy(filePath = file.path)
        }

        fun parse(text: String, strictMode: Boolean = true): ScenarioStub {
            return parse(StringValue(text), strictMode)
        }

        fun parse(json: Value, strictMode: Boolean = true): ScenarioStub {
            val parsedJson = jsonStringToValueMap(json.toStringLiteral())
            return mockFromJSON(parsedJson, strictMode)
        }
    }
}

const val MOCK_HTTP_REQUEST = "http-request"
const val MOCK_HTTP_RESPONSE = "http-response"
const val DELAY_IN_SECONDS = "delay-in-seconds"
const val DELAY_IN_MILLISECONDS = "delay-in-milliseconds"
const val TRANSIENT_MOCK = "http-stub"
const val TRANSIENT_MOCK_ID = "$TRANSIENT_MOCK-id"
const val REQUEST_BODY_REGEX = "bodyRegex"
const val IS_TRANSIENT_MOCK = "transient"
const val PARTIAL = "partial"
const val ID = "id"
private val nonMetadataDataKeys = listOf(MOCK_HTTP_REQUEST, MOCK_HTTP_RESPONSE, PARTIAL)

fun mockFromJSON(mockSpec: Map<String, Value>, strictMode: Boolean = true): ScenarioStub {
    val validationResult = FuzzyExampleJsonValidator.matches(mockSpec)
    val data = mockSpec.filterKeys { it !in nonMetadataDataKeys }
    return if (PARTIAL in mockSpec) {
        parsePartialExample(mockSpec, data, validationResult, strictMode)
    } else {
        parseStandardExample(mockSpec, data, validationResult, strictMode)
    }
}

private fun parsePartialExample(mockSpec: Map<String, Value>, data: Map<String, Value>, validationResult: Result, strictMode: Boolean): ScenarioStub {
    val template = mockSpec[PARTIAL] as? JSONObjectValue ?: JSONObjectValue()
    val parsedPartial = mockFromJSON(template.jsonObject.plus(data), strictMode)
    return parsedPartial.copy(
        partial = parsedPartial,
        rawJsonData = JSONObjectValue(mockSpec),
        validationErrors = validationResult,
        strictMode = strictMode
    )
}

private fun parseStandardExample(mockSpec: Map<String, Value>, data: Map<String, Value>, validationResult: Result, strictMode: Boolean): ScenarioStub {
    val mockRequest: HttpRequest = try {
        val reqMap = getJSONObjectValueOrNull(MOCK_HTTP_REQUEST, mockSpec)
        if (reqMap != null) HttpRequest.fromJSONLenient(reqMap) else HttpRequest("", "")
    } catch (e: Exception) {
        logger.debug(e, "Failed to parse $MOCK_HTTP_REQUEST")
        HttpRequest("", "")
    }

    val mockResponse: HttpResponse = try {
        val respMap = getJSONObjectValueOrNull(MOCK_HTTP_RESPONSE, mockSpec)
        if (respMap != null) HttpResponse.fromJSONLenient(respMap) else HttpResponse(0, emptyMap())
    } catch (e: Exception) {
        logger.debug(e, "Failed to parse $MOCK_HTTP_RESPONSE")
        HttpResponse(0, emptyMap())
    }

    val delayInSeconds = getIntOrNull(DELAY_IN_SECONDS, mockSpec)
    val delayInMilliseconds = getLongOrNull(DELAY_IN_MILLISECONDS, mockSpec)
    val delayInMs = delayInMilliseconds ?: delayInSeconds?.toLong()?.times(1000)

    val explicitStubToken = getStringOrNull(TRANSIENT_MOCK_ID, mockSpec)
    val isTransientMock = getBooleanOrNull(IS_TRANSIENT_MOCK, mockSpec) ?: false
    val stubToken = explicitStubToken ?: if (isTransientMock) UUID.randomUUID().toString() else null
    val requestBodyRegex = getRequestBodyRegexOrNull(mockSpec)
    val id = getStringOrNull(ID, mockSpec)

    return ScenarioStub(
        request = mockRequest,
        response = mockResponse,
        delayInMilliseconds = delayInMs,
        stubToken = stubToken,
        requestBodyRegex = requestBodyRegex,
        data = JSONObjectValue(data),
        rawJsonData = JSONObjectValue(mockSpec),
        validationErrors = validationResult,
        strictMode = strictMode,
        id = id
    )
}

fun getRequestBodyRegexOrNull(mockSpec: Map<String, Value>): Regex? {
    return try {
        val requestSpec = getJSONObjectValueOrNull(MOCK_HTTP_REQUEST, mockSpec)
        val regexString = requestSpec?.get(REQUEST_BODY_REGEX)?.toStringLiteral()
        parseRegex(regexString)
    } catch (e: Exception) {
        logger.debug(e, "Failed to parse $REQUEST_BODY_REGEX")
        null
    }
}

private fun parseRegex(regex: String?): Regex? {
    return regex?.let {
        try {
            Regex(it)
        } catch (e: Throwable) {
            throw ContractException("Couldn't parse regex $regex", exceptionCause = e)
        }
    }
}

fun getJSONObjectValueOrNull(key: String, mapData: Map<String, Value>): Map<String, Value>? {
    return (mapData[key] as? JSONObjectValue)?.jsonObject
}

fun getArrayOrNull(key: String, mapData: Map<String, Value>): List<Value>? {
    return (mapData[key] as? JSONArrayValue)?.list
}

fun getObjectListOrNull(key: String, mapData: Map<String, Value>): List<Map<String, Value>>? {
    return getArrayOrNull(key, mapData)?.mapNotNull { (it as? JSONObjectValue)?.jsonObject }
}

fun getIntOrNull(key: String, mapData: Map<String, Value>): Int? {
    return (mapData[key] as? NumberValue)?.number?.toInt()
}

fun getLongOrNull(key: String, mapData: Map<String, Value>): Long? {
    return (mapData[key] as? NumberValue)?.number?.toLong()
}

fun getStringOrNull(key: String, mapData: Map<String, Value>): String? {
    return (mapData[key] as? StringValue)?.string
}

fun getBooleanOrNull(key: String, mapData: Map<String, Value>): Boolean? {
    return (mapData[key] as? BooleanValue)?.booleanValue
}
