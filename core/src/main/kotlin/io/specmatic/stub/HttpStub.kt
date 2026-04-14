package io.specmatic.stub

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.swagger.v3.core.util.Yaml
import io.specmatic.core.APPLICATION_NAME
import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.IncomingMtlsRegistry
import io.specmatic.core.KeyData
import io.specmatic.core.KeyDataRegistry
import io.specmatic.core.MismatchMessages
import io.specmatic.core.MissingDataException
import io.specmatic.core.MultiPartContent
import io.specmatic.core.MultiPartContentValue
import io.specmatic.core.MultiPartFileValue
import io.specmatic.core.MultiPartFormDataValue
import io.specmatic.core.NoBodyValue
import io.specmatic.core.QueryParameters
import io.specmatic.core.ResponseBuilder
import io.specmatic.core.Result
import io.specmatic.core.Results
import io.specmatic.core.SPECMATIC_EMPTY_HEADER
import io.specmatic.core.SPECMATIC_RESULT_HEADER
import io.specmatic.core.Scenario
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.examples.server.ExampleMismatchMessages
import io.specmatic.core.invalidRequestStatuses
import io.specmatic.core.listOfExcludedHeaders
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.LogTail
import io.specmatic.core.log.NewLineLogMessage
import io.specmatic.core.log.StringLog
import io.specmatic.core.log.consoleLog
import io.specmatic.core.log.dontPrintToConsole
import io.specmatic.core.log.logger
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.report.ReportGenerator
import io.specmatic.core.route.modules.HealthCheckModule.Companion.configureHealthCheckModule
import io.specmatic.core.route.modules.HealthCheckModule.Companion.isHealthCheckRequest
import io.specmatic.core.urlDecodePathSegments
import io.specmatic.core.utilities.URIValidationResult
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.core.utilities.saveJsonFile
import io.specmatic.core.utilities.toMap
import io.specmatic.core.utilities.validateTestOrStubUri
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.toXMLNode
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.license.core.util.LicenseConfig
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.mock.TRANSIENT_MOCK
import io.specmatic.reporter.generated.dto.stub.usage.SpecmaticStubUsageReport
import io.specmatic.reporter.internal.dto.stub.usage.merge
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener
import io.specmatic.stub.report.OpenApiMockUsage
import io.specmatic.stub.report.StubEndpoint
import io.specmatic.stub.report.StubUsageReport
import io.specmatic.test.LegacyHttpClient
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestResultRecord.Companion.STUB_TEST_TYPE
import io.specmatic.test.normalizedContentType
import io.specmatic.test.internalHeadersToKtorHeaders
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.specmatic.core.utilities.FileAssociation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.Charset
import java.time.Instant
import java.util.*
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.text.toCharArray

const val SPECMATIC_RESPONSE_CODE_HEADER = "Specmatic-Response-Code"
const val HTTP_PORT = 80
const val HTTPS_PORT = 443
const val JSON_REPORT_PATH = "./build/reports/specmatic"
const val JSON_REPORT_FILE_NAME = "stub_usage_report.json"
private const val SWAGGER_SPEC_NOT_AVAILABLE_MESSAGE = "No OpenAPI specifications are loaded in this mock server"

class HttpStub(
    private val features: List<Feature>,
    rawHttpStubs: List<HttpStubData> = emptyList(),
    val host: String = "127.0.0.1",
    private val port: Int = 9000,
    private val log: (event: LogMessage) -> Unit = dontPrintToConsole,
    private val strictMode: Boolean = false,
    val keyDataRegistry: KeyDataRegistry = KeyDataRegistry.empty(),
    val incomingMtlsRegistry: IncomingMtlsRegistry = IncomingMtlsRegistry.empty(),
    val passThroughTargetBase: String = "",
    val httpClientFactory: HttpClientFactory = HttpClientFactory(),
    val workingDirectory: WorkingDirectory? = null,
    specmaticConfigSource: SpecmaticConfigSource = SpecmaticConfigSource.None,
    private val timeoutMillis: Long = 0,
    private val specToStubBaseUrlMap: Map<String, String?> = features.associate {
        it.path to endPointFromHostAndPort(host, port, keyDataRegistry.hasAny())
    },
    private val listeners: List<MockEventListener> = emptyList(),
    private val reportBaseDirectoryPath: String = ".",
    private val startTime: Instant = Instant.now(),
    var requestHandlers: MutableList<RequestHandler> = mutableListOf()
) : ContractStub {
    private val incomingMtlsSslContextsByPort: MutableMap<Int, SslContext> = mutableMapOf()
    private val mtlsEnabledByPort: MutableMap<Int, Boolean> = mutableMapOf()

    private enum class SwaggerSpecResponseFormat {
        YAML,
        JSON
    }
    private data class MockedOpenApiSpec(
        val resolvedSpecPath: String,
        val displaySpecId: String
    )

    constructor(
        feature: Feature,
        scenarioStubs: List<ScenarioStub> = emptyList(),
        host: String = "localhost",
        port: Int = 9000,
        log: (event: LogMessage) -> Unit = dontPrintToConsole,
        specToStubBaseUrlMap: Map<String, String> = mapOf(
            feature.path to endPointFromHostAndPort(host, port, null)
        ),
        listeners: List<MockEventListener> = emptyList(),
    ) : this(
        listOf(feature),
        contractInfoToHttpExpectations(listOf(Pair(feature, scenarioStubs))),
        host,
        port,
        log,
        specToStubBaseUrlMap = specToStubBaseUrlMap,
        listeners = listeners,
    )

    constructor(
        gherkinData: String,
        scenarioStubs: List<ScenarioStub> = emptyList(),
        host: String = "localhost",
        port: Int = 9000,
        log: (event: LogMessage) -> Unit = dontPrintToConsole,
    ) : this(
        parseGherkinStringToFeature(gherkinData),
        scenarioStubs,
        host,
        port,
        log,
        specToStubBaseUrlMap = mapOf(
            parseGherkinStringToFeature(gherkinData).path to endPointFromHostAndPort(host, port, null)
        ),
    )

    companion object {
        fun setExpectation(
            stub: ScenarioStub,
            feature: Feature,
            mismatchMessages: MismatchMessages = ExampleMismatchMessages
        ): Pair<Pair<Result.Success, List<HttpStubData>>?, NoMatchingScenario?> {
            try {
                val tier1Match = feature.matchingStub(stub, mismatchMessages)
                tier1Match.scenario ?: throw ContractException("Expected scenario after stub matched for:${System.lineSeparator()}${stub.toJSON()}")
                val stubData = softCastResponseToXML(tier1Match)
                return Pair(Pair(Result.Success(), listOf(stubData)), null)
            } catch (e: NoMatchingScenario) {
                return Pair(null, e)
            }
        }
    }

    private val loadedSpecmaticConfig = specmaticConfigSource.load()
    private val specmaticConfigInstance: SpecmaticConfig = loadedSpecmaticConfig.config
    private val prettyPrint = specmaticConfigInstance.getPrettyPrint()
    val specmaticConfigPath: String? = loadedSpecmaticConfig.path

    private val ctrfTestResultRecords = mutableListOf<TestResultRecord>()

    val specToBaseUrlMap: Map<String, String> = getValidatedBaseUrlsOrExit(
        features.associate {
            val baseUrl = specToStubBaseUrlMap[it.path] ?: endPointFromHostAndPort(host, port, keyDataRegistry.hasAny())
            it.path to baseUrl
        }
    )

    private val httpExpectations: HttpExpectations = HttpExpectations(
        static = staticHttpStubData(rawHttpStubs),
        transient = rawHttpStubs.filter { it.stubToken != null }.reversed().toMutableList(),
        specToBaseUrlMap = specToBaseUrlMap
    )
    private val firstMockedOpenApiSpec: MockedOpenApiSpec? by lazy {
        features.asSequence()
            .filter { it.path.isNotBlank() }
            .firstOrNull { isOpenAPI(it.path, logFailure = false) }
            ?.let { feature ->
                val resolvedSpecPath = feature.path
                val displaySpecId = File(resolvedSpecPath).name.ifBlank { feature.name }
                MockedOpenApiSpec(
                    resolvedSpecPath = resolvedSpecPath,
                    displaySpecId = displaySpecId
                )
            }
    }

    // used by graphql/plugins
    fun ctrfTestResultRecords() = ctrfTestResultRecords.toList()

    //used by graphql / plugins
    fun registerHandler(requestHandler: RequestHandler) {
        requestHandlers.add(requestHandler)
    }

    private fun staticHttpStubData(rawHttpStubs: List<HttpStubData>): MutableList<HttpStubData> {
        val staticStubs = rawHttpStubs.filter { it.stubToken == null }

        val stubsFromSpecificationExamples: List<HttpStubData> = features.map {
            it.loadInlineExamplesAsStub()
        }.flatten().mapNotNull {
            it.realise(
                hasValue = { stubData, _ -> stubData },
                orFailure = { null },
                orException = { null }
            )
        }

        return staticStubs.plus(stubsFromSpecificationExamples).toMutableList()
    }

    private val _logs: MutableList<StubEndpoint> = Collections.synchronizedList(ArrayList())
    private val _allEndpoints: List<StubEndpoint> = extractAllEndpoints()

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

    val endPoint = endPointFromHostAndPort(host, port, keyDataRegistry.hasAny())

    override val client = LegacyHttpClient(this.endPoint)

    private val sseBuffer: SSEBuffer = SSEBuffer()

    private val broadcastChannels: Vector<BroadcastChannel<SseEvent>> = Vector(50, 10)

    private val requestInterceptors: MutableList<FileAssociation<RequestInterceptor>> = mutableListOf()

    private val responseInterceptors: MutableList<FileAssociation<ResponseInterceptor>> = mutableListOf()

    fun registerRequestInterceptor(requestInterceptor: RequestInterceptor) {
        registerRequestInterceptor(FileAssociation.Global(requestInterceptor))
    }

    fun registerResponseInterceptor(responseInterceptor: ResponseInterceptor) {
        registerResponseInterceptor(FileAssociation.Global(responseInterceptor))
    }

    fun registerRequestInterceptor(requestInterceptor: FileAssociation<RequestInterceptor>) {
        if (!requestInterceptors.contains(requestInterceptor)) {
            requestInterceptors.add(requestInterceptor)
        }
    }

    fun registerResponseInterceptor(responseInterceptor: FileAssociation<ResponseInterceptor>) {
        if (!responseInterceptors.contains(responseInterceptor)) {
            responseInterceptors.add(responseInterceptor)
        }
    }

    private val environment = applicationEngineEnvironment {
        module {
            install(DoubleReceive)
            configure(CORS)

            intercept(ApplicationCallPipeline.Call) {
                val httpLogMessage = HttpLogMessage(
                    targetServer = targetServerForPort(call.request.local.localPort),
                    prettyPrint = prettyPrint,
                )
                var transformedResponse: HttpResponse? = null
                var swaggerSpecSummary: String? = null
                var shouldRedactSwaggerSpecResponseInLogs = false
                var responseInterceptorResults: List<InterceptorResult<HttpResponse>> = emptyList()

                try {
                    val requestBaseUrl = "${call.request.local.scheme}://${call.request.local.localHost}:${call.request.local.localPort}"
                    val requestUrlPath = call.request.path()
                    val defaultBaseUrl = endPointFromHostAndPort(host, port, keyDataRegistry.hasAny())
                    val rawHttpRequest = ktorHttpRequestToHttpRequest(call).also {
                        if (it.isHealthCheckRequest()) return@intercept
                    }

                    val (httpRequest, requestInterceptorResults) = applyRequestInterceptors(
                        rawHttpRequest = rawHttpRequest,
                        baseUrl = requestBaseUrl,
                        defaultBaseUrl = defaultBaseUrl,
                        urlPath = requestUrlPath
                    )
                    val requestInterceptorErrors = requestInterceptorResults.flatMap { it.errors }

                    LicenseResolver.utilize(
                        product = LicensedProduct.OPEN_SOURCE,
                        feature = SpecmaticFeature.MOCK,
                        protocol = listOf(httpRequest.protocol),
                    )

                    // Add the decoded request to the log message
                    httpLogMessage.addRequestWithCurrentTime(httpRequest)

                    // Log the original request if it was transformed
                    if (httpRequest != rawHttpRequest) {
                        logger.log("")
                        logger.log("--------------------")
                        logger.log("Request before hook processing:")
                        logger.log(rawHttpRequest.toLogString(prettyPrint = prettyPrint).prependIndent("  "))
                    }

                    // Log request hook responseInterceptorErrors if any occurred
                    if (requestInterceptorErrors.isNotEmpty()) {
                        logger.boundary()
                        logger.log("--------------------")
                        logger.log("Request adapter errors:")
                        logger.log(InterceptorErrors(requestInterceptorErrors).toString().prependIndent("  "))
                    }

                    if(requestInterceptorResults.isNotEmpty()) {
                        httpLogMessage.addPreHookRequestWithCurrentTime(
                            requestInterceptorResults.first().copy(
                                errors = requestInterceptorErrors
                            )
                        )
                    }

                    val responseFromRequestHandler =
                        requestHandlers.firstNotNullOfOrNull { it.handleRequest(httpRequest) }
                    val httpStubResponse: HttpStubResponse = when {
                        isFetchLogRequest(httpRequest) -> handleFetchLogRequest().copy(isInternalStubPath = true)
                        isFetchLoadLogRequest(httpRequest) -> handleFetchLoadLogRequest().copy(isInternalStubPath = true)
                        isFetchContractsRequest(httpRequest) -> handleFetchContractsRequest().copy(isInternalStubPath = true)
                        isSwaggerSpecRequest(httpRequest) -> {
                            shouldRedactSwaggerSpecResponseInLogs = true
                            handleFetchSwaggerSpecRequest(httpRequest).also {
                                swaggerSpecSummary = if (it.response.status == HttpStatusCode.Conflict.value) {
                                    SWAGGER_SPEC_NOT_AVAILABLE_MESSAGE
                                } else {
                                    val specPath = it.contractPath.takeIf(String::isNotBlank) ?: "unknown-openapi-spec"
                                    "(specification in $specPath was returned)"
                                }
                            }.copy(isInternalStubPath = true)
                        }
                        responseFromRequestHandler != null -> responseFromRequestHandler
                        isExpectationCreation(httpRequest) -> handleExpectationCreationRequest(httpRequest).copy(isInternalStubPath = true)
                        isSseExpectationCreation(httpRequest) -> handleSseExpectationCreationRequest(httpRequest).copy(isInternalStubPath = true)
                        isStateSetupRequest(httpRequest) -> handleStateSetupRequest(httpRequest).copy(isInternalStubPath = true)
                        isFlushTransientStubsRequest(httpRequest) -> handleFlushTransientStubsRequest(httpRequest).copy(isInternalStubPath = true)
                        else -> {
                            val responseResult = serveStubResponse(httpRequest, baseUrl = requestBaseUrl, defaultBaseUrl = defaultBaseUrl, urlPath = requestUrlPath)
                            if (responseResult is NotStubbed) httpLogMessage.addResult(responseResult.stubResult)
                            responseResult.response
                        }
                    }


                    val (httpResponse, responseInterceptorResultsList) = applyResponseInterceptors(
                        httpRequest = httpRequest,
                        httpResponse = httpStubResponse.response,
                        specFile = httpStubResponse.contractPath.takeUnless(String::isBlank)?.let(::File)
                    )
                    responseInterceptorResults = responseInterceptorResultsList

                    // Store encoded response for later logging if different
                    transformedResponse =
                        if (httpResponse != httpStubResponse.response) httpResponse.adjustPayloadForContentType() else null

                    if (httpRequest.path!!.startsWith("""/features/default""")) {
                        handleSse(httpRequest, this@HttpStub, this)
                    } else {
                        val updatedHttpStubResponse = httpStubResponse.copy(response = httpResponse)
                        respondToKtorHttpResponse(
                            call,
                            updatedHttpStubResponse.response,
                            updatedHttpStubResponse.delayInMilliSeconds,
                            specmaticConfigInstance,
                            updatedHttpStubResponse.contractPath
                        )
                        // Add the original response (before encoding) to the log message
                        val stubResponseToLog = if (shouldRedactSwaggerSpecResponseInLogs) {
                            val summary = swaggerSpecSummary ?: "returned spec"
                            httpStubResponse.copy(response = redactedSwaggerSpecResponse(httpStubResponse.response, summary))
                        } else {
                            httpStubResponse
                        }
                        httpLogMessage.addResponse(stubResponseToLog)
                    }

                    if (httpStubResponse.isInternalStubPath.not()) {
                        addCtrfTestResultRecord(httpLogMessage, httpRequest, httpResponse, httpStubResponse)
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

                swaggerSpecSummary?.let { log(StringLog(it)) }
                log(httpLogMessage)

                val responseErrors = responseInterceptorResults.flatMap { it.errors }

                // Log the response after hook processing if it was transformed
                if (!shouldRedactSwaggerSpecResponseInLogs) transformedResponse?.let {
                    logger.log("")
                    logger.log("--------------------")
                    logger.log("Response after adapter processing:")
                    logger.log(it.toLogString(prettyPrint = prettyPrint).prependIndent("  "))
                }

                // Log response hook errors if any occurred
                if (responseErrors.isNotEmpty()) {
                    logger.boundary()
                    logger.log("--------------------")
                    logger.log("Response adapter errors:")
                    logger.log(InterceptorErrors(responseErrors).toString().prependIndent("  "))
                }

                if (responseInterceptorResults.isNotEmpty()) {
                    httpLogMessage.addPostHookResponseWithCurrentTime(
                        responseInterceptorResults.last().copy(
                            processedValue = transformedResponse,
                            errors = responseErrors
                        )
                    )
                }

                if (!httpLogMessage.isInternalControlRequestForMockEvent()) {
                    MockEvent(httpLogMessage).let { event -> listeners.forEach { it.onRespond(event) } }
                }
            }

            configureHealthCheckModule()
        }

        configureHostPorts()
    }

    fun getServerBinds(): Set<Pair<String, Int>> {
        return environment.connectors.map { it.host to it.port }.toSet()
    }

    private fun addCtrfTestResultRecord(
        httpLogMessage: HttpLogMessage,
        httpRequest: HttpRequest,
        httpResponse: HttpResponse,
        httpStubResponse: HttpStubResponse
    ) {
        val path = convertPathParameterStyle(httpLogMessage.scenario?.path ?: httpRequest.path.orEmpty())
        val method = httpLogMessage.scenario?.method ?: httpRequest.method.orEmpty()
        val requestContentType = httpLogMessage.scenario?.requestContentType
            ?: httpRequest.headers["Content-Type"]
        val responseStatus = httpLogMessage.scenario?.status ?: 0
        val protocol = httpLogMessage.scenario?.protocol ?: SpecmaticProtocol.HTTP
        val ctrfTestResultRecord = TestResultRecord(
            path = path,
            method = method,
            responseStatus = responseStatus,
            responseContentType = httpLogMessage.scenario?.httpResponsePattern?.headersPattern?.contentType,
            request = httpRequest,
            response = httpResponse,
            result = httpLogMessage.toResult(),
            scenarioResult = (httpLogMessage.result ?: Result.Success()).updateScenario(httpLogMessage.scenario),
            specType = httpLogMessage.scenario?.specType ?: SpecType.OPENAPI,
            requestContentType = requestContentType,
            specification = httpStubResponse.scenario?.specification,
            testType = STUB_TEST_TYPE,
            actualResponseStatus = httpResponse.status,
            actualResponseContentType = httpResponse.normalizedContentType(),
            operations = setOf(
                OpenAPIOperation(
                    path = path,
                    method = method,
                    contentType = requestContentType,
                    responseCode = responseStatus,
                    protocol = protocol,
                    responseContentType = httpLogMessage.scenario?.httpResponsePattern?.headersPattern?.contentType,
                )
            ),
            exampleId = httpStubResponse.mock?.scenarioStub?.id
        )
        synchronized(ctrfTestResultRecords) { ctrfTestResultRecords.add(ctrfTestResultRecord) }
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
        mtlsEnabledByPort.clear()
        validateTlsConfigurationByPort(hostPortList)
        validateIncomingMtlsConfigurationByPort(hostPortList)
        hostPortList.forEach { (host, port) ->
            recordIncomingMtlsByPort(port, incomingMtlsRegistry.get(host, port))
        }

        connectors.addAll(hostPortList.map { (host, port) ->
            val mtlsEnabled = incomingMtlsRegistry.get(host, port)
            val keyData = keyDataRegistry.get(host, port) ?: return@map EngineConnectorBuilder().also { it.host = host; it.port = port }
            if (mtlsEnabled) {
                incomingMtlsSslContextsByPort[port] = incomingMtlsServerContext(keyData)
                return@map EngineConnectorBuilder().also { it.host = host; it.port = port }
            }
            EngineSSLConnectorBuilder(
                keyStore = keyData.keyStore,
                keyAlias = keyData.keyAlias,
                privateKeyPassword = { keyData.keyPassword.toCharArray() },
                keyStorePassword = { keyData.keyStorePassword.toCharArray() }
            ).also { it.host = host; it.port = port }
        })

        val portsWithIncomingMtlsButNoHttps = hostPortList.filter { (host, port) ->
            incomingMtlsRegistry.get(host, port) && keyDataRegistry.get(host, port) == null
        }.map { (_, port) -> port }
        if (portsWithIncomingMtlsButNoHttps.isNotEmpty()) {
            throw ContractException(
                "Incoming mTLS is enabled, but no HTTPS key material is configured for ports: ${portsWithIncomingMtlsButNoHttps.joinToString(", ")}"
            )
        }
    }

    private fun applyRequestInterceptors(rawHttpRequest: HttpRequest, baseUrl: String, defaultBaseUrl: String, urlPath: String): Pair<HttpRequest, List<InterceptorResult<HttpRequest>>> {
        val global = requestInterceptors.asSequence().filterIsInstance<FileAssociation.Global<RequestInterceptor>>().map { it.data }
        val (updatedRequest, globalResults) = applyInterceptors(rawHttpRequest, global) { interceptor, req ->
            interceptor.interceptRequestAndReturnErrors(req)
        }

        val specLevel = matchingInterceptors(
            httpRequest = updatedRequest,
            interceptors = requestInterceptors,
            baseUrl = baseUrl,
            defaultBaseUrl = defaultBaseUrl,
            urlPath = urlPath
        )

        return applyInterceptors(updatedRequest, specLevel, globalResults) { interceptor, req ->
            interceptor.interceptRequestAndReturnErrors(req)
        }
    }

    private fun applyResponseInterceptors(httpRequest: HttpRequest, httpResponse: HttpResponse, specFile: File?): Pair<HttpResponse, List<InterceptorResult<HttpResponse>>> {
        val specLevel = if (specFile != null) responseInterceptors.filterMatching(specFile) else emptySequence()
        val (specUpdatedResponse, specResult) = applyInterceptors(httpResponse, specLevel) { interceptor, req ->
            interceptor.interceptResponseAndReturnErrors(httpRequest, req)
        }

        val global = responseInterceptors.asSequence().filterIsInstance<FileAssociation.Global<ResponseInterceptor>>().map { it.data }
        return applyInterceptors(specUpdatedResponse, global, specResult) { interceptor, req ->
            interceptor.interceptResponseAndReturnErrors(httpRequest, req)
        }
    }

    private fun <T> matchingInterceptors(httpRequest: HttpRequest, interceptors: List<FileAssociation<T>>, baseUrl: String, defaultBaseUrl: String, urlPath: String): Sequence<T> {
        val feature = matchingFeatureForInterceptors(httpRequest = httpRequest, baseUrl = baseUrl, defaultBaseUrl = defaultBaseUrl, urlPath = urlPath) ?: return emptySequence()
        return interceptors.filterMatching(File(feature.path))
    }

    private fun matchingFeatureForInterceptors(httpRequest: HttpRequest, baseUrl: String, defaultBaseUrl: String, urlPath: String): Feature? {
        val url = "$baseUrl$urlPath"
        val stubBaseUrlPath = specmaticConfigInstance.stubBaseUrlPathAssociatedTo(url, defaultBaseUrl)
        val trimmedRequest = httpRequest.trimBaseUrlPath(stubBaseUrlPath)
        val candidateFeatures = featuresAssociatedTo(baseUrl, features, specToBaseUrlMap, urlPath)
        return candidateFeatures.firstOrNull { it.identifierMatchingScenario(trimmedRequest) != null }
    }

    private fun <T, I> applyInterceptors(initial: T, interceptors: Sequence<I>, results: List<InterceptorResult<T>> = emptyList(), run: (I, T) -> InterceptorResult<T>): Pair<T, List<InterceptorResult<T>>> {
        return interceptors.fold(initial to results) { (value, res), interceptor ->
            val result = run(interceptor, value)
            (result.processedValue ?: value) to res + result
        }
    }

    private fun <T> List<FileAssociation<T>>.filterMatching(file: File): Sequence<T> {
        return asSequence().filterIsInstance<FileAssociation.FileScoped<T>>().filter { it.matches(file) }.map { it.data }
    }

    private fun targetServerForPort(port: Int): String {
        val transportSuffix = if (mtlsEnabledByPort[port] == true) " (mTLS)" else ""

        return "port '$port'$transportSuffix"
    }

    private fun validateTlsConfigurationByPort(hostPortList: List<Pair<String, Int>>) {
        val tlsByPort = hostPortList.groupBy(
            keySelector = { (_, port) -> port },
            valueTransform = { (host, port) -> keyDataRegistry.get(host, port) != null }
        )
        val portsWithMixedTlsMode = tlsByPort.filterValues { it.distinct().size > 1 }.keys
        if (portsWithMixedTlsMode.isNotEmpty()) {
            throw ContractException(
                "Transport mode configuration is ambiguous for ports: ${portsWithMixedTlsMode.joinToString(", ")}. Please ensure all host mappings for a shared port use the same transport mode."
            )
        }
    }

    private fun validateIncomingMtlsConfigurationByPort(hostPortList: List<Pair<String, Int>>) {
        val mtlsByPort = hostPortList.groupBy(
            keySelector = { (_, port) -> port },
            valueTransform = { (host, port) -> incomingMtlsRegistry.get(host, port) }
        )
        val portsWithMixedMtlsMode = mtlsByPort.filterValues { it.distinct().size > 1 }.keys
        if (portsWithMixedMtlsMode.isNotEmpty()) {
            throw ContractException(
                "Incoming mTLS configuration is ambiguous for ports: ${portsWithMixedMtlsMode.joinToString(", ")}. Please ensure all host mappings for a shared port use the same incoming mTLS setting."
            )
        }
    }

    private fun recordIncomingMtlsByPort(port: Int, mtlsEnabled: Boolean) {
        val existingMode = mtlsEnabledByPort[port]
        if (existingMode == null || existingMode == mtlsEnabled) {
            mtlsEnabledByPort[port] = mtlsEnabled
            return
        }

        throw ContractException(
            "Incoming mTLS configuration is ambiguous for port $port. Please ensure all host mappings for a shared port use the same incoming mTLS setting."
        )
    }

    private fun incomingMtlsServerContext(keyData: KeyData): SslContext {
        @Suppress("UNCHECKED_CAST")
        val certificateChain = keyData.keyStore.getCertificateChain(keyData.keyAlias).toList() as List<X509Certificate>
        val privateKey = keyData.keyStore.getKey(keyData.keyAlias, keyData.keyPassword.toCharArray()) as PrivateKey
        val sslContextBuilder = SslContextBuilder.forServer(privateKey, *certificateChain.toTypedArray())
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .clientAuth(ClientAuth.REQUIRE)

        findAlpnProvider()?.let { alpnProvider ->
            sslContextBuilder.sslProvider(alpnProvider)
            sslContextBuilder.ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            sslContextBuilder.applicationProtocolConfig(
                ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1
                )
            )
        }

        return sslContextBuilder.build()
    }

    private fun findAlpnProvider(): SslProvider? {
        return when {
            SslProvider.isAlpnSupported(SslProvider.OPENSSL) -> SslProvider.OPENSSL
            SslProvider.isAlpnSupported(SslProvider.JDK) -> SslProvider.JDK
            else -> null
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
        val defaultBaseUrl = endPointFromHostAndPort(this.host, this.port, keyDataRegistry.hasAny())
        val specsWithMultipleBaseUrls = specmaticConfigInstance.stubToBaseUrlList(defaultBaseUrl).groupBy(
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
            logger.boundary()
        }

        val openApiBaseUrls: List<String> = features
            .asSequence()
            .map { feature ->
                specToBaseUrlMap[feature.path] ?: defaultBaseUrl
            }
            .distinct()
            .toList()

        val hostPorts = openApiBaseUrls.map { baseUrl ->
            val host = extractHost(baseUrl).let(::normalizeHost)
            val port = extractPort(baseUrl)
            host to port
        }.distinct()

        return hostPorts.ifEmpty { listOf(this.host to this.port) }
    }

    fun serveStubResponse(
        httpRequest: HttpRequest,
        baseUrl: String,
        defaultBaseUrl: String,
        urlPath: String
    ): StubbedResponseResult {
        val url = "$baseUrl$urlPath"
        val stubBaseUrlPath = specmaticConfigInstance.stubBaseUrlPathAssociatedTo(url, defaultBaseUrl)

        return getHttpResponse(
            httpRequest = httpRequest.trimBaseUrlPath(stubBaseUrlPath),
            features = featuresAssociatedTo(baseUrl, features, specToBaseUrlMap, urlPath),
            httpExpectations.associatedTo(baseUrl, defaultBaseUrl, urlPath),
            strictMode = strictMode,
            passThroughTargetBase = passThroughTargetBase,
            httpClientFactory = httpClientFactory,
            specmaticConfig = specmaticConfigInstance,
        ).also {
            if (it is FoundStubbedResponse) {
                it.response.mock?.let { mock -> httpExpectations.utilizeMock(mock) }
            }
            it.log(_logs, httpRequest)
        }
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

        return features.filter { feature -> feature.path in specsForGivenBaseUrl }.distinctBy { feature -> feature.path }
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
        this.channelPipelineConfig = {
            val localAddress = channel().localAddress() as? InetSocketAddress
            if (localAddress != null) {
                val mtlsSslContext = incomingMtlsSslContextsByPort[localAddress.port]
                if (mtlsSslContext != null) {
                    val sslHandler = get("ssl") as? SslHandler
                    val newSslHandler = mtlsSslContext.newHandler(channel().alloc())
                    if (sslHandler != null) {
                        replace(sslHandler, "ssl", newSslHandler)
                    } else {
                        addFirst("ssl", newSslHandler)
                    }
                }
            }
        }
    })

    private fun handleFetchLoadLogRequest(): HttpStubResponse =
        HttpStubResponse(HttpResponse.ok(StringValue(LogTail.getSnapshot())))

    private fun handleFetchContractsRequest(): HttpStubResponse =
        HttpStubResponse(HttpResponse.ok(StringValue(features.joinToString("\n") { it.name })))

    private fun handleFetchSwaggerSpecRequest(httpRequest: HttpRequest): HttpStubResponse {
        val openApiSpec = firstMockedOpenApiSpec ?: return HttpStubResponse(
            HttpResponse(status = HttpStatusCode.Conflict.value, body = SWAGGER_SPEC_NOT_AVAILABLE_MESSAGE)
        )
        val resolvedSpec = File(openApiSpec.resolvedSpecPath)
        if (!resolvedSpec.exists()) {
            throw ContractException("Could not find mocked OpenAPI specification: ${resolvedSpec.canonicalPath}")
        }

        val overlayContent = stubOverlayContent(resolvedSpec)
        val parsedOpenApi = OpenApiSpecification.fromYAML(
            yamlContent = resolvedSpec.readText(),
            openApiFilePath = resolvedSpec.canonicalPath,
            specificationPath = openApiSpec.displaySpecId,
            specmaticConfig = specmaticConfigInstance,
            overlayContent = overlayContent,
            strictMode = specmaticConfigInstance.getStubStrictMode(null) ?: false
        ).parsedOpenApi

        val format = swaggerSpecResponseFormat(httpRequest)
            ?: throw ContractException("Unsupported swagger specification path: ${httpRequest.path.orEmpty()}")
        val response = when (format) {
            SwaggerSpecResponseFormat.YAML -> {
                val openApiYaml = Yaml.pretty(parsedOpenApi)
                HttpResponse(status = 200, headers = mapOf(HttpHeaders.ContentType to "application/yaml"), body = StringValue(openApiYaml))
            }
            SwaggerSpecResponseFormat.JSON -> {
                val openApiJson = ObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writeValueAsString(parsedOpenApi)
                HttpResponse.jsonResponse(openApiJson)
            }
        }

        return HttpStubResponse(
            response = response,
            contractPath = resolvedSpec.canonicalPath
        )
    }

    private fun swaggerSpecResponseFormat(httpRequest: HttpRequest): SwaggerSpecResponseFormat? =
        when (httpRequest.path) {
            SWAGGER_SPEC_YAML_PATH -> SwaggerSpecResponseFormat.YAML
            SWAGGER_SPEC_JSON_PATH -> SwaggerSpecResponseFormat.JSON
            else -> null
        }

    private fun stubOverlayContent(specFile: File): String {
        val configuredOverlay = specmaticConfigInstance.getStubOverlayFilePath(specFile, SpecType.OPENAPI)?.let(::File)
        return configuredOverlay?.let {
            if (!it.exists()) {
                throw ContractException("Specified Overlay file does not exist ${it.canonicalPath}")
            }
            it.readText()
        }.orEmpty()
    }

    private fun redactedSwaggerSpecResponse(originalResponse: HttpResponse, summary: String): HttpResponse =
        HttpResponse(
            status = originalResponse.status,
            headers = mapOf(HttpHeaders.ContentType to (originalResponse.contentType() ?: "text/plain")),
            body = StringValue(summary)
        )

    private fun handleFetchLogRequest(): HttpStubResponse =
        HttpStubResponse(HttpResponse.ok(StringValue(LogTail.getString())))

    private fun handleExpectationCreationRequest(httpRequest: HttpRequest): HttpStubResponse {
        return try {
            if (httpRequest.body.toStringLiteral().isEmpty())
                throw ContractException("Expectation payload was empty")

            val mock: ScenarioStub = ScenarioStub.parse(httpRequest.body)
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
                logger.debug("No Sse Event was found in the request:\n${httpRequest.toLogString("  ", prettyPrint)}")
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
        val mock = ScenarioStub.parse(json)
        setExpectation(mock)
    }

    fun setExpectation(stub: ScenarioStub): List<HttpStubData> {
        val results = features.asSequence().map { feature -> setExpectation(stub, feature) }

        val result: Pair<Pair<Result.Success, List<HttpStubData>>?, NoMatchingScenario?>? =
            results.find { it.first != null }
        val firstResult: Pair<Result.Success, List<HttpStubData>>? = result?.first

        when (firstResult) {
            null -> {
                val failures = results
                    .flatMap {
                        it.second?.results?.withoutFluff()?.results ?: emptyList()
                    }
                    .filterIsInstance<Result.Failure>()
                    .distinctBy { failure -> failure.toReport().toText() }
                    .toList()

                val failureResults = Results(failures).withoutFluff()
                throw NoMatchingScenario(
                    failureResults,
                    cachedMessage = failureResults.report(stub.requestElsePartialRequest())
                )
            }

            else -> {
                val stubData = firstResult.second.map { it.copy(scenarioStub = stub) }
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
        val protocols = features.map { it.protocol }.distinct()
        if (SpecmaticProtocol.HTTP in protocols) generateReports()
        logger.debug("Stopping the server with grace period of $timeoutMillis")
        server.stop(gracePeriodMillis = timeoutMillis, timeoutMillis = timeoutMillis)
    }

    private fun generateReports() {
        generateStubUsageReport()
        synchronized(ctrfTestResultRecords) {
            val mockUsage = OpenApiMockUsage()
            mockUsage.addEndpoints(_allEndpoints)
            ctrfTestResultRecords.forEach(mockUsage::addTestResultRecord)

            ReportGenerator.generateReport(
                testResultRecords = mockUsage.testResultRecords(),
                coverageReportOperations = mockUsage.generate(),
                startTime = startTime.toEpochMilli(),
                endTime = Instant.now().toEpochMilli(),
                specConfigs = mockUsage.ctrfSpecConfigs(),
                coverage = 0,
                reportDir = File("${specmaticConfigInstance.getReportDirPath()}/stub")
            )
        }
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
        LicenseConfig.instance.utilization.shipDisabled = specmaticConfigInstance.isTelemetryDisabled()
        val initializers = ServiceLoader.load(StubInitializer::class.java)

        initializers.forEach { initializer ->
            initializer.initialize(this.specmaticConfigInstance, this)
        }

        server.start()
    }

    private fun extractAllEndpoints(): List<StubEndpoint> {
        return features.flatMap { it.scenarios }.map { scenario ->
            StubEndpoint(
                scenario.path,
                scenario.method,
                scenario.status,
                scenario.requestContentType,
                scenario.httpResponsePattern.headersPattern.contentType,
                scenario.sourceProvider,
                scenario.sourceRepository,
                scenario.sourceRepositoryBranch,
                scenario.specification,
                scenario.protocol,
                scenario.specType
            )
        }
    }

    private fun generateStubUsageReport() {
        specmaticConfigPath?.let {
            val stubUsageReport = StubUsageReport(specmaticConfigPath, _allEndpoints, _logs)
            println("Saving Stub Usage Report json to $JSON_REPORT_PATH ...")
            val json = Json {
                encodeDefaults = false
            }
            val generatedReport = stubUsageReport.generate()
            val reportPath = File(reportBaseDirectoryPath).resolve(JSON_REPORT_PATH).canonicalFile
            val reportJson: String = reportPath.resolve(JSON_REPORT_FILE_NAME).let { reportFile ->
                val objectMapper = ObjectMapper()
                if (reportFile.exists()) {
                    try {
                        val existingReport =
                            objectMapper.readValue(reportFile.readText(), SpecmaticStubUsageReport::class.java)
                        objectMapper.writeValueAsString(generatedReport.merge(existingReport))
                    } catch (exception: Throwable) {
                        logger.log("The existing report file is not a valid Stub Usage Report. ${exception.message}")
                        objectMapper.writeValueAsString(generatedReport)
                    }
                } else {
                    objectMapper.writeValueAsString(generatedReport)
                }
            }

            saveJsonFile(reportJson, reportPath.canonicalPath, JSON_REPORT_FILE_NAME)
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
            appendLine("Mock server is running on the following URLs:")
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

        val requestHeaders: Map<String, String> = call.request.headers.toMap().mapValues { it.value[0] }

        val transformedSOAPActionHeader: Map<String, String> =
            transformSOAP1_2ActionToSOAP1_1Header(call)

        return HttpRequest(
            method = call.request.httpMethod.value,
            path = urlDecodePathSegments(call.request.path()),
            headers = requestHeaders + transformedSOAPActionHeader,
            body = body,
            queryParams = QueryParameters(paramPairs = toParams(call.request.queryParameters)),
            formFields = formFields,
            multiPartFormData = multiPartFormData
        )
    } catch (e: Throwable) {
        throw CouldNotParseRequest(e)
    }
}

private fun transformSOAP1_2ActionToSOAP1_1Header(call: ApplicationCall): Map<String, String> {
    val soapAction =
        ContentType.parse(call.request.headers["Content-Type"].orEmpty()).parameters.find { it.name == "action" }
    val transformedSOAPActionHeader: Map<String, String> =
        if (soapAction != null) {
            mapOf("SOAPAction" to "\"${soapAction.value}\"")
        } else {
            emptyMap()
        }
    return transformedSOAPActionHeader
}

private suspend fun bodyFromCall(call: ApplicationCall): Triple<Value, Map<String, String>, List<MultiPartFormDataValue>> {
    return when {
        call.request.httpMethod == HttpMethod.Get -> if (call.request.headers.contains("Content-Type")) {
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
            val bodyValue: Value = getBodyPayloadValue(call)

            Triple(bodyValue, emptyMap(), emptyList())

        }
    }
}

private suspend fun getBodyPayloadValue(
    call: ApplicationCall
): Value {
    val rawContentType = call.request.headers["Content-Type"]

    if (rawContentType == null) return NoBodyValue

    val contentType = ContentType.parse(rawContentType)
    val contentSubtype = contentType.contentSubtype.lowercase()

    val rawContent = receiveText(call)

    return try {
        if (contentSubtype == "json" || contentSubtype.substringAfter("+") == "json") {
            parsedJSON(rawContent)
        } else if (contentSubtype == "xml" || contentSubtype.substringAfter("+") == "xml") {
            toXMLNode(rawContent)
        } else {
            StringValue(rawContent)
        }
    } catch (e: Throwable) {
        parsedValue(rawContent)
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
    specmaticConfig: SpecmaticConfig? = null,
    specificationPath: String? = null,
) {
    val headersControlledByEngine = listOfExcludedHeaders().mapTo(hashSetOf()) { it.lowercase() }
    val headers = internalHeadersToKtorHeaders(httpResponse.headers.filterNot { it.key.lowercase() in headersControlledByEngine })
    headers.forEach { (key, values) ->
        values.forEach { value ->
            call.response.headers.append(key, value)
        }
    }

    val delayInMs = delayInMilliSeconds ?: specmaticConfig?.getStubDelayInMilliseconds(specificationPath?.let(::File))
    if (delayInMs != null) {
        delay(delayInMs)
    }

    val contentType = httpResponse.contentType() ?: httpResponse.body.httpContentType
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
        if (matchingStubResponse != null) {
            val (httpStubResponse, httpStubData) = matchingStubResponse
            return FoundStubbedResponse(
                httpStubResponse.resolveSubstitutions(
                    httpRequest,
                    httpStubData.resolveOriginalRequest() ?: httpRequest,
                    httpStubData.data,
                ),
            )
        }
        if (httpClientFactory != null && passThroughTargetBase.isNotBlank()) {
            return NotStubbed(
                passThroughResponse(httpRequest, passThroughTargetBase, httpClientFactory),
                stubResult = Result.Success()
            )
        }

        val matchingFeature = features.firstOrNull { it.identifierMatchingScenario(httpRequest) != null }
        val effectiveStrictMode = specmaticConfig.getStubStrictMode(matchingFeature?.path?.let(::File)) ?: strictMode
        if (effectiveStrictMode) {
            val generativeMode = specmaticConfig.getStubGenerative(matchingFeature?.path?.let(::File))
            return strictModeHttp400Response(features, httpRequest, matchResults, generativeMode)
        }

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

object SpecificationAndRequestMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Specification expected $expected but request contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the request was not in the specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Specification expected mandatory $keyLabel \"$keyName\" to be present but was missing from the request"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional $keyLabel \"$keyName\" from specification to be present but was missing from the request"
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
            response = softCastResponse,
            delayInMilliSeconds = it.delayInMilliseconds,
            contractPath = it.contractPath,
            exampleName = it.name,
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

data class ResponseDetails(val feature: Feature, val successResponse: ResponseBuilder?, val results: Results)

fun fakeHttpResponse(
    features: List<Feature>,
    httpRequest: HttpRequest,
    specmaticConfig: SpecmaticConfig = SpecmaticConfig()
): StubbedResponseResult {

    if (features.isEmpty())
        return NotStubbed(HttpStubResponse(HttpResponse(400, "No valid API specifications loaded")), Result.Failure("No valid API specifications loaded"))

    val responses: List<ResponseDetails> = responseDetailsFrom(features, httpRequest)
    return when (val fakeResponse = responses.successResponse()) {
        null -> {
            val failureResponses = responses.filter { it.successResponse == null }
            val combinedFailureResult = Results(failureResponses.flatMap { it.results.results })
            val firstScenarioWith400Response = failureResponses.asSequence().flatMap { response ->
                response.results.results.asSequence().filterIsInstance<Result.Failure>().filter {
                    it.failureReason == null && it.scenario?.let { scenario -> scenario.status == 400 || scenario.status == 422 } == true
                }.map { failure -> response.feature to failure.scenario!! }
            }.firstOrNull()

            if (firstScenarioWith400Response != null && specmaticConfig.getStubGenerative(File(firstScenarioWith400Response.first.path))) {
                val feature = firstScenarioWith400Response.first
                val scenario = firstScenarioWith400Response.second as Scenario
                val errorResponse = scenario.responseWithStubError(combinedFailureResult.report())
                NotStubbed(
                    HttpStubResponse(errorResponse, contractPath = feature.path, scenario = scenario, feature = feature),
                    combinedFailureResult.toResultIfAnyWithCauses(),
                )
            } else {
                val httpFailureResponse = combinedFailureResult.generateErrorHttpResponse(httpRequest)

                val (nearestMatchingFeature, nearestMatchingScenario) =
                    features.firstNotNullOfOrNull { feature ->
                        feature.identifierMatchingScenario(httpRequest)?.let { scenario ->
                            feature to scenario
                        }
                    } ?: Pair (null, null)

                NotStubbed(
                    response = HttpStubResponse(
                        response = httpFailureResponse,
                        scenario = nearestMatchingScenario,
                        contractPath = nearestMatchingFeature?.path.orEmpty(),
                        feature = nearestMatchingFeature
                    ),
                    stubResult = combinedFailureResult.toResultIfAnyWithCauses(),
                )
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
    return features.map { feature ->
        feature.stubResponse(httpRequest, SpecificationAndRequestMismatchMessages).let {
            ResponseDetails(feature, it.first, it.second)
        }
    }
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
        if (withoutSpecmaticTypeHeader) it.withoutSpecmaticTypeHeader()
        else it
    }.adjustPayloadForContentType()
}

fun dumpIntoFirstAvailableStringField(httpResponse: HttpResponse, stringValue: String): HttpResponse {
    val responseBody = httpResponse.body

    if (responseBody !is JSONObjectValue)
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

    if (key != null)
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

    if (indexOfFirstStringValue >= 0) {
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
    features: List<Feature>,
    httpRequest: HttpRequest,
    matchResults: List<Pair<Result, HttpStubData>>,
    generative: Boolean
): NotStubbed {
    val results = Results(matchResults.map { it.first }).withoutFluff().withoutViolationReport()
    val strictModeReport = results.strictModeReport(httpRequest)
    val scenario = features.firstNotNullOfOrNull { it.identifierMatchingScenario(httpRequest) }
    val response = if (generative) {
        generativeStrictModeResponse(httpRequest, features, strictModeReport) ?: strictModeFallbackResponse(strictModeReport)
    } else {
        strictModeFallbackResponse(strictModeReport)
    }

    return NotStubbed(
        stubResult = results.toResultIfAnyWithCauses(),
        response = HttpStubResponse(scenario = scenario, response = response),
    )
}

private fun generativeStrictModeResponse(httpRequest: HttpRequest, features: List<Feature>, strictModeReport: String): HttpResponse? {
    return features.firstNotNullOfOrNull { feature ->
        feature.identifierMatchingScenario(httpRequest, furtherPredicate = { it.status in invalidRequestStatuses })
    }?.responseWithStubError(strictModeReport)
}

private fun strictModeFallbackResponse(strictModeReport: String): HttpResponse {
    val defaultHeaders = mapOf("Content-Type" to "text/plain", SPECMATIC_RESULT_HEADER to "failure")
    val headers = if (strictModeReport.isEmpty()) defaultHeaders + (SPECMATIC_EMPTY_HEADER to "true") else defaultHeaders
    return HttpResponse(
        status = 400,
        headers = headers,
        body = StringValue("STRICT MODE ON${System.lineSeparator()}${System.lineSeparator()}$strictModeReport")
    )
}

fun stubResponse(
    httpRequest: HttpRequest,
    contractInfo: List<Pair<Feature, List<ScenarioStub>>>,
    stubs: StubDataItems
): HttpResponse {
    return try {
        when (val mock = stubs.http.find { (requestPattern, _, resolver) ->
            requestPattern.matches(httpRequest, resolver.disableOverrideUnexpectedKeyCheck()) is Result.Success
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
            feature.matchingStub(example, ExampleMismatchMessages)
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

internal fun httpResponseLog(response: HttpResponse, prettyPrint: Boolean = true): String =
    "${response.toLogString("<- ", prettyPrint)}\n<< Response At ${Date()} == "

internal fun httpRequestLog(httpRequest: HttpRequest, prettyPrint: Boolean = true): String =
    ">> Request Start At ${Date()}\n${httpRequest.toLogString("-> ", prettyPrint)}"

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

fun endPointFromHostAndPort(host: String, port: Int?, isHttps: Boolean): String {
    val protocol = when (isHttps) {
        false -> "http"
        true -> "https"
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
    val effectiveUrl = if ("://" in url) URI(url) else URI("scheme://$url")
    return resolvedPort(effectiveUrl)
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

private const val SWAGGER_SPEC_YAML_PATH = "/swagger/v1/swagger.yaml"
private const val SWAGGER_SPEC_JSON_PATH = "/swagger/v1/swagger.json"

internal fun isSwaggerSpecRequest(httpRequest: HttpRequest): Boolean =
    httpRequest.method == "GET" && (httpRequest.path == SWAGGER_SPEC_YAML_PATH || httpRequest.path == SWAGGER_SPEC_JSON_PATH)

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
