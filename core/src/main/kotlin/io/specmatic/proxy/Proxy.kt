package io.specmatic.proxy

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.specmatic.core.*
import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.HttpRequestFilterContext
import io.specmatic.core.filters.HttpResponseFilterContext
import io.specmatic.core.log.logger
import io.specmatic.core.route.modules.HealthCheckModule.Companion.configureHealthCheckModule
import io.specmatic.core.route.modules.HealthCheckModule.Companion.isHealthCheckRequest
import io.specmatic.core.utilities.TrackingFeature
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.uniqueNameForApiOperation
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.*
import io.specmatic.test.LegacyHttpClient
import io.swagger.v3.core.util.Yaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.URI
import java.net.URL
import java.util.*

fun interface RequestObserver {
    fun onRequestHandled(
        httpRequest: HttpRequest,
        httpResponse: HttpResponse,
    )
}

class Proxy(
    host: String,
    port: Int,
    baseURL: String,
    private val outputDirectory: FileWriter,
    keyData: KeyData? = null,
    timeoutInMilliseconds: Long = DEFAULT_TIMEOUT_IN_MILLISECONDS,
    filter: String? = "",
    private val requestObserver: RequestObserver? = null,
    specmaticConfigSource: SpecmaticConfigSource = SpecmaticConfigSource.None,
) : Closeable {
    constructor(
        host: String,
        port: Int,
        baseURL: String,
        proxySpecmaticDataDir: String,
        keyData: KeyData? = null,
        timeoutInMilliseconds: Long,
        filter: String,
        requestObserver: RequestObserver? = null,
        specmaticConfigSource: SpecmaticConfigSource = SpecmaticConfigSource.None,
    ) : this(host, port, baseURL, RealFileWriter(proxySpecmaticDataDir), keyData, timeoutInMilliseconds, filter, requestObserver, specmaticConfigSource)

    private val stubs = mutableListOf<NamedStub>()

    private val requestInterceptors: MutableList<RequestInterceptor> = mutableListOf()
    private val responseInterceptors: MutableList<ResponseInterceptor> = mutableListOf()

    fun registerRequestInterceptor(requestInterceptor: RequestInterceptor) {
        if (requestInterceptor !in requestInterceptors) {
            requestInterceptors.add(requestInterceptor)
        }
    }

    fun registerResponseInterceptor(responseInterceptor: ResponseInterceptor) {
        if (responseInterceptor !in responseInterceptors) {
            responseInterceptors.add(responseInterceptor)
        }
    }

    private val loadedSpecmaticConfig = specmaticConfigSource.load()
    private val specmaticConfigInstance: SpecmaticConfig = loadedSpecmaticConfig.config

    private val targetHost =
        baseURL.let {
            when {
                it.isBlank() -> null
                else -> URI(baseURL).host
            }
        }

    private val environment =
        applicationEngineEnvironment {
            module {
                intercept(ApplicationCallPipeline.Call) {
                    try {
                        val httpRequest = ktorHttpRequestToHttpRequest(call)

                        if (httpRequest.isHealthCheckRequest()) return@intercept
                        if (httpRequest.isDumpRequest()) return@intercept

                        when (httpRequest.method?.uppercase()) {
                            "CONNECT" -> {
                                val errorResponse = HttpResponse(400, "CONNECT is not supported")
                                println(
                                    listOf(httpRequestLog(httpRequest), httpResponseLog(errorResponse)).joinToString(
                                        System.lineSeparator(),
                                    ),
                                )
                                respondToKtorHttpResponse(call, errorResponse)
                            }

                            else ->
                                try {
                                    LicenseResolver.utilize(
                                        product = LicensedProduct.OPEN_SOURCE,
                                        feature = TrackingFeature.PROXY,
                                    )

                                    if (filter != "" && filterHttpRequest(httpRequest, filter)) {
                                        respondToKtorHttpResponse(call, HttpResponse(404, "This request has been filtered out"))
                                        return@intercept
                                    }

                                    // Apply request codec hooks to get the tracked request
                                    val (recordedRequest, requestInterceptorErrors) = requestInterceptors.fold(
                                        httpRequest to emptyList<InterceptorError>()
                                    ) { (request, errors), requestInterceptor ->
                                        val result = requestInterceptor.interceptRequestAndReturnErrors(request)
                                        (result.value ?: request) to (errors + result.errors)
                                    }

                                    // Log the transformed request if it was changed by hooks
                                    if (recordedRequest != httpRequest) {
                                        logger.log("")
                                        logger.log("--------------------")
                                        logger.log("  Request after hook processing:")
                                        logger.log(recordedRequest.toLogString().prependIndent("    "))
                                        logger.boundary()
                                    }

                                    // Log request hook errors if any occurred
                                    if (requestInterceptorErrors.isNotEmpty()) {
                                        logger.boundary()
                                        logger.log("--------------------")
                                        logger.log("Request hook errors:")
                                        logger.log(InterceptorErrors(requestInterceptorErrors).toString().prependIndent("  "))
                                    }

                                    // continue as before, if not matching filter
                                    val client =
                                        LegacyHttpClient(
                                            proxyURL(httpRequest, baseURL),
                                            timeoutInMilliseconds = timeoutInMilliseconds,
                                        )

                                    // Send the ORIGINAL request to the target (not the tracked one)
                                    val requestToSend =
                                        targetHost?.let {
                                            httpRequest.withHost(targetHost)
                                        } ?: httpRequest

                                    val httpResponse = client.execute(requestToSend)

                                    if (filter != "" && filterHttpResponse(httpResponse, filter)) {
                                        respondToKtorHttpResponse(
                                            call,
                                            HttpResponse(404, "This response has been filtered out"),
                                        )
                                        return@intercept
                                    }

                                    // Apply response codec hooks to get the tracked response
                                    val (recordedResponse, responseInterceptorErrors) = responseInterceptors.fold(
                                        httpResponse to emptyList<InterceptorError>()
                                    ) { (response, errors), responseInterceptor ->
                                        val result = responseInterceptor.interceptResponseAndReturnErrors(recordedRequest, response)
                                        (result.value ?: response) to (errors + result.errors)
                                    }

                                    // Log the transformed response if it was changed by hooks
                                    if (recordedResponse != httpResponse) {
                                        logger.log("")
                                        logger.log("--------------------")
                                        logger.log("  Response after hook processing:")
                                        logger.log(recordedResponse.toLogString().prependIndent("    "))
                                        logger.boundary()
                                    }

                                    // Log response hook errors if any occurred
                                    if (responseInterceptorErrors.isNotEmpty()) {
                                        logger.boundary()
                                        logger.log("--------------------")
                                        logger.log("Response hook errors:")
                                        logger.log(InterceptorErrors(responseInterceptorErrors).toString().prependIndent("  "))
                                    }

                                    // check response for matching filter. if matches, bail!
                                    val name =
                                        "${recordedRequest.method} ${recordedRequest.path}${toQueryString(recordedRequest.queryParams.asMap())}"
                                    stubs.add(
                                        NamedStub(
                                            name,
                                            uniqueNameForApiOperation(recordedRequest, baseURL, recordedResponse.status),
                                            ScenarioStub(
                                                recordedRequest.dropIrrelevantHeaders(),
                                                recordedResponse.dropIrrelevantHeaders(),
                                            ),
                                        ),
                                    )

                                    requestObserver?.onRequestHandled(recordedRequest, recordedResponse)

                                    // Send the ORIGINAL response back to consumer (not the tracked one)
                                    respondToKtorHttpResponse(call, withoutContentEncodingGzip(httpResponse))
                                } catch (e: Throwable) {
                                    logger.log(e)
                                    val errorResponse =
                                        HttpResponse(500, exceptionCauseMessage(e) + "\n\n" + e.stackTraceToString())
                                    respondToKtorHttpResponse(call, errorResponse)
                                    logger.debug(
                                        listOf(
                                            httpRequestLog(httpRequest),
                                            httpResponseLog(errorResponse),
                                        ).joinToString(System.lineSeparator()),
                                    )
                                }
                        }
                    } catch (e: Throwable) {
                        logger.log(e)
                        val errorResponse =
                            HttpResponse(500, exceptionCauseMessage(e) + "\n\n" + e.stackTraceToString())
                        respondToKtorHttpResponse(call, errorResponse)
                    }
                }

                configureHealthCheckModule()

                routing {
                    post(DUMP_ENDPOINT) { handleDumpRequest(call) }
                }
            }

            when (keyData) {
                null ->
                    connector {
                        this.host = host
                        this.port = port
                    }

                else ->
                    sslConnector(
                        keyStore = keyData.keyStore,
                        keyAlias = keyData.keyAlias,
                        privateKeyPassword = { keyData.keyPassword.toCharArray() },
                        keyStorePassword = { keyData.keyPassword.toCharArray() },
                    ) {
                        this.host = host
                        this.port = port
                    }
            }
        }

    private fun toQueryString(queryParams: Map<String, String>): String =
        queryParams.entries
            .joinToString("&") { entry ->
                "${entry.key}=${entry.value}"
            }.let {
                when {
                    it.isEmpty() -> it
                    else -> "?$it"
                }
            }

    private fun withoutContentEncodingGzip(httpResponse: HttpResponse): HttpResponse {
        val contentEncodingKey =
            httpResponse.headers.keys.find { it.lowercase() == "content-encoding" } ?: "Content-Encoding"
        return when {
            httpResponse.headers[contentEncodingKey]?.lowercase()?.contains("gzip") == true ->
                httpResponse.copy(headers = httpResponse.headers.minus(contentEncodingKey))

            else ->
                httpResponse
        }
    }

    private val server: ApplicationEngine =
        embeddedServer(Netty, environment, configure = {
            this.requestQueueLimit = 1000
            this.callGroupSize = 5
            this.connectionGroupSize = 20
            this.workerGroupSize = 20
        })

    private fun proxyURL(
        httpRequest: HttpRequest,
        baseURL: String,
    ): String =
        when {
            isFullURL(httpRequest.path) -> ""
            else -> baseURL
        }

    private fun isFullURL(path: String?): Boolean =
        path != null &&
            try {
                URL(URLParts(path).withEncodedPathSegments())
                true
            } catch (e: Throwable) {
                false
            }

    init {
        val initializers = ServiceLoader.load(ProxyInitializer::class.java)

        initializers.forEach { initializer ->
            initializer.initialize(this.specmaticConfigInstance, this)
        }

        server.start()
    }

    override fun close() {
        try {
            runBlocking {
                dumpSpecAndExamplesIntoOutputDir()
            }
        } finally {
            server.stop(0, 0)
        }
    }

    private fun filterHttpRequest(
        httpRequest: HttpRequest,
        filter: String?,
    ): Boolean {
        if (filter.isNullOrBlank()) {
            return true
        }
        val filterToEvalEx = ExpressionStandardizer.filterToEvalEx(filter)
        return filterToEvalEx
            .with("context", HttpRequestFilterContext(httpRequest))
            .evaluate()
            .booleanValue
    }

    private fun filterHttpResponse(
        httpResponse: HttpResponse,
        filter: String?,
    ): Boolean {
        if (filter.isNullOrBlank()) {
            return true
        }
        val filterToEvalEx = ExpressionStandardizer.filterToEvalEx(filter)
        return filterToEvalEx
            .with("context", HttpResponseFilterContext(httpResponse))
            .evaluate()
            .booleanValue
    }

    private suspend fun dumpSpecAndExamplesIntoOutputDir() =
        Mutex().withLock {
            val gherkin = toGherkinFeature("New feature", stubs)
            val base = "proxy_generated"
            val featureFileName = "$base.yaml"

            if (stubs.isEmpty()) {
                println("No stubs were recorded. No contract will be written.")
                return
            }
            outputDirectory.createDirectory()

            val stubDataDirectory = outputDirectory.subDirectory("${base}$EXAMPLES_DIR_SUFFIX")
            stubDataDirectory.createDirectory()

            stubs.mapIndexed { index, namedStub: NamedStub ->
                val fileName = "${namedStub.shortName}_${index.inc()}.json"
                println("Writing stub data to $fileName")
                stubDataDirectory.writeText(fileName, namedStub.stub.toJSON().toStringLiteral())
            }

            val openApi = parseGherkinStringToFeature(gherkin).toOpenApi()

            println("Writing specification to $featureFileName")
            outputDirectory.writeText(featureFileName, Yaml.pretty(openApi))
        }

    private suspend fun handleDumpRequest(call: ApplicationCall) {
        call.respond(HttpStatusCode.Accepted, "Dump process of spec and examples has started in the background")
        withContext(Dispatchers.IO) {
            dumpSpecAndExamplesIntoOutputDir()
        }
    }

    companion object {
        private const val DUMP_ENDPOINT = "/_specmatic/proxy/dump"

        private fun HttpRequest.isDumpRequest(): Boolean = (this.path == DUMP_ENDPOINT) && (this.method == HttpMethod.Post.value)
    }
}
