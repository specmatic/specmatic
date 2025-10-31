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
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.uniqueNameForApiOperation
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.httpRequestLog
import io.specmatic.stub.httpResponseLog
import io.specmatic.stub.ktorHttpRequestToHttpRequest
import io.specmatic.stub.respondToKtorHttpResponse
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
    ) : this(host, port, baseURL, RealFileWriter(proxySpecmaticDataDir), keyData, timeoutInMilliseconds, filter)

    private val stubs = mutableListOf<NamedStub>()

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
                                    if (filter != "" && filterHttpRequest(httpRequest, filter)) {
                                        respondToKtorHttpResponse(call, HttpResponse(404, "This request has been filtered out"))
                                        return@intercept
                                    }

                                    // continue as before, if not matching filter
                                    val client =
                                        LegacyHttpClient(
                                            proxyURL(httpRequest, baseURL),
                                            timeoutInMilliseconds = timeoutInMilliseconds,
                                        )

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

                                    // check response for matching filter. if matches, bail!
                                    val name =
                                        "${httpRequest.method} ${httpRequest.path}${toQueryString(httpRequest.queryParams.asMap())}"
                                    stubs.add(
                                        NamedStub(
                                            name,
                                            uniqueNameForApiOperation(httpRequest, baseURL, httpResponse.status),
                                            ScenarioStub(
                                                httpRequest.dropIrrelevantHeaders(),
                                                httpResponse.dropIrrelevantHeaders(),
                                            ),
                                        ),
                                    )

                                    requestObserver?.onRequestHandled(httpRequest, httpResponse)

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
