package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.utilities.parseXML
import io.specmatic.core.value.*
import io.specmatic.mock.ScenarioStub
import io.specmatic.core.log.dontPrintToConsole
import io.specmatic.test.LegacyHttpClient
import java.net.URI
import java.net.URL

fun hostAndPort(uriString: String): BaseURLInfo {
    val uri = URI.create(uriString)
    return BaseURLInfo(uri.host, uri.port, uri.scheme, uriString.removeSuffix("/"))
}

data class ImportedPostmanContracts(val name: String, val gherkin: String, val baseURLInfo: BaseURLInfo, val stubs: List<NamedStub>)

fun postmanCollectionToGherkin(postmanContent: String): List<ImportedPostmanContracts> {
    val postmanCollection = stubsFromPostmanCollection(postmanContent)

    val groups = postmanCollection.stubs.groupBy { it.first }

    return groups.entries.map { (baseURLInfo, stubInfo) ->
        val collection = PostmanCollection(postmanCollection.name, stubInfo)
        val gherkinString = toGherkinFeature(collection)

        ImportedPostmanContracts(collection.name, gherkinString, baseURLInfo, postmanCollection.stubs.map { it.second })
    }
}

fun runTests(contract: ImportedPostmanContracts) {
    val (name, gherkin, baseURLInfo, _) = contract
    logger.log("Testing contract \"$name\" with base URL ${baseURLInfo.originalBaseURL}")
    try {
        val feature = parseGherkinStringToFeature(gherkin)
        val results = feature.executeTests(LegacyHttpClient(baseURL = baseURLInfo.originalBaseURL))

        logger.log("Test result for contract \"$name\" ###")
        val resultReport = "${results.report(PATH_NOT_RECOGNIZED_ERROR).trim()}\n\n".trim()
        val testCounts = "Tests run: ${results.successCount + results.failureCount}, Passed: ${results.successCount}, Failed: ${results.failureCount}\n\n"
        logger.log("$testCounts$resultReport".trim())
        logger.newLine()
        logger.newLine()
    } catch(e: Throwable) {
        logger.log(e, "Test reported an exception")
    }
}

fun toGherkinFeature(postmanCollection: PostmanCollection): String =
        toGherkinFeature(postmanCollection.name, postmanCollection.stubs.map { it.second })

data class PostmanCollection(val name: String, val stubs: List<Pair<BaseURLInfo, NamedStub>>)

fun stubsFromPostmanCollection(postmanContent: String): PostmanCollection {
    val json = jsonStringToValueMap(postmanContent)

    if(!json.containsKey("info")) throw Exception("This doesn't look like a v2.1.0 Postman collection.")

    val info = json.getValue("info") as JSONObjectValue

    val schema = info.getString("schema")

    if(schema != "https://schema.getpostman.com/json/collection/v2.1.0/collection.json")
        throw Exception("Schema $schema is not supported. Please export this collection in v2.1.0 format. You might have to update to the latest version of Postman.")

    val name = info.getString("name")

    val items = json.getValue("item") as JSONArrayValue
    return PostmanCollection(name, items.list.map { it as JSONObjectValue }.map { item ->
        postmanItemToStubs(item)
    }.flatten())
}

private fun postmanItemToStubs(item: JSONObjectValue): List<Pair<BaseURLInfo, NamedStub>> {
    if(!item.jsonObject.containsKey("request")) {
        val items = item.getJSONArray("item").map { it as JSONObjectValue }
        return items.flatMap { postmanItemToStubs(it) }
    }

    val request = item.getJSONObjectValue("request")
    val scenarioName = if (item.jsonObject.contains("name")) item.getString("name") else "New scenario"

    logger.log("Getting response for $scenarioName")

    return try {
        val responses = item.getJSONArray("response")
        val namedStubsFromSavedResponses = namedStubsFromPostmanResponses(responses)

        baseNamedStub(request, scenarioName).plus(namedStubsFromSavedResponses)
    } catch (e: Throwable) {
        logger.log(e, "  Exception thrown when processing Postman scenario \"$scenarioName\"")
        emptyList()
    }
}

private fun baseNamedStub(request: JSONObjectValue, scenarioName: String): List<Pair<BaseURLInfo, NamedStub>> {
    return try {
        val (baseURL, httpRequest) = postmanItemRequest(request)

        logger.log("  Using base url $baseURL")
        val response = LegacyHttpClient(baseURL, log = dontPrintToConsole).execute(httpRequest)

        listOf(Pair(hostAndPort(baseURL), NamedStub(scenarioName, ScenarioStub(
            httpRequest.dropIrrelevantHeaders(),
            response.dropIrrelevantHeaders()
        ))))
    } catch (e: Throwable) {
        logger.log(e,"  Failed to generate a response for the Postman request")
        emptyList()
    }
}

fun namedStubsFromPostmanResponses(responses: List<Value>): List<Pair<BaseURLInfo, NamedStub>> {
    return responses.map {
        val responseItem = it as JSONObjectValue

        val scenarioName = if (responseItem.jsonObject.contains("name")) responseItem.getString("name") else "New scenario"
        val innerRequest = responseItem.getJSONObjectValue("originalRequest")

        val (baseURL, innerHttpRequest) = postmanItemRequest(innerRequest)
        val innerHttpResponse: HttpResponse = postmanItemResponse(responseItem)

        Pair(hostAndPort(baseURL), NamedStub(scenarioName, ScenarioStub(
            innerHttpRequest.dropIrrelevantHeaders(),
            innerHttpResponse.dropIrrelevantHeaders()
        )))
    }
}

fun postmanItemResponse(responseItem: JSONObjectValue): HttpResponse {
    val status = responseItem.getInt("code")

    val headers: Map<String, String> = when {
        responseItem.jsonObject.containsKey("header") -> {
            val rawHeaders = responseItem.jsonObject.getValue("header") as JSONArrayValue
            emptyMap<String, String>().plus(rawHeaders.list.map {
                val rawHeader = it as JSONObjectValue

                val name = rawHeader.getString("key")
                val value = rawHeader.getString("value")

                Pair(name, value)
            })
        }
        else -> emptyMap()
    }

    val body: Value = when {
        responseItem.jsonObject.containsKey("body") -> guessType(parsedValue(responseItem.jsonObject.getValue("body").toString()))
        else -> EmptyString
    }

    return HttpResponse(status, headers, body)
}

fun postmanItemRequest(request: JSONObjectValue): Pair<String, HttpRequest> {
    val method = request.getString("method")
    val url = urlFromPostmanValue(request.jsonObject.getValue("url"))

    val baseURL = "${url.protocol}://${url.authority}"
    val query: Map<String, String> = url.query?.split("&")?.map { it.split("=").let { parts -> Pair(parts[0], parts[1]) } }?.fold(emptyMap()) { acc, entry -> acc.plus(entry) }
            ?: emptyMap()
    val headers: Map<String, String> = request.getJSONArray("header").map { it as JSONObjectValue }.fold(emptyMap()) { headers, header ->
        headers.plus(Pair(header.getString("key"), header.getString("value")))
    }

    val (body, formFields, formData) = when {
        request.jsonObject.contains("body") -> when (val mode = request.getJSONObjectValue("body").getString("mode")) {
            "raw" -> Triple(guessType(parsedValue(request.getJSONObjectValue("body").getString(mode))), emptyMap(), emptyList<MultiPartFormDataValue>())
            "urlencoded" -> {
                val rawFormFields = request.getJSONObjectValue("body").getJSONArray(mode)
                val formFields = rawFormFields.map {
                    val formField = it as JSONObjectValue
                    val name = formField.getString("key")
                    val value = formField.getString("value")

                    Pair(name, value)
                }.fold(emptyMap<String, String>()) { acc, entry -> acc.plus(entry) }

                Triple(EmptyString, formFields, emptyList())
            }
            "formdata" -> {
                val rawFormData = request.getJSONObjectValue("body").getJSONArray(mode)
                val formData = rawFormData.map {
                    val formField = it as JSONObjectValue
                    val name = formField.getString("key")
                    val value = formField.getString("value")

                    MultiPartContentValue(name, guessType(parsedValue(value)), specifiedContentType = null)
                }

                Triple(EmptyString, emptyMap(), formData)
            }
            "file" -> {
                throw ContractException("File mode is NOT supported yet.")
            }
            else -> Triple(EmptyString, emptyMap(), emptyList())
        }
        else -> Triple(EmptyString, emptyMap(), emptyList())
    }

    val httpRequest = HttpRequest(method, url.path, headers, body, query, formFields, formData)
    return Pair(baseURL, httpRequest)
}

internal fun urlFromPostmanValue(urlValue: Value): URL {
    return when(urlValue) {
        is JSONObjectValue -> urlValue.jsonObject.getValue("raw")
        else -> urlValue
    }.toStringLiteral().trim().let {
        if(it.startsWith("http://") || it.startsWith("https://"))
            it
        else
            "http://$it"
    }.let {
        URI.create(it).toURL()
    }
}

fun guessType(value: Value): Value = when(value) {
    is StringValue -> try {
        when {
            isNumber(value) -> NumberValue(convertToNumber(value.string))
            value.string.lowercase() in listOf("true", "false") -> BooleanValue(value.string.lowercase().toBoolean())
            value.string.startsWith("{") || value.string.startsWith("[") -> parsedJSON(value.string)
            value.string.startsWith("<") -> toXMLNode(parseXML(value.string))
            else -> value
        }
    } catch(e: Throwable) {
        value
    }
    else -> value
}
