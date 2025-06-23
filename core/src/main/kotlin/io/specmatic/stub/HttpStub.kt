package io.specmatic.stub

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.*
import io.ktor.server.plugins.cors.CORS
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.specmatic.core.*
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.log.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.route.modules.HealthCheckModule.Companion.configureHealthCheckModule
import io.specmatic.core.route.modules.HealthCheckModule.Companion.isHealthCheckRequest
import io.specmatic.core.utilities.*
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.toXMLNode
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.mock.TRANSIENT_MOCK
import io.specmatic.mock.mockFromJSON
import io.specmatic.mock.validateMock
import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener
import io.specmatic.stub.report.StubEndpoint
import io.specmatic.stub.report.StubUsageReport
import io.specmatic.stub.report.StubUsageReportJson
import io.specmatic.test.LegacyHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer
import java.net.InetAddress
import java.net.URI
import java.nio.charset.Charset
import java.util.*
import kotlin.text.toCharArray

const val SPECMATIC_RESPONSE_CODE_HEADER = "Specmatic-Response-Code"
const val HTTP_PORT = 80
const val HTTPS_PORT = 443

class HttpStub(
    private val features: List<Feature>,
    rawHttpStubs: List<HttpStubData> = emptyList(),
    val host: String = "127.0.0.1",
    private val port: Int = 9000,
    private val log: (event: LogMessage) -> Unit = dontPrintToConsole,
    private val strictMode: Boolean = false,
    val keyData: KeyData? = null,
    val passThroughTargetBase: String = "",
    val httpClientFactory: HttpClientFactory = HttpClientFactory(),
    val workingDirectory: WorkingDirectory? = null,
    val specmaticConfigPath: String? = null,
    private val timeoutMillis: Long = 0,
    private val specToStubBaseUrlMap: Map<String, String?> = features.associate {
        it.path to endPointFromHostAndPort(host, port, keyData)
    },
    private val listeners: List<MockEventListener> = emptyList()
) : ContractStub {
    constructor(
        feature: Feature,
        scenarioStubs: List<ScenarioStub> = emptyList(),
        host: String = "localhost",
        port: Int = 9000,
        log: (event: LogMessage) -> Unit = dontPrintToConsole,
        specToStubBaseUrlMap: Map<String, String> = mapOf(
            feature.path to endPointFromHostAndPort(host, port, null)
        ),
        listeners: List<MockEventListener> = emptyList()
    ) : this(
        listOf(feature),
        contractInfoToHttpExpectations(listOf(Pair(feature, scenarioStubs))),
        host,
        port,
        log,
        specToStubBaseUrlMap = specToStubBaseUrlMap,
        listeners = listeners
    )

    constructor(
        gherkinData: String,
        scenarioStubs: List<ScenarioStub> = emptyList(),
        host: String = "localhost",
        port: Int = 9000,
        log: (event: LogMessage) -> Unit = dontPrintToConsole
    ) : this(
        parseGherkinStringToFeature(gherkinData),
        scenarioStubs,
        host,
        port,
        log,
        specToStubBaseUrlMap = mapOf(
            parseGherkinStringToFeature(gherkinData).path to endPointFromHostAndPort(host, port, null)
        )
    )

    companion object {
        const val JSON_REPORT_PATH = "./build/reports/specmatic"
        const val JSON_REPORT_FILE_NAME = "stub_usage_report.json"

        fun setExpectation(
            stub: ScenarioStub,
            feature: Feature,
            mismatchMessages: MismatchMessages = ContractAndStubMismatchMessages
        ): Pair<Pair<Result.Success, List<HttpStubData>>?, NoMatchingScenario?> {
            try {
                val tier1Match = feature.matchingStub(
                    stub,
                    mismatchMessages
                )

                val matchedScenario = tier1Match.scenario ?: throw ContractException("Expected scenario after stub matched for:${System.lineSeparator()}${stub.toJSON()}")

                val stubWithSubstitutionsResolved = stub.resolveDataSubstitutions().map { scenarioStub ->
                    feature.matchingStub(scenarioStub, ContractAndStubMismatchMessages)
                }

                val stubData: List<HttpStubData> = stubWithSubstitutionsResolved.map {
                    softCastResponseToXML(
                        it
                    )
                }

                return Pair(Pair(Result.Success(), stubData), null)
            } catch (e: NoMatchingScenario) {
                return Pair(null, e)
            }
        }
    }

    private val specmaticConfig: SpecmaticConfig =
        if(specmaticConfigPath != null && File(specmaticConfigPath).exists())
            loadSpecmaticConfig(specmaticConfigPath)
        else
            SpecmaticConfig()

    val specToBaseUrlMap: Map<String, String> = getValidatedBaseUrlsOrExit(
        features.associate {
            val baseUrl = specToStubBaseUrlMap[it.path] ?: endPointFromHostAndPort(host, port, keyData)
            it.path to baseUrl
        }
    )

    private val httpExpectations: HttpExpectations = HttpExpectations(
        static = staticHttpStubData(rawHttpStubs),
        transient = rawHttpStubs.filter { it.stubToken != null }.reversed().toMutableList(),
        specToBaseUrlMap = specToBaseUrlMap
    )

    private val requestHandlers: MutableList<RequestHandler> = mutableListOf()

    //used by graphql / plugins
    fun registerHandler(requestHandler: RequestHandler) {
        requestHandlers.add(requestHandler)
    }

    private fun staticHttpStubData(rawHttpStubs: List<HttpStubData>): MutableList<HttpStubData> {
        val staticStubs = rawHttpStubs.filter { it.stubToken == null }

        val stubsFromSpecificationExamples: List<HttpStubData> = features.map { feature ->
            feature.stubsFromExamples.entries.map { (exampleName, examples) ->
                examples.mapNotNull { (request, response) ->
                    try {
                        val stubData: HttpStubData =
                            feature.matchingStub(request, response, ExamplesAsExpectationsMismatch(exampleName))

                        if (stubData.matchFailure) {
                            logger.newLine()
                            logger.log(stubData.response.body.toStringLiteral())
                            null
                        } else {
                            stubData
                        }
                    } catch (e: Throwable) {
                        logger.newLine()

                        when (e) {
                            is ContractException -> {
                                logger.log(e)
                                null
                            }
                            is NoMatchingScenario -> {
                                logger.log(e, "[Example $exampleName]")
                                null
                            }
                            else -> {
                                logger.log(e, "[Example $exampleName]")
                                throw e
                            }
                        }
                    }
                }
            }
        }.flatten().flatten()

        return staticStubs.plus(stubsFromSpecificationExamples).toMutableList()
    }

    private val _logs: MutableList<StubEndpoint> = Collections.synchronizedList(ArrayList())
    private val _allEndpoints: List<StubEndpoint> = extractALlEndpoints()

    val logs: List<StubEndpoint> get() = _logs.toList()
    val allEndpoints: List<StubEndpoint> get() = _allEndpoints.toList()


    val stubCount: Int
        get() {
            return httpExpectations.stubCount
        }

    val transientStubCount: Int
        get() {
            return httpExpectations.transientStubCount
        }

    val endPoint = endPointFromHostAndPort(host, port, keyData)

    override val client = LegacyHttpClient(this.endPoint)

    private val sseBuffer: SSEBuffer = SSEBuffer()

    private val broadcastChannels: Vector<BroadcastChannel<SseEvent>> = Vector(50, 10)

    private val requestInterceptors: MutableList<RequestInterceptor> = mutableListOf()

    private val responseInterceptors: MutableList<ResponseInterceptor> = mutableListOf()

    fun registerRequestInterceptor(requestInterceptor: RequestInterceptor) {
        requestInterceptors.add(requestInterceptor)
    }

    fun registerResponseInterceptor(responseInterceptor: ResponseInterceptor) {
        responseInterceptors.add(responseInterceptor)
    }

    private val environment = applicationEngineEnvironment {
        module {
            install(DoubleReceive)
            configure(CORS)

            intercept(ApplicationCallPipeline.Call) {
                val httpLogMessage = HttpLogMessage(targetServer = "port '${call.request.local.localPort}'")

                try {
                    val rawHttpRequest = ktorHttpRequestToHttpRequest(call).also {
                        httpLogMessage.addRequestWithCurrentTime(it)
                        if (it.isHealthCheckRequest()) return@intercept
                    }

                    val httpRequest = requestInterceptors.fold(rawHttpRequest) { request, requestInterceptor ->
                        requestInterceptor.interceptRequest(request) ?: request
                    }

                    val responseFromRequestHandler = requestHandlers.firstNotNullOfOrNull { it.handleRequest(httpRequest) }
                    val httpStubResponse: HttpStubResponse = when {
                        isFetchLogRequest(httpRequest) -> handleFetchLogRequest()
                        isFetchLoadLogRequest(httpRequest) -> handleFetchLoadLogRequest()
                        isFetchContractsRequest(httpRequest) -> handleFetchContractsRequest()
                        responseFromRequestHandler != null -> responseFromRequestHandler
                        isExpectationCreation(httpRequest) -> handleExpectationCreationRequest(httpRequest)
                        isSseExpectationCreation(httpRequest) -> handleSseExpectationCreationRequest(httpRequest)
                        isStateSetupRequest(httpRequest) -> handleStateSetupRequest(httpRequest)
                        isFlushTransientStubsRequest(httpRequest) -> handleFlushTransientStubsRequest(httpRequest)
                        else -> serveStubResponse(
                            httpRequest,
                            baseUrl = "${call.request.local.scheme}://${call.request.local.serverHost}:${call.request.local.localPort}",
                            defaultBaseUrl = endPointFromHostAndPort(host, port, keyData),
                            urlPath = call.request.path()
                        )
                    }

                    val httpResponse = responseInterceptors.fold(httpStubResponse.response) { response, responseInterceptor ->
                        responseInterceptor.interceptResponse(httpRequest, response) ?: response
                    }
                    if (httpRequest.path!!.startsWith("""/features/default""")) {
                        handleSse(httpRequest, this@HttpStub, this)
                    } else {
                        val updatedHttpStubResponse = httpStubResponse.copy(response = httpResponse)
                        respondToKtorHttpResponse(call, updatedHttpStubResponse.response, updatedHttpStubResponse.delayInMilliSeconds, specmaticConfig)
                        httpLogMessage.addResponse(updatedHttpStubResponse)
                    }
                } catch (e: ContractException) {
                    val response = badRequest(e.report())
                    httpLogMessage.addResponseWithCurrentTime(response)
                    httpLogMessage.scenario = e.scenario as? Scenario
                    httpLogMessage.addException(e)
                    respondToKtorHttpResponse(call, response)
                } catch (e: CouldNotParseRequest) {
                    httpLogMessage.addRequestWithCurrentTime(defensivelyExtractedRequestForLogging(call))
                    val response = badRequest("Could not parse request")
                    httpLogMessage.addResponseWithCurrentTime(response)
                    httpLogMessage.addException(e)
                    respondToKtorHttpResponse(call, response)
                } catch (e: Throwable) {
                    val response = internalServerError(exceptionCauseMessage(e) + "\n\n" + e.stackTraceToString())
                    httpLogMessage.addResponseWithCurrentTime(response)
                    httpLogMessage.addException(Exception(e))
                    respondToKtorHttpResponse(call, response)
                }

                log(httpLogMessage)
                MockEvent(httpLogMessage).let { event -> listeners.forEach { it.onRespond(event) } }
            }

            configureHealthCheckModule()
        }

        configureHostPorts()
    }

    private suspend fun handleSse(
        httpRequest: HttpRequest,
        httpStub: HttpStub,
        pipelineContext: PipelineContext<Unit, ApplicationCall>
    ) {
        logger.log("Incoming subscription on URL path ${httpRequest.path} ")
        val channel: Channel<SseEvent> = Channel(10, BufferOverflow.DROP_OLDEST)
        val broadcastChannel: BroadcastChannel<SseEvent> = channel.broadcast()
        httpStub.broadcastChannels.add(broadcastChannel)

        val events: ReceiveChannel<SseEvent> = broadcastChannel.openSubscription()

        try {
            pipelineContext.call.respondSse(events, sseBuffer, httpRequest)

            httpStub.broadcastChannels.remove(broadcastChannel)

            close(
                events,
                channel,
                "Events handle was already closed after handling all events",
                "Channel was already handled after handling all events"
            )
        } catch (e: Throwable) {
            logger.log(e, "Exception in the SSE module")

            httpStub.broadcastChannels.remove(broadcastChannel)

            close(
                events,
                channel,
                "Events handle threw an exception on closing",
                "Channel through an exception on closing"
            )
        }
    }

    private fun ApplicationEngineEnvironmentBuilder.configureHostPorts() {
        val hostPortList = getHostAndPortList()

        when (keyData) {
            null -> connectors.addAll(
                hostPortList.map { (host, port) ->
                    EngineConnectorBuilder().also {
                        it.host = host
                        it.port = port
                    }
                }
            )

            else -> connectors.addAll(
                hostPortList.map { (host, port) ->
                    EngineSSLConnectorBuilder(
                        keyStore = keyData.keyStore,
                        keyAlias = keyData.keyAlias,
                        privateKeyPassword = { keyData.keyPassword.toCharArray() },
                        keyStorePassword = { keyData.keyPassword.toCharArray() }
                    ).also {
                        it.host = host
                        it.port = port
                    }
                }
            )
        }
    }

    private fun Application.configure(CORS: ApplicationPlugin<CORSConfig>) {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)

            allowHeaders {
                true
            }

            allowCredentials = true
            allowNonSimpleContentTypes = true

            anyHost()
        }
    }

    private fun getHostAndPortList(): List<Pair<String, Int>> {
        val defaultBaseUrl = endPointFromHostAndPort(this.host, this.port, this.keyData)
        val specsWithMultipleBaseUrls = specmaticConfig.stubToBaseUrlList(defaultBaseUrl).groupBy(
            keySelector = { it.first }, valueTransform = { it.second }
        ).filterValues { it.size > 1 }

        if (specsWithMultipleBaseUrls.isNotEmpty()) {
            logger.log("WARNING: The following specification are associated with multiple base URLs:")
            specsWithMultipleBaseUrls.forEach { (spec, baseUrls) ->
                logger.log("- $spec")
                baseUrls.forEach { baseUrl ->
                    logger.withIndentation(2) {
                        logger.log("- $baseUrl")
                    }
                }
            }
            logger.log("Note: The logs below indicate the selected base URL for each specification")
        }

        return specmaticConfig.stubBaseUrls(defaultBaseUrl).map { stubBaseUrl ->
            val host = extractHost(stubBaseUrl).let(::normalizeHost)
            val port = extractPort(stubBaseUrl)
            Pair(host, port)
        }.distinct().ifEmpty { listOf(this.host to this.port) }
    }

    fun serveStubResponse(
        httpRequest: HttpRequest,
        baseUrl: String,
        defaultBaseUrl: String,
        urlPath: String
    ): HttpStubResponse {
        val url = "$baseUrl$urlPath"
        val stubBaseUrlPath = specmaticConfig.stubBaseUrlPathAssociatedTo(url, defaultBaseUrl)

        return getHttpResponse(
            httpRequest = httpRequest.trimBaseUrlPath(stubBaseUrlPath),
            features = featuresAssociatedTo(baseUrl, features, specToBaseUrlMap, urlPath),
            httpExpectations.associatedTo(baseUrl, defaultBaseUrl, urlPath),
            strictMode = strictMode,
            passThroughTargetBase = passThroughTargetBase,
            httpClientFactory = httpClientFactory,
            specmaticConfig = specmaticConfig,
        ).also {
            if (it is FoundStubbedResponse) {
                it.response.mock?.let { mock -> httpExpectations.removeTransientMock(mock) }
            }
            it.log(_logs, httpRequest)
        }.response
    }

    internal fun featuresAssociatedTo(
        baseUrl: String,
        features: List<Feature>,
        specToBaseUrlMap: Map<String, String>,
        urlPath: String
    ): List<Feature> {
        val parsedBaseUrl = URI(baseUrl + urlPath)
        val specsForGivenBaseUrl = specToBaseUrlMap.mapValues { URI(it.value) }.filterValues { stubBaseUrl ->
            isSameBaseIgnoringHost(parsedBaseUrl, stubBaseUrl)
        }

        return features.filter { feature -> feature.path in specsForGivenBaseUrl }
    }

    private fun handleFlushTransientStubsRequest(httpRequest: HttpRequest): HttpStubResponse {
        val token = httpRequest.path?.removePrefix("/_specmatic/$TRANSIENT_MOCK/")

        httpExpectations.removeWithToken(token)

        return HttpStubResponse(HttpResponse.OK)
    }

    private fun isFlushTransientStubsRequest(httpRequest: HttpRequest): Boolean {
        return httpRequest.method?.toLowerCasePreservingASCIIRules() == "delete" && httpRequest.path?.startsWith("/_specmatic/$TRANSIENT_MOCK/") == true
    }

    private fun close(
        events: ReceiveChannel<SseEvent>,
        channel: Channel<SseEvent>,
        eventsError: String,
        channelError: String
    ) {
        try {
            events.cancel()
        } catch (e: Throwable) {
            logger.log("$eventsError (${exceptionCauseMessage(e)})")
        }

        try {
            channel.cancel()
        } catch (e: Throwable) {
            logger.log("$channelError (${exceptionCauseMessage(e)}")
        }
    }

    private suspend fun defensivelyExtractedRequestForLogging(call: ApplicationCall): HttpRequest {
        val request = HttpRequest().let {
            try {
                it.copy(method = call.request.httpMethod.toString())
            } catch (e: Throwable) {
                it
            }
        }.let {
            try {
                it.copy(path = call.request.path())
            } catch (e: Throwable) {
                it
            }
        }.let { request ->
            val requestHeaders = call.request.headers.toMap().mapValues { it.value[0] }
            request.copy(headers = requestHeaders)
        }.let {
            val queryParams = toParams(call.request.queryParameters)
            it.copy(queryParams = QueryParameters(paramPairs = queryParams))
        }.let {
            val bodyOrError = try {
                receiveText(call)
            } catch (e: Throwable) {
                "Could not get body. Got exception: ${exceptionCauseMessage(e)}\n\n${e.stackTraceToString()}"
            }

            it.copy(body = StringValue(bodyOrError))
        }
        return request
    }

    private val server: ApplicationEngine = embeddedServer(Netty, environment, configure = {
        this.callGroupSize = 20
    })

    private fun handleFetchLoadLogRequest(): HttpStubResponse =
        HttpStubResponse(HttpResponse.ok(StringValue(LogTail.getSnapshot())))

    private fun handleFetchContractsRequest(): HttpStubResponse =
        HttpStubResponse(HttpResponse.ok(StringValue(features.joinToString("\n") { it.name })))

    private fun handleFetchLogRequest(): HttpStubResponse =
        HttpStubResponse(HttpResponse.ok(StringValue(LogTail.getString())))

    private fun handleExpectationCreationRequest(httpRequest: HttpRequest): HttpStubResponse {
        return try {
            if (httpRequest.body.toStringLiteral().isEmpty())
                throw ContractException("Expectation payload was empty")

            val mock: ScenarioStub = stringToMockScenario(httpRequest.body)
            val stub: HttpStubData = setExpectation(mock).first()

            HttpStubResponse(HttpResponse.OK, contractPath = stub.contractPath)
        } catch (e: ContractException) {
            HttpStubResponse(
                HttpResponse(
                    status = 400,
                    headers = mapOf(SPECMATIC_RESULT_HEADER to "failure"),
                    body = StringValue(e.report())
                )
            )
        } catch (e: NoMatchingScenario) {
            HttpStubResponse(
                HttpResponse(
                    status = 400,
                    headers = mapOf(SPECMATIC_RESULT_HEADER to "failure"),
                    body = StringValue(e.report(httpRequest))
                )
            )
        } catch (e: Throwable) {
            HttpStubResponse(
                HttpResponse(
                    status = 400,
                    headers = mapOf(SPECMATIC_RESULT_HEADER to "failure"),
                    body = StringValue(e.localizedMessage ?: e.message ?: e.javaClass.name)
                )
            )
        }
    }

    private suspend fun handleSseExpectationCreationRequest(httpRequest: HttpRequest): HttpStubResponse {
        return try {
            val sseEvent: SseEvent? = ObjectMapper().readValue(httpRequest.bodyString, SseEvent::class.java)

            if (sseEvent == null) {
                logger.debug("No Sse Event was found in the request:\n${httpRequest.toLogString("  ")}")
            } else if (sseEvent.bufferIndex == null) {
                logger.debug("Broadcasting event: $sseEvent")

                for (channel in broadcastChannels) {
                    channel.send(sseEvent)
                }
            } else {
                logger.debug("Adding event to buffer: $sseEvent")
                sseBuffer.add(sseEvent)
            }

            HttpStubResponse(HttpResponse.OK, contractPath = "")
        } catch (e: ContractException) {
            HttpStubResponse(
                HttpResponse(
                    status = 400,
                    headers = mapOf(SPECMATIC_RESULT_HEADER to "failure"),
                    body = exceptionCauseMessage(e)
                )
            )
        } catch (e: Throwable) {
            HttpStubResponse(
                HttpResponse(
                    status = 500,
                    headers = mapOf(SPECMATIC_RESULT_HEADER to "failure"),
                    body = exceptionCauseMessage(e) + "\n\n" + e.stackTraceToString()
                )
            )
        }
    }

    // Java helper
    override fun setExpectation(json: String) {
        val mock = stringToMockScenario(StringValue(json))
        setExpectation(mock)
    }

    fun setExpectation(stub: ScenarioStub): List<HttpStubData> {
        val results = features.asSequence().map { feature -> setExpectation(stub, feature) }

        val result: Pair<Pair<Result.Success, List<HttpStubData>>?, NoMatchingScenario?>? = results.find { it.first != null }
        val firstResult: Pair<Result.Success, List<HttpStubData>>? = result?.first

        when (firstResult) {
            null -> {
                val failures = results.map {
                    it.second?.results?.withoutFluff()?.results ?: emptyList()
                }.flatten().toList()

                val failureResults = Results(failures).withoutFluff()
                throw NoMatchingScenario(failureResults, cachedMessage = failureResults.report(stub.request))
            }

            else -> {
                val requestBodyRegex = parseRegex(stub.requestBodyRegex)
                val stubData = firstResult.second.map { it.copy(requestBodyRegex = requestBodyRegex) }
                val resultWithRequestBodyRegex = stubData.map { Pair(firstResult.first, it) }

                if (stub.stubToken != null) {
                    resultWithRequestBodyRegex.forEach {
                        httpExpectations.addDynamicTransient(it, stub)
                    }

                } else {
                    resultWithRequestBodyRegex.forEach {
                        httpExpectations.addDynamic(it, stub)
                    }
                }
            }
        }

        return firstResult.second
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

    override fun close() {
        server.stop(gracePeriodMillis = timeoutMillis, timeoutMillis = timeoutMillis)
        printUsageReport()
    }

    private fun handleStateSetupRequest(httpRequest: HttpRequest): HttpStubResponse {
        val body = httpRequest.body
        val serverState = toMap(body)

        features.forEach { feature ->
            feature.setServerState(serverState)
        }

        return HttpStubResponse(HttpResponse.OK)
    }

    init {
        server.start()
    }

    private fun extractALlEndpoints(): List<StubEndpoint> {
        return features.map {
            it.scenarios.map { scenario ->
                if (scenario.isA2xxScenario()) {
                    StubEndpoint(
                        scenario.path,
                        scenario.method,
                        scenario.status,
                        scenario.sourceProvider,
                        scenario.sourceRepository,
                        scenario.sourceRepositoryBranch,
                        scenario.specification,
                        scenario.serviceType
                    )
                } else {
                    null
                }
            }
        }.flatten().filterNotNull()
    }

    private fun printUsageReport() {
        specmaticConfigPath?.let {
            val stubUsageReport = StubUsageReport(specmaticConfigPath, _allEndpoints, _logs)
            println("Saving Stub Usage Report json to $JSON_REPORT_PATH ...")
            val json = Json {
                encodeDefaults = false
            }
            val generatedReport = stubUsageReport.generate()
            val reportJson: String = File(JSON_REPORT_PATH).resolve(JSON_REPORT_FILE_NAME).let { reportFile ->
                if (reportFile.exists()) {
                    try {
                        val existingReport = Json.decodeFromString<StubUsageReportJson>(reportFile.readText())
                        json.encodeToString(generatedReport.merge(existingReport))
                    } catch (exception: SerializationException) {
                        logger.log("The existing report file is not a valid Stub Usage Report. ${exception.message}")
                        json.encodeToString(generatedReport)
                    }
                } else {
                    json.encodeToString(generatedReport)
                }
            }

            saveJsonFile(reportJson, JSON_REPORT_PATH, JSON_REPORT_FILE_NAME)
        }
    }

    private fun getValidatedBaseUrlsOrExit(specToBaseUrlMap: Map<String, String>): Map<String, String> {
        val validationResult = validateBaseUrls(specToBaseUrlMap)
        if (validationResult is Result.Failure) exitWithMessage(validationResult.reportString())
        return specToBaseUrlMap
    }

    fun printStartupMessage() {
        consoleLog(NewLineLogMessage)
        consoleLog(
            StringLog(
                serverStartupMessage(specToBaseUrlMap)
            )
        )
        consoleLog(StringLog("Press Ctrl + C to stop."))
    }

    private fun serverStartupMessage(specToStubBaseUrlMap: Map<String, String>): String {
        val baseUrlToSpecsMap = specToStubBaseUrlMap.entries.groupBy({ it.value }, { it.key })

        return buildString {
            appendLine("Stub server is running on the following URLs:")
            baseUrlToSpecsMap.entries.sortedBy { it.key }.forEachIndexed { urlIndex, (url, specs) ->
                appendLine("- $url serving endpoints from specs:")
                specs.sorted().forEachIndexed { index, spec ->
                    appendLine("\t${index + 1}. $spec")
                }
                if (urlIndex < baseUrlToSpecsMap.size - 1) appendLine()
            }
        }
    }

}

class CouldNotParseRequest(innerException: Throwable) : Exception(exceptionCauseMessage(innerException))

suspend fun ktorHttpRequestToHttpRequest(call: ApplicationCall): HttpRequest {
    try {
        val (body, formFields, multiPartFormData) = bodyFromCall(call)

        val requestHeaders = call.request.headers.toMap().mapValues { it.value[0] }

        return HttpRequest(
            method = call.request.httpMethod.value,
            path = urlDecodePathSegments(call.request.path()),
            headers = requestHeaders,
            body = body,
            queryParams = QueryParameters(paramPairs = toParams(call.request.queryParameters)),
            formFields = formFields,
            multiPartFormData = multiPartFormData
        )
    } catch (e: Throwable) {
        throw CouldNotParseRequest(e)
    }
}

private suspend fun bodyFromCall(call: ApplicationCall): Triple<Value, Map<String, String>, List<MultiPartFormDataValue>> {
    return when {
        call.request.httpMethod == HttpMethod.Get -> if(call.request.headers.contains("Content-Type")) {
            Triple(parsedValue(receiveText(call)), emptyMap(), emptyList())
        } else {
            Triple(NoBodyValue, emptyMap(), emptyList())
        }

        call.request.contentType().match(ContentType.Application.FormUrlEncoded) -> Triple(
            EmptyString,
            call.receiveParameters().toMap().mapValues { (_, values) -> values.first() },
            emptyList()
        )

        call.request.isMultipart() -> {
            val multiPartData = call.receiveMultipart()
            val boundary = call.request.contentType().parameter("boundary") ?: "boundary"

            val parts = multiPartData.readAllParts().map {
                when (it) {
                    is PartData.FileItem -> {
                        val content = it.provider().asStream().use { inputStream ->
                            MultiPartContent(inputStream.readBytes())
                        }
                        MultiPartFileValue(
                            it.name ?: "",
                            it.originalFileName ?: "",
                            it.contentType?.let { contentType -> "${contentType.contentType}/${contentType.contentSubtype}" },
                            null,
                            content,
                            boundary
                        )
                    }

                    is PartData.FormItem -> {
                        MultiPartContentValue(
                            it.name ?: "",
                            StringValue(it.value),
                            boundary,
                            specifiedContentType = it.contentType?.let { contentType -> "${contentType.contentType}/${contentType.contentSubtype}" }
                        )
                    }

                    is PartData.BinaryItem -> {
                        val content = it.provider().asStream().use { input ->
                            val output = ByteArrayOutputStream()
                            input.copyTo(output)
                            output.toString()
                        }

                        MultiPartContentValue(
                            it.name ?: "",
                            StringValue(content),
                            boundary,
                            specifiedContentType = it.contentType?.let { contentType -> "${contentType.contentType}/${contentType.contentSubtype}" }
                        )
                    }

                    else -> {
                        throw UnsupportedOperationException("Unhandled PartData")
                    }
                }
            }

            Triple(EmptyString, emptyMap(), parts)
        }

        else -> {
            if(call.request.headers.contains("Content-Type"))
                Triple(parsedValue(receiveText(call)), emptyMap(), emptyList())
            else
                Triple(NoBodyValue, emptyMap(), emptyList())
        }
    }
}

suspend fun receiveText(call: ApplicationCall): String {
    return if (call.request.contentCharset() == null) {
        val byteArray: ByteArray = call.receive()
        String(byteArray, Charset.forName("UTF-8"))
    } else {
        call.receiveText()
    }
}

//internal fun toParams(queryParameters: Parameters) = queryParameters.toMap().mapValues { it.value.first() }

internal fun toParams(queryParameters: Parameters): List<Pair<String, String>> =
    queryParameters.toMap().flatMap { (parameterName, parameterValues) ->
        parameterValues.map {
            parameterName to it
        }
    }

suspend fun respondToKtorHttpResponse(
    call: ApplicationCall,
    httpResponse: HttpResponse,
    delayInMilliSeconds: Long? = null,
    specmaticConfig: SpecmaticConfig? = null
) {
    val headersControlledByEngine = listOfExcludedHeaders().map { it.lowercase() }
    for ((name, value) in httpResponse.headers.filterNot { it.key.lowercase() in headersControlledByEngine }) {
        call.response.headers.append(name, value)
    }

    val delayInMs = delayInMilliSeconds ?: specmaticConfig?.getStubDelayInMilliseconds()
    if (delayInMs != null) {
        delay(delayInMs)
    }

    val contentType = httpResponse.headers["Content-Type"] ?: httpResponse.body.httpContentType
    val responseBody = httpResponse.body.toStringLiteral()
    val status = HttpStatusCode.fromValue(httpResponse.status)

    if (contentType.isBlank()) {
        call.respond(object : OutgoingContent.NoContent() {
            override val status: HttpStatusCode = HttpStatusCode.fromValue(httpResponse.status)
        })
        return
    }

    call.respond(TextContent(responseBody, ContentType.parse(contentType), status))
}

fun getHttpResponse(
    httpRequest: HttpRequest,
    features: List<Feature>,
    httpExpectations: HttpExpectations,
    strictMode: Boolean,
    passThroughTargetBase: String = "",
    httpClientFactory: HttpClientFactory? = null,
    specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
): StubbedResponseResult {
    try {
        val (matchResults, matchingStubResponse) = stubbedResponse(httpExpectations, httpRequest)
        if(matchingStubResponse != null) {
            val (httpStubResponse, httpStubData) = matchingStubResponse
            return FoundStubbedResponse(
                httpStubResponse.resolveSubstitutions(
                    httpRequest,
                    httpStubData.resolveOriginalRequest() ?: httpRequest,
                    httpStubData.data,
                )
            )
        }
        if (httpClientFactory != null && passThroughTargetBase.isNotBlank()) {
            return NotStubbed(
                passThroughResponse(
                    httpRequest,
                    passThroughTargetBase,
                    httpClientFactory
                )
            )
        }
        if (strictMode) return NotStubbed(HttpStubResponse(
            response = strictModeHttp400Response(httpRequest, matchResults),
            scenario = features.firstNotNullOfOrNull { it.identifierMatchingScenario(httpRequest) }
        ))

        return fakeHttpResponse(features, httpRequest, specmaticConfig)
    } finally {
        features.forEach { feature -> feature.clearServerState() }
    }
}

const val SPECMATIC_SOURCE_HEADER = "X-$APPLICATION_NAME-Source"

fun passThroughResponse(
    httpRequest: HttpRequest,
    passThroughUrl: String,
    httpClientFactory: HttpClientFactory
): HttpStubResponse {
    val response = httpClientFactory.client(passThroughUrl).execute(httpRequest)
    return HttpStubResponse(response.copy(headers = response.headers.plus(SPECMATIC_SOURCE_HEADER to "proxy")))
}

object StubAndRequestMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Stub expected $expected but request contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the request was not in the stub"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the stub was not found in the request"
    }
}

private fun stubbedResponse(
    httpExpectations: HttpExpectations,
    httpRequest: HttpRequest
): Pair<List<Pair<Result, HttpStubData>>, Pair<HttpStubResponse, HttpStubData>?> {

    val (stubData, matchResults) = httpExpectations.matchingStub(httpRequest)

    val stubResponse = stubData?.let {
        val softCastResponse = it.softCastResponseToXML(httpRequest).response
        HttpStubResponse(
            softCastResponse,
            it.delayInMilliseconds,
            it.contractPath,
            examplePath = it.examplePath,
            feature = stubData.feature,
            scenario = stubData.scenario,
            mock = stubData
        ) to it
    }

    return Pair(matchResults, stubResponse)
}

private fun stubThatMatchesRequest(
    httpExpectations: HttpExpectations,
    httpRequest: HttpRequest
): Pair<HttpStubData?, List<Pair<Result, HttpStubData>>> {
    return httpExpectations.matchingStub(httpRequest)
}

fun isMissingData(e: Throwable?): Boolean {
    return when (e) {
        null -> false
        is MissingDataException -> true
        is ContractException -> isMissingData(e.exceptionCause)
        else -> false
    }
}

object ContractAndRequestsMismatch : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Contract expected $expected but request contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the request was not in the contract"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "${
            keyLabel.lowercase().capitalizeFirstChar()
        } named $keyName in the contract was not found in the request"
    }
}

data class ResponseDetails(val feature: Feature, val successResponse: ResponseBuilder?, val results: Results)

fun fakeHttpResponse(
    features: List<Feature>,
    httpRequest: HttpRequest,
    specmaticConfig: SpecmaticConfig = SpecmaticConfig()
): StubbedResponseResult {

    if (features.isEmpty())
       return NotStubbed(HttpStubResponse(HttpResponse(400, "No valid API specifications loaded")))

    val responses: List<ResponseDetails> = responseDetailsFrom(features, httpRequest)

    return when (val fakeResponse = responses.successResponse()) {
        null -> {
            val failureResults = responses.filter { it.successResponse == null }.map { it.results }

            val combinedFailureResult = failureResults.reduce { first, second ->
                first.plus(second)
            }.withoutFluff()

            val firstScenarioWith400Response = failureResults.flatMap { it.results }.filter {
                it is Result.Failure
                    && it.failureReason == null
                    && it.scenario?.let { it.status == 400 || it.status == 422 } == true
            }.map { it.scenario!! }.firstOrNull()

            if (firstScenarioWith400Response != null && specmaticConfig.getStubGenerative()) {
                val httpResponse = (firstScenarioWith400Response as Scenario).generateHttpResponse(emptyMap())
                val updatedResponse: HttpResponse = dumpIntoFirstAvailableStringField(httpResponse, combinedFailureResult.report())

                FoundStubbedResponse(
                    HttpStubResponse(
                        updatedResponse,
                        contractPath = "",
                        feature = fakeResponse?.feature,
                        scenario = fakeResponse?.successResponse?.scenario
                    )
                )
            } else {
                val httpFailureResponse = combinedFailureResult.generateErrorHttpResponse(httpRequest)
                val nearestScenario = features.firstNotNullOfOrNull { it.identifierMatchingScenario(httpRequest) }
                NotStubbed(HttpStubResponse(httpFailureResponse, scenario = nearestScenario))
            }
        }

        else -> FoundStubbedResponse(
            HttpStubResponse(
                generateHttpResponseFrom(fakeResponse, httpRequest),
                contractPath = fakeResponse.feature.path,
                feature = fakeResponse.feature,
                scenario = fakeResponse.successResponse?.scenario
            )
        )
    }
}

fun responseDetailsFrom(features: List<Feature>, httpRequest: HttpRequest): List<ResponseDetails> {
    return features.asSequence().map { feature ->
        feature.stubResponse(httpRequest, ContractAndRequestsMismatch).let {
            ResponseDetails(feature, it.first, it.second)
        }
    }.toList()
}

fun List<ResponseDetails>.successResponse(): ResponseDetails? {
    return this.find { it.successResponse != null }
}

fun generateHttpResponseFrom(
    fakeResponse: ResponseDetails,
    httpRequest: HttpRequest,
    withoutSpecmaticTypeHeader: Boolean = false
): HttpResponse {
    return fakeResponse.successResponse?.build(RequestContext(httpRequest))?.withRandomResultHeader()!!.let {
        if(withoutSpecmaticTypeHeader) it.withoutSpecmaticTypeHeader()
        else it
    }
}

fun dumpIntoFirstAvailableStringField(httpResponse: HttpResponse, stringValue: String): HttpResponse {
    val responseBody = httpResponse.body

    if(responseBody !is JSONObjectValue)
        return httpResponse

    val newBody = dumpIntoFirstAvailableStringField(responseBody, stringValue)

    return httpResponse.copy(body = newBody)
}

fun dumpIntoFirstAvailableStringField(jsonObjectValue: JSONObjectValue, stringValue: String): JSONObjectValue {
    val key = jsonObjectValue.jsonObject.keys.find { key ->
        key == "message" && jsonObjectValue.jsonObject[key] is StringValue
    } ?: jsonObjectValue.jsonObject.keys.find { key ->
        jsonObjectValue.jsonObject[key] is StringValue
    }

    if(key != null)
        return jsonObjectValue.copy(
            jsonObject = jsonObjectValue.jsonObject.plus(
                key to StringValue(stringValue)
            )
        )

    val newMap = jsonObjectValue.jsonObject.mapValues { (key, value) ->
        when (value) {
            is JSONObjectValue -> {
                dumpIntoFirstAvailableStringField(value, stringValue)
            }

            is JSONArrayValue -> {
                dumpIntoFirstAvailableStringField(value, stringValue)
            }

            else -> {
                value
            }
        }
    }

    return jsonObjectValue.copy(jsonObject = newMap)
}

fun dumpIntoFirstAvailableStringField(jsonArrayValue: JSONArrayValue, stringValue: String): JSONArrayValue {
    val indexOfFirstStringValue = jsonArrayValue.list.indexOfFirst { it is StringValue }

    if(indexOfFirstStringValue >= 0) {
        val mutableList = jsonArrayValue.list.toMutableList()
        mutableList.add(indexOfFirstStringValue, StringValue(stringValue))

        return jsonArrayValue.copy(
            list = mutableList
        )
    }

    val newList = jsonArrayValue.list.map { value ->
        when (value) {
            is JSONObjectValue -> {
                dumpIntoFirstAvailableStringField(value, stringValue)
            }

            is JSONArrayValue -> {
                dumpIntoFirstAvailableStringField(value, stringValue)
            }

            else -> {
                value
            }
        }
    }

    return jsonArrayValue.copy(list = newList)
}

private fun strictModeHttp400Response(
    httpRequest: HttpRequest,
    matchResults: List<Pair<Result, HttpStubData>>
): HttpResponse {
    val failureResults = matchResults.map { it.first }

    val results = Results(failureResults).withoutFluff()
    return HttpResponse(
        400,
        headers = mapOf(SPECMATIC_RESULT_HEADER to "failure"),
        body = StringValue("STRICT MODE ON${System.lineSeparator()}${System.lineSeparator()}${results.strictModeReport(httpRequest)}")
    )
}

fun stubResponse(
    httpRequest: HttpRequest,
    contractInfo: List<Pair<Feature, List<ScenarioStub>>>,
    stubs: StubDataItems
): HttpResponse {
    return try {
        when (val mock = stubs.http.find { (requestPattern, _, resolver) ->
            requestPattern.matches(httpRequest, resolver.disableOverrideUnexpectedKeycheck()) is Result.Success
        }) {
            null -> {
                val responses = contractInfo.asSequence().map { (feature, _) ->
                    feature.lookupResponse(httpRequest)
                }

                responses.firstOrNull {
                    it.headers.getOrDefault(SPECMATIC_RESULT_HEADER, "none") != "failure"
                } ?: HttpResponse(400, responses.map {
                    it.body
                }.filter { it != EmptyString }.joinToString("\n\n"))
            }

            else -> mock.response
        }
    } finally {
        contractInfo.forEach { (feature, _) ->
            feature.clearServerState()
        }
    }
}

fun contractInfoToHttpExpectations(contractInfo: List<Pair<Feature, List<ScenarioStub>>>): List<HttpStubData> {
    return contractInfo.flatMap { (feature, examples) ->
        examples.map { example ->
            feature.matchingStub(example, ContractAndStubMismatchMessages) to example
        }.flatMap { (stubData, example) ->
            val examplesWithDataSubstitutionsResolved = try {
                example.resolveDataSubstitutions()
            } catch(e: Throwable) {
                println()
                logger.log("    Error resolving template data for example ${example.filePath}")
                logger.log("    " + exceptionCauseMessage(e))
                throw e
            }

            examplesWithDataSubstitutionsResolved.map {
                feature.matchingStub(it, ContractAndStubMismatchMessages)
            }
        }
    }
}

fun badRequest(errorMessage: String?): HttpResponse {
    return HttpResponse(HttpStatusCode.BadRequest.value, errorMessage, mapOf(SPECMATIC_RESULT_HEADER to "failure"))
}

fun internalServerError(errorMessage: String?): HttpResponse {
    return HttpResponse(
        HttpStatusCode.InternalServerError.value,
        errorMessage,
        mapOf(SPECMATIC_RESULT_HEADER to "failure")
    )
}

internal fun httpResponseLog(response: HttpResponse): String =
    "${response.toLogString("<- ")}\n<< Response At ${Date()} == "

internal fun httpRequestLog(httpRequest: HttpRequest): String =
    ">> Request Start At ${Date()}\n${httpRequest.toLogString("-> ")}"

fun endPointFromHostAndPort(host: String, port: Int?, keyData: KeyData?): String {
    val protocol = when (keyData) {
        null -> "http"
        else -> "https"
    }

    val computedPortString = when (port) {
        80, null -> ""
        else -> ":$port"
    }

    return "$protocol://$host$computedPortString"
}

fun extractHost(url: String): String {
    return URI(url).host
}

fun extractPort(url: String): Int {
    return resolvedPort(URI.create(url))
}

fun normalizeHost(host: String): String {
    return try {
        InetAddress.getByName(host).hostAddress
    } catch (e: Exception) {
        host
    }
}

fun isSameBaseIgnoringHost(base: URI, other: URI): Boolean {
    val basePort = resolvedPort(base)
    val otherPort = resolvedPort(other)
    return base.scheme == other.scheme && basePort == otherPort && base.path.startsWith(other.path)
}

private fun resolvedPort(uri: URI): Int {
    return when (uri.scheme) {
        "http" -> uri.port.takeUnless { it == -1 } ?: HTTP_PORT
        "https" -> uri.port.takeUnless { it == -1 } ?: HTTPS_PORT
        else -> uri.port
    }
}

fun validateBaseUrls(specToBaseUrlMap: Map<String, String>): Result {
    val results = specToBaseUrlMap.map { (contractPath, baseUrl) ->
        when (val result = validateTestOrStubUri(baseUrl)) {
            URIValidationResult.Success -> Result.Success()
            else -> Result.Failure(
                breadCrumb = "Invalid baseURL \"$baseUrl\" for $contractPath",
                message = result.message
            )
        }
    }

    return Result.fromResults(results)
}

internal fun isPath(path: String?, lastPart: String): Boolean {
    return path == "/_$APPLICATION_NAME_LOWER_CASE/$lastPart"
}

internal fun isFetchLogRequest(httpRequest: HttpRequest): Boolean =
    isPath(httpRequest.path, "log") && httpRequest.method == "GET"

internal fun isFetchContractsRequest(httpRequest: HttpRequest): Boolean =
    isPath(httpRequest.path, "contracts") && httpRequest.method == "GET"

internal fun isFetchLoadLogRequest(httpRequest: HttpRequest): Boolean =
    isPath(httpRequest.path, "load_log") && httpRequest.method == "GET"

internal fun isExpectationCreation(httpRequest: HttpRequest) =
    isPath(httpRequest.path, "expectations") && httpRequest.method == "POST"

internal fun isSseExpectationCreation(httpRequest: HttpRequest) =
    isPath(httpRequest.path, "sse-expectations") && httpRequest.method == "POST"

internal fun isStateSetupRequest(httpRequest: HttpRequest): Boolean =
    isPath(httpRequest.path, "state") && httpRequest.method == "POST"

fun softCastResponseToXML(mockResponse: HttpStubData): HttpStubData =
    mockResponse.copy(response = mockResponse.response.copy(body = softCastValueToXML(mockResponse.response.body)))

fun softCastValueToXML(body: Value): Value {
    return when (body) {
        is StringValue -> try {
            toXMLNode(body.string)
        } catch (e: Throwable) {
            body
        }

        else -> body
    }
}

fun stringToMockScenario(text: Value): ScenarioStub {
    val mockSpec: Map<String, Value> =
        jsonStringToValueMap(text.toStringLiteral()).also {
            validateMock(it)
        }

    return mockFromJSON(mockSpec)
}

data class SseEvent(
    val data: String? = "",
    val event: String? = null,
    val id: String? = null,
    val bufferIndex: Int? = null
)

suspend fun ApplicationCall.respondSse(
    events: ReceiveChannel<SseEvent>,
    sseBuffer: SSEBuffer,
    httpRequest: HttpRequest
) {
    response.cacheControl(CacheControl.NoCache(null))

    respondTextWriter(contentType = ContentType.Text.EventStream) {
        logger.log("Writing out an initial response for subscription to ${httpRequest.path!!}")
        withContext(Dispatchers.IO) {
            write("\n")
            flush()
        }

        logger.log("Writing out buffered events for subscription to ${httpRequest.path}")
        sseBuffer.write(this)

        logger.log("Awaiting events...")
        for (event in events) {
            sseBuffer.add(event)
            logger.log("Writing out event for subscription to ${httpRequest.path}")
            logger.log("Event details: $event")

            writeEvent(event, this)
        }
    }
}

fun writeEvent(event: SseEvent, writer: Writer) {
    if (event.id != null) {
        writer.write("id: ${event.id}\n")
    }
    if (event.event != null) {
        writer.write("event: ${event.event}\n")
    }
    if (event.data != null) {
        for (dataLine in event.data.lines()) {
            writer.write("data: $dataLine\n")
        }
    }

    writer.write("\n")
    writer.flush()
}
