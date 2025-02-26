package io.specmatic.stub.stateful

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.*
import io.ktor.server.plugins.cors.CORS
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.OpenApiSpecification.Companion.applyOverlay
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.Resolver
import io.specmatic.core.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.core.Scenario
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.PossibleJsonObjectPatternContainer
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.pattern.withoutOptionality
import io.specmatic.core.route.modules.HealthCheckModule.Companion.configureHealthCheckModule
import io.specmatic.core.route.modules.HealthCheckModule.Companion.isHealthCheckRequest
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.Result
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.*
import io.specmatic.stub.stateful.StubCache.Companion.idValueFor
import io.specmatic.test.ExampleProcessor
import io.specmatic.test.HttpClient
import java.io.File

const val DEFAULT_ACCEPTED_RESPONSE_QUERY_ENDPOINT = "/monitor"

class StatefulHttpStub(
    host: String = "127.0.0.1",
    port: Int = 9000,
    private val features: List<Feature>,
    private val specmaticConfigPath: String? = null,
    private val scenarioStubs: List<ScenarioStub> = emptyList(),
    private val timeoutMillis: Long = 2000,
): ContractStub {

    private val environment = applicationEngineEnvironment {
        module {
            install(DoubleReceive)

            install(CORS) {
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Patch)

                allowHeaders { true }

                allowCredentials = true
                allowNonSimpleContentTypes = true

                anyHost()
            }

            routing {
                staticResources("/", "swagger-ui")

                get("/openapi.yaml") {
                    val openApiFilePath = features.first().path
                    val overlayContent = OpenApiSpecification.getImplicitOverlayContent(openApiFilePath)
                    val openApiSpec = File(openApiFilePath).readText().applyOverlay(overlayContent)
                    call.respond(openApiSpec)
                }
            }

            intercept(ApplicationCallPipeline.Call) {
                val httpLogMessage = HttpLogMessage()

                try {
                    val rawHttpRequest = ktorHttpRequestToHttpRequest(call)
                    httpLogMessage.addRequest(rawHttpRequest)

                    if(rawHttpRequest.isHealthCheckRequest()) return@intercept

                    val httpStubResponse: HttpStubResponse = cachedHttpResponse(rawHttpRequest).response

                    respondToKtorHttpResponse(
                        call,
                        httpStubResponse.response,
                        httpStubResponse.delayInMilliSeconds,
                        specmaticConfig
                    )
                    httpLogMessage.addResponse(httpStubResponse)
                } catch (e: ContractException) {
                    val response = badRequest(e.report())
                    httpLogMessage.addResponse(response)
                    respondToKtorHttpResponse(call, response)
                } catch (e: CouldNotParseRequest) {
                    val response = badRequest("Could not parse request")
                    httpLogMessage.addResponse(response)

                    respondToKtorHttpResponse(call, response)
                } catch (e: Throwable) {
                    val response = internalServerError(exceptionCauseMessage(e) + "\n\n" + e.stackTraceToString())
                    httpLogMessage.addResponse(response)

                    respondToKtorHttpResponse(call, response)
                }

                logger.log(httpLogMessage)
            }

            configureHealthCheckModule()

            connector {
                this.host = host
                this.port = port
            }
        }

    }

    private val server: ApplicationEngine = embeddedServer(Netty, environment, configure = {
        this.callGroupSize = 20
    })

    init {
        if(features.isEmpty()) {
            throw IllegalArgumentException("The stateful stub requires at least one API specification to function.")
        }
        server.start()
    }

    override val client = HttpClient(endPointFromHostAndPort(host, port, null))

    override fun setExpectation(json: String) {
        return
    }

    override fun close() {
        server.stop(gracePeriodMillis = timeoutMillis, timeoutMillis = timeoutMillis)
    }

    private val specmaticConfig = loadSpecmaticConfig()
    private val stubCache = stubCacheWithExampleSeedData()

    private fun cachedHttpResponse(
        httpRequest: HttpRequest,
    ): StubbedResponseResult {
        if (features.isEmpty())
            return NotStubbed(HttpStubResponse(HttpResponse(400, "No valid API specifications loaded")))

        val responses: Map<Int, ResponseDetails> = responseDetailsFrom(features, httpRequest)
        val fakeResponse = responses.responseWithStatusCodeStartingWith(
            "2"
        ) ?: return badRequestOrFakeResponse(responses, httpRequest)

        val fakeAcceptedResponse = responses.responseWithStatusCodeStartingWith(
            "202",
            httpRequest.isRequestExpectingAcceptedResponse()
        )

        val updatedResponse = cachedResponse(
            fakeResponse,
            httpRequest,
            specmaticConfig.getIncludeMandatoryAndRequestedKeysInResponse(),
            responses.responseWithStatusCodeStartingWith("404")?.successResponse?.responseBodyPattern,
            fakeAcceptedResponse
        ) ?: generateHttpResponseFrom(fakeResponse, httpRequest, true)

        return FoundStubbedResponse(
            HttpStubResponse(
                updatedResponse,
                contractPath = fakeResponse.feature.path,
                feature = fakeResponse.feature,
                scenario = fakeResponse.successResponse?.scenario
            )
        )
    }

    private fun cachedResponse(
        fakeResponse: ResponseDetails,
        httpRequest: HttpRequest,
        includeMandatoryAndRequestedKeysInResponse: Boolean?,
        notFoundResponseBodyPattern: Pattern?,
        fakeAcceptedResponse: ResponseDetails?
    ): HttpResponse? {
        val scenario = fakeResponse.successResponse?.scenario
        val method = scenario?.method
        val pathSegments = httpRequest.pathSegments()

        val generatedResponse = generateHttpResponseFrom(fakeResponse, httpRequest, true)

        if(isUnsupportedResponseBodyForCaching(generatedResponse, method, pathSegments)) return null

        val attributeSelectionKeys: Set<String> =
            scenario?.getFieldsToBeMadeMandatoryBasedOnAttributeSelection(httpRequest.queryParams).orEmpty()

        val (resourcePath, resourceId) = resourcePathAndIdFrom(httpRequest)
        val notFoundResponse = generate4xxResponseWithMessage(
            notFoundResponseBodyPattern,
            scenario,
            message = "Resource with resourceId '$resourceId' not found",
            statusCode = 404
        )

        val resourceIdKey = resourceIdKeyFrom(scenario?.httpRequestPattern)
        val cachedResponseWithId = stubCache.findResponseFor(resourcePath, resourceIdKey, resourceId)?.responseBody
        if(pathSegments.size > 1 && cachedResponseWithId == null) return notFoundResponse

        return when {
            method == "POST" -> cachePostResponseAndReturn(
                generatedResponse,
                httpRequest,
                scenario,
                attributeSelectionKeys,
                fakeResponse,
                includeMandatoryAndRequestedKeysInResponse,
                resourcePath,
                fakeAcceptedResponse
            )

            method == "PATCH" && pathSegments.size > 1 -> cachePatchResponseAndReturn(
                httpRequest,
                resourcePath,
                resourceIdKey,
                resourceId,
                fakeResponse,
                generatedResponse,
                attributeSelectionKeys
            )

            method == "GET" && pathSegments.size == 1 -> cacheGetAllResponseAndReturn(
                resourcePath,
                attributeSelectionKeys,
                httpRequest,
                generatedResponse
            )

            method == "GET" && pathSegments.size > 1 -> cacheGetResponseAndReturn(
                cachedResponseWithId,
                notFoundResponse,
                generatedResponse,
                attributeSelectionKeys,
                scenario
            )

            method == "DELETE" && pathSegments.size > 1 -> cacheDeleteResponseAndReturn(
                resourcePath,
                resourceIdKey,
                resourceId,
                generatedResponse
            )

            else -> null
        }
    }

    private fun cacheDeleteResponseAndReturn(
        resourcePath: String,
        resourceIdKey: String,
        resourceId: String,
        generatedResponse: HttpResponse
    ): HttpResponse {
        stubCache.deleteResponse(resourcePath, resourceIdKey, resourceId)
        return generatedResponse
    }

    private fun cacheGetResponseAndReturn(
        cachedResponseWithId: JSONObjectValue?,
        notFoundResponse: HttpResponse,
        generatedResponse: HttpResponse,
        attributeSelectionKeys: Set<String>,
        scenario: Scenario
    ): HttpResponse {
        if (cachedResponseWithId == null) return notFoundResponse

        val isAcceptedResponseQueryRequest = scenario.path.startsWith(DEFAULT_ACCEPTED_RESPONSE_QUERY_ENDPOINT)

        if(isAcceptedResponseQueryRequest) return responseForAcceptedResponseQueryRequest(
            scenario,
            cachedResponseWithId
        ) ?: generatedResponse

        return generatedResponse.withUpdated(cachedResponseWithId, attributeSelectionKeys)
    }

    private fun responseForAcceptedResponseQueryRequest(
        scenario: Scenario,
        cachedResponseWithId: JSONObjectValue
    ): HttpResponse? {
        val matchingStub = scenarioStubs.firstOrNull {
            scenario.matches(httpRequest = it.request, resolver = scenario.resolver)  is Result.Success
        } ?: return null

        ExampleProcessor.store(
            cachedResponseWithId,
            JSONObjectValue(jsonObject = mapOf("${'$'}store" to StringValue("replace")))
        )
        return ExampleProcessor.resolve(matchingStub.response, ExampleProcessor::defaultIfNotExits)
    }

    private fun cacheGetAllResponseAndReturn(
        resourcePath: String,
        attributeSelectionKeys: Set<String>,
        httpRequest: HttpRequest,
        generatedResponse: HttpResponse
    ): HttpResponse {
        val responseBody = stubCache.findAllResponsesFor(
            resourcePath,
            attributeSelectionKeys,
            httpRequest.queryParams.asMap()
        )
        return generatedResponse.withUpdated(responseBody, attributeSelectionKeys)
    }

    private fun cachePatchResponseAndReturn(
        httpRequest: HttpRequest,
        resourcePath: String,
        resourceIdKey: String,
        resourceId: String,
        fakeResponse: ResponseDetails,
        generatedResponse: HttpResponse,
        attributeSelectionKeys: Set<String>
    ): HttpResponse? {
        val responseBody =
            generatePatchResponse(
                httpRequest,
                resourcePath,
                resourceIdKey,
                resourceId,
                fakeResponse
            ) ?: return null

        stubCache.updateResponse(resourcePath, responseBody, resourceIdKey, resourceId)
        return generatedResponse.withUpdated(responseBody, attributeSelectionKeys)
    }

    private fun cachePostResponseAndReturn(
        generatedResponse: HttpResponse,
        httpRequest: HttpRequest,
        scenario: Scenario,
        attributeSelectionKeys: Set<String>,
        fakeResponse: ResponseDetails,
        includeMandatoryAndRequestedKeysInResponse: Boolean?,
        resourcePath: String,
        fakeAcceptedResponse: ResponseDetails?
    ): HttpResponse? {
        val responseBody = generatePostResponse(generatedResponse, httpRequest, scenario.resolver) ?: return null

        val finalResponseBody = if (attributeSelectionKeys.isEmpty()) {
            responseBody.includeMandatoryAndRequestedKeys(
                fakeResponse,
                httpRequest,
                includeMandatoryAndRequestedKeysInResponse
            )
        } else responseBody

        stubCache.addResponse(
            path = resourcePath,
            responseBody = finalResponseBody,
            idKey = DEFAULT_CACHE_RESPONSE_ID_KEY,
            idValue = idValueFor(DEFAULT_CACHE_RESPONSE_ID_KEY, finalResponseBody)
        )

        if (httpRequest.isRequestExpectingAcceptedResponse()) {
            return updateCacheAndReturnAcceptedResponse(
                fakeAcceptedResponse,
                finalResponseBody,
                httpRequest,
                generatedResponse,
                resourcePath
            )
        }

        return generatedResponse.withUpdated(finalResponseBody, attributeSelectionKeys)
    }

    private fun updateCacheAndReturnAcceptedResponse(
        fakeAcceptedResponse: ResponseDetails?,
        finalResponseBody: JSONObjectValue,
        httpRequest: HttpRequest,
        httpResponse: HttpResponse,
        originalResourcePath: String
    ): HttpResponse {
        if(fakeAcceptedResponse == null) throw acceptedResponseSchemaNotFoundException()
        val responseIdValue = idValueFor(DEFAULT_CACHE_RESPONSE_ID_KEY, finalResponseBody)

        stubCache.addAcceptedResponse(
            path = DEFAULT_ACCEPTED_RESPONSE_QUERY_ENDPOINT,
            finalResponseBody = finalResponseBody,
            httpResponse = httpResponse,
            httpRequest = httpRequest,
            idKey = DEFAULT_CACHE_RESPONSE_ID_KEY,
            idValue = responseIdValue,
        )
        val generatedResponse = generateHttpResponseFrom(fakeAcceptedResponse, httpRequest, true)

        return generatedResponse.copy(
            headers = generatedResponse.headers.mapValues {
                if (it.key.contains("Specmatic")) it.value
                else createAcceptedResponseQueryLink(originalResourcePath, responseIdValue)
            }
        )
    }

    private fun createAcceptedResponseQueryLink(
        originalResourcePath: String,
        responseIdValue: String
    ): String {
        return "<$DEFAULT_ACCEPTED_RESPONSE_QUERY_ENDPOINT/$responseIdValue>;rel=related;title=${DEFAULT_ACCEPTED_RESPONSE_QUERY_ENDPOINT.substringAfterLast("/")},<$originalResourcePath/$responseIdValue>;rel=self,<$originalResourcePath/$responseIdValue>;rel=canonical"
    }

    private fun acceptedResponseSchemaNotFoundException(): ContractException {
        return ContractException("No 202 (Accepted) response schema found for this request as expected by the request header $SPECMATIC_RESPONSE_CODE_HEADER.")
    }

    private fun badRequestOrFakeResponse(
        responses: Map<Int, ResponseDetails>,
        httpRequest: HttpRequest
    ): StubbedResponseResult {
        val badRequestScenario = features.scenarioWith(
            httpRequest.method,
            httpRequest.path?.split("/")?.getOrNull(1),
            400
        )
        val badRequestResponseDetails = responses.responseWithStatusCodeStartingWith("400")
            ?: return fakeHttpResponse(features, httpRequest, specmaticConfig)

        val response = generate4xxResponseWithMessage(
            badRequestScenario?.resolvedResponseBodyPattern(),
            badRequestScenario,
            badRequestResponseDetails.results.distinctReport(),
            400
        )
        return FoundStubbedResponse(
            HttpStubResponse(
                response,
                contractPath = badRequestResponseDetails.feature.path,
                feature = badRequestResponseDetails.feature,
                scenario = badRequestResponseDetails.successResponse?.scenario
            )
        )
    }

    private fun List<Feature>.scenarioWith(method: String?, path: String?, statusCode: Int): Scenario? {
        return this.flatMap { it.scenarios }.firstOrNull { scenario ->
            scenario.method == method
                    && scenario.path.split("/").getOrNull(1) == path
                    && scenario.status == statusCode
        }
    }

    private fun Map<Int, ResponseDetails>.responseWithStatusCodeStartingWith(
        value: String,
        isRequestExpectingAcceptedResponse: Boolean = false
    ): ResponseDetails? {
        val valueMatchesStatusCodeFrom: (Int, ResponseDetails) -> Boolean = { statusCode, responseDetails ->
            statusCode.toString().startsWith(value) && responseDetails.successResponse != null
        }

        val response = this.entries.firstOrNull { valueMatchesStatusCodeFrom(it.key, it.value) }?.value

        if (isRequestExpectingAcceptedResponse) return response ?: throw acceptedResponseSchemaNotFoundException()

        return this.entries.filter { it.key != 202 }.firstOrNull {
            valueMatchesStatusCodeFrom(it.key, it.value)
        }?.value ?: response
    }

    private fun generate4xxResponseWithMessage(
        responseBodyPattern: Pattern?,
        scenario: Scenario?,
        message: String,
        statusCode: Int
    ): HttpResponse {
        if(statusCode.toString().startsWith("4").not()) {
            throw IllegalArgumentException("The statusCode should be of 4xx type")
        }
        val warningMessage = "WARNING: The response is in string format since no schema found in the specification for $statusCode response"

        val resolver = scenario?.resolver
        if (
            responseBodyPattern == null ||
            responseBodyPattern !is PossibleJsonObjectPatternContainer ||
            resolver == null
        ) {
            return HttpResponse(statusCode, "$message${System.lineSeparator()}$warningMessage")
        }
        val responseBodyJsonObjectPattern =
            (responseBodyPattern as PossibleJsonObjectPatternContainer).jsonObjectPattern(resolver)

        val messageKey = messageKeyFor4xxResponseMessage(responseBodyJsonObjectPattern)
        if (messageKey == null || responseBodyJsonObjectPattern == null) {
            return HttpResponse(statusCode, "$message${System.lineSeparator()}$warningMessage")
        }

        val jsonObjectWithNotFoundMessage = responseBodyJsonObjectPattern.generate(
            resolver
        ).jsonObject.plus(
            mapOf(withoutOptionality(messageKey) to StringValue(message))
        )
        return HttpResponse(statusCode, JSONObjectValue(jsonObject = jsonObjectWithNotFoundMessage))
    }

    private fun messageKeyFor4xxResponseMessage(
        responseBodyJsonObjectPattern: JSONObjectPattern?
    ): String? {
        val messageKeyWithStringType = responseBodyJsonObjectPattern?.pattern?.entries?.firstOrNull {
            it.value is StringPattern && withoutOptionality(it.key) in setOf("message", "msg")
        }?.key

        if (messageKeyWithStringType != null) return messageKeyWithStringType

        return responseBodyJsonObjectPattern?.pattern?.entries?.firstOrNull {
            it.value is StringPattern
        }?.key
    }

    private fun resourcePathAndIdFrom(httpRequest: HttpRequest): Pair<String, String> {
        val pathSegments = httpRequest.pathSegments()
        val resourcePath = "/${pathSegments.first()}"
        val resourceId = pathSegments.last()
        return Pair(resourcePath, resourceId)
    }

    private fun HttpRequest.pathSegments(): List<String> {
        return this.path?.split("/")?.filter { it.isNotBlank() }.orEmpty()
    }

    private fun isUnsupportedResponseBodyForCaching(
        generatedResponse: HttpResponse,
        method: String?,
        pathSegments: List<String>
    ): Boolean {
        return (generatedResponse.body is JSONObjectValue ||
                (method == "DELETE" && pathSegments.size > 1) ||
                (method == "GET" &&
                        generatedResponse.body is JSONArrayValue &&
                        generatedResponse.body.list.firstOrNull() is JSONObjectValue)).not()
    }

    private fun generatePostResponse(
        generatedResponse: HttpResponse,
        httpRequest: HttpRequest,
        resolver: Resolver
    ): JSONObjectValue? {
        val responseBody = generatedResponse.body
        if (responseBody !is JSONObjectValue || httpRequest.body !is JSONObjectValue)
            return null

        val patchedResponseBodyMap = patchValuesFromRequestIntoResponse(httpRequest.body, responseBody)

        return responseBody.copy(
            jsonObject = responseBodyMapWithUniqueId(httpRequest, patchedResponseBodyMap, resolver)
        )
    }

    private fun generatePatchResponse(
        httpRequest: HttpRequest,
        resourcePath: String,
        resourceIdKey: String,
        resourceId: String,
        fakeResponse: ResponseDetails
    ): JSONObjectValue? {
        if (httpRequest.body !is JSONObjectValue) return null

        val responseBodyPattern = responseBodyPatternFrom(fakeResponse) ?: return null
        val resolver = fakeResponse.successResponse?.resolver ?: return null

        val cachedResponse = stubCache.findResponseFor(resourcePath, resourceIdKey, resourceId)
        val responseBody = cachedResponse?.responseBody ?: return null

        return responseBody.copy(
            jsonObject = patchAndAppendValuesFromRequestIntoResponse(
                httpRequest.body,
                responseBody,
                responseBodyPattern,
                resolver
            )
        )
    }

    private fun JSONObjectValue.includeMandatoryAndRequestedKeys(
        fakeResponse: ResponseDetails,
        httpRequest: HttpRequest,
        includeMandatoryAndRequestedKeysInResponse: Boolean?
    ): JSONObjectValue {
        val responseBodyPattern = fakeResponse.successResponse?.responseBodyPattern ?: return this
        val resolver = fakeResponse.successResponse.resolver ?: return this

        val resolvedResponseBodyPattern = responseBodyPatternFrom(fakeResponse) ?: return this

        if (includeMandatoryAndRequestedKeysInResponse == true && httpRequest.body is JSONObjectValue) {
            return this.copy(
                jsonObject = patchAndAppendValuesFromRequestIntoResponse(
                    httpRequest.body,
                    responseBodyPattern.eliminateOptionalKey(this, resolver) as JSONObjectValue,
                    resolvedResponseBodyPattern,
                    resolver
                )
            )
        }

        return this
    }

    private fun patchValuesFromRequestIntoResponse(
        requestBody: JSONObjectValue,
        responseBody: JSONObjectValue,
        nonPatchableKeys: Set<String> = emptySet()
    ): Map<String, Value> {
        return responseBody.jsonObject.mapValues { (key, value) ->
            if(key in nonPatchableKeys) return@mapValues value

            val patchValueFromRequest = requestBody.jsonObject.entries.firstOrNull {
                it.key == key
            }?.value ?: return@mapValues value

            if(patchValueFromRequest::class.java == value::class.java) return@mapValues patchValueFromRequest
            value
        }
    }

    private fun patchAndAppendValuesFromRequestIntoResponse(
        requestBody: JSONObjectValue,
        responseBody: JSONObjectValue,
        responseBodyPattern: JSONObjectPattern,
        resolver: Resolver
    ): Map<String, Value> {
        val acceptedKeysInResponseBody = responseBodyPattern.keysInNonOptionalFormat()

        val entriesFromRequestMissingInTheResponse = requestBody.jsonObject.filter {
            it.key in acceptedKeysInResponseBody
                    && responseBodyPattern.patternForKey(it.key)?.matches(it.value, resolver)?.isSuccess() == true
                    && responseBody.jsonObject.containsKey(it.key).not()
        }.map {
            it.key to it.value
        }.toMap()

        return patchValuesFromRequestIntoResponse(
            requestBody,
            responseBody,
            specmaticConfig.getVirtualServiceNonPatchableKeys()
        ).plus(entriesFromRequestMissingInTheResponse)
    }

    private fun responseBodyMapWithUniqueId(
        httpRequest: HttpRequest,
        responseBodyMap: Map<String, Value>,
        resolver: Resolver,
    ): Map<String, Value> {
        val idKey = DEFAULT_CACHE_RESPONSE_ID_KEY
        val maxAttempts = 100_000

        val initialIdValue = responseBodyMap[idKey] ?: return responseBodyMap

        val (resourcePath, _) = resourcePathAndIdFrom(httpRequest)
        var currentIdValue = initialIdValue

        repeat(maxAttempts) {
            if (stubCache.findResponseFor(resourcePath, idKey, currentIdValue.toStringLiteral()) == null) {
                return responseBodyMap + mapOf(idKey to currentIdValue)
            }
            currentIdValue = currentIdValue.deepPattern().generate(resolver)
        }

        return responseBodyMap
    }

    private fun responseBodyPatternFrom(fakeResponse: ResponseDetails): JSONObjectPattern? {
        val responseBodyPattern = fakeResponse.successResponse?.responseBodyPattern ?: return null
        val resolver = fakeResponse.successResponse.resolver ?: return null
        val resolvedPattern = resolver.withCyclePrevention(responseBodyPattern) {
            resolvedHop(responseBodyPattern, it)
        }
        if(resolvedPattern !is PossibleJsonObjectPatternContainer) {
            return null
        }

        return resolvedPattern.jsonObjectPattern(resolver)
    }

    private fun resourceIdKeyFrom(httpRequestPattern: HttpRequestPattern?): String {
        return httpRequestPattern?.getPathSegmentPatterns()?.last()?.key.orEmpty()
    }

    private fun HttpResponse.withUpdated(body: Value, attributeSelectionKeys: Set<String>): HttpResponse {
        if(body !is JSONObjectValue) return this.copy(body = body)
        return this.copy(body = body.removeKeysNotPresentIn(attributeSelectionKeys))
    }

    private fun loadSpecmaticConfig(): SpecmaticConfig {
        return if(specmaticConfigPath != null && File(specmaticConfigPath).exists())
            loadSpecmaticConfig(specmaticConfigPath)
        else
            SpecmaticConfig()
    }

    private fun stubCacheWithExampleSeedData(): StubCache {
        val stubCache = StubCache()

        scenarioStubs.forEach {
            val httpRequest = it.request
            if (httpRequest.method !in setOf("GET", "POST")) return@forEach
            if (isUnsupportedResponseBodyForCaching(
                    generatedResponse = it.response,
                    method = httpRequest.method,
                    pathSegments = httpRequest.pathSegments()
                )
            ) return@forEach

            val (resourcePath, _) = resourcePathAndIdFrom(httpRequest)
            val responseBody = it.response.body
            if (httpRequest.method == "GET" && httpRequest.pathSegments().size == 1) {
                if (httpRequest.queryParams.asMap().containsKey(specmaticConfig.attributeSelectionQueryParamKey())) {
                    return@forEach
                }

                val responseBodies = (it.response.body as JSONArrayValue).list.filterIsInstance<JSONObjectValue>()
                responseBodies.forEach { body ->
                    stubCache.addResponse(
                        path = resourcePath,
                        responseBody = body,
                        idKey = DEFAULT_CACHE_RESPONSE_ID_KEY,
                        idValue = idValueFor(DEFAULT_CACHE_RESPONSE_ID_KEY, body)
                    )
                }
                return@forEach
            }

            if (responseBody !is JSONObjectValue) return@forEach
            if(httpRequest.method == "POST" && httpRequest.body !is JSONObjectValue) return@forEach
            stubCache.addResponse(
                path = resourcePath,
                responseBody = responseBody,
                idKey = DEFAULT_CACHE_RESPONSE_ID_KEY,
                idValue = idValueFor(DEFAULT_CACHE_RESPONSE_ID_KEY, responseBody)
            )
        }

        return stubCache
    }

    private fun responseDetailsFrom(features: List<Feature>, httpRequest: HttpRequest): Map<Int, ResponseDetails> {
        return features.asSequence().map { feature ->
            feature.stubResponseMap(
                httpRequest,
                ContractAndRequestsMismatch,
                IgnoreUnexpectedKeys
            ).map { (statusCode, responseResultPair) ->
                statusCode to ResponseDetails(feature, responseResultPair.first, responseResultPair.second)
            }.toMap()
        }.flatMap { map -> map.entries.map { it.toPair() } }.toMap()
    }
}