package io.specmatic.mock

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.*
import io.specmatic.stub.stringToMockScenario
import java.io.File

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
    val requestBodyRegex: String? = null,
    val data: JSONObjectValue = JSONObjectValue(),
    val filePath: String? = null,
    val partial: ScenarioStub? = null
) {
    fun requestMethod() = request.method ?: partial?.request?.method

    fun requestPath() = request.path ?: partial?.request?.path

    fun responseStatus() = response.status.takeIf { it != 0 } ?: partial?.response?.status

    fun requestElsePartialRequest() = partial?.request ?: request

    fun responseElsePartialResponse() = partial?.response ?: response

    fun response() = getHttpResponse()

    fun isPartial() = partial != null

    fun isInvalid(): Boolean = requestMethod() == null || requestPath() == null || responseStatus() == null

    val name: String? = this.data.jsonObject["name"]?.toStringLiteral() ?: partial?.name

    fun toJSON(): JSONObjectValue {
        val requestResponse: Map<String, Value> =
            if (partial != null) {
                mapOf(
                    PARTIAL to JSONObjectValue(serializeRequestResponse(partial))
                )
            } else {
                serializeRequestResponse(this)
            }

        return JSONObjectValue(requestResponse + data.jsonObject)
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
        fun readFromFile(file: File): ScenarioStub {
            return attempt(
                breadCrumb = file.path,
                errorMessage = "Error loading example due to invalid format. Please correct the format to proceed"
            ) {
                parse(file.readText(Charsets.UTF_8)).copy(filePath = file.path)
            }
        }

        fun parse(text: String): ScenarioStub {
            return parse(StringValue(text))
        }

        fun parse(json: Value): ScenarioStub {
            return stringToMockScenario(json)
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

val MOCK_HTTP_REQUEST_ALL_KEYS = listOf("mock-http-request", MOCK_HTTP_REQUEST)
val MOCK_HTTP_RESPONSE_ALL_KEYS = listOf("mock-http-response", MOCK_HTTP_RESPONSE)

const val PARTIAL = "partial"

fun validateMock(mockSpec: Map<String, Any?>) {
    if(mockSpec.containsKey(PARTIAL)) {
        val template = mockSpec.getValue(PARTIAL) as? JSONObjectValue ?: throw ContractException("template should be an object")
        return validateMock(template.jsonObject)
    }

    if (MOCK_HTTP_REQUEST_ALL_KEYS.none { mockSpec.containsKey(it) })
        throw ContractException(errorMessage = "Example should contain http-request/mock-http-request as a top level key.")
    if (MOCK_HTTP_RESPONSE_ALL_KEYS.none { mockSpec.containsKey(it) })
        throw ContractException(errorMessage = "Example should contain http-response/mock-http-response as a top level key.")
}

fun mockFromJSON(mockSpec: Map<String, Value>): ScenarioStub {
    val data = JSONObjectValue(mockSpec.filterKeys { it !in (MOCK_HTTP_REQUEST_ALL_KEYS + MOCK_HTTP_RESPONSE_ALL_KEYS).plus(PARTIAL) })

    if(PARTIAL in mockSpec) {
        val template = mockSpec.getValue(PARTIAL) as? JSONObjectValue ?: throw ContractException("template key must be an object")

        return ScenarioStub(data = data, partial = mockFromJSON(template.jsonObject))
    }

    val mockRequest: HttpRequest = requestFromJSON(getJSONObjectValue(MOCK_HTTP_REQUEST_ALL_KEYS, mockSpec))
    val mockResponse: HttpResponse = HttpResponse.fromJSON(getJSONObjectValue(MOCK_HTTP_RESPONSE_ALL_KEYS, mockSpec))

    val delayInSeconds: Int? = getIntOrNull(DELAY_IN_SECONDS, mockSpec)
    val delayInMilliseconds: Long? = getLongOrNull(DELAY_IN_MILLISECONDS, mockSpec)
    val delayInMs: Long? = delayInMilliseconds ?: delayInSeconds?.toLong()?.times(1000)

    val stubToken: String? = getStringOrNull(TRANSIENT_MOCK_ID, mockSpec)
    val requestBodyRegex: String? = getRequestBodyRegexOrNull(mockSpec)

    return ScenarioStub(
        request = mockRequest,
        response = mockResponse,
        delayInMilliseconds = delayInMs,
        stubToken = stubToken,
        requestBodyRegex = requestBodyRegex,
        data = data
    )
}

fun getRequestBodyRegexOrNull(mockSpec: Map<String, Value>): String? {
    val requestSpec: Map<String, Value> = getJSONObjectValue(MOCK_HTTP_REQUEST_ALL_KEYS, mockSpec)
    return requestSpec[REQUEST_BODY_REGEX]?.toStringLiteral()
}

fun getJSONObjectValue(keys: List<String>, mapData: Map<String, Value>): Map<String, Value> {
    val key = keys.first { mapData.containsKey(it) }
    return getJSONObjectValue(key, mapData)
}

fun getJSONObjectValue(key: String, mapData: Map<String, Value>): Map<String, Value> {
    val data = mapData.getValue(key)
    if(data !is JSONObjectValue) throw ContractException("$key should be a json object")
    return data.jsonObject
}

fun getIntOrNull(key: String, mapData: Map<String, Value>): Int? {
    val data = mapData[key]

    return data?.let {
        if(data !is NumberValue) throw ContractException("$key should be a number")
        return data.number.toInt()
    }
}

fun getLongOrNull(key: String, mapData: Map<String, Value>): Long? {
    val data = mapData[key]

    return data?.let {
        if(data !is NumberValue) throw ContractException("$key should be a number")
        return data.number.toLong()
    }
}

fun getStringOrNull(key: String, mapData: Map<String, Value>): String? {
    val data = mapData[key]

    return data?.let {
        if(data !is StringValue) throw ContractException("$key should be a number")
        return data.string
    }
}

