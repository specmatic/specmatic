package io.specmatic.test

import io.specmatic.core.*
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.consoleLog
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.startLinesWith
import io.specmatic.core.utilities.valueMapToPlainJsonString
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.stub.toParams
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.utils.io.charsets.*
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.util.*
import java.util.zip.GZIPInputStream

// API for non-Kotlin invokers
fun createHttpClient(baseURL: String, timeoutInMilliseconds: Long) = HttpClient(baseURL, timeoutInMilliseconds)

class HttpClient(
    val baseURL: String,
    private val timeoutInMilliseconds: Long = 6000,
    private val log: (event: LogMessage) -> Unit = ::consoleLog,
    private val httpClientFactory: HttpClientFactory = ApacheHttpClientFactory(timeoutInMilliseconds)
) : TestExecutor {
    private val serverStateURL = "/_$APPLICATION_NAME_LOWER_CASE/state"
    private var httpLogMessage: HttpLogMessage = HttpLogMessage(targetServer = baseURL)

    override fun execute(request: HttpRequest): HttpResponse {
        val url = URL(request.getURL(baseURL))

        val requestWithFileContent = request.loadFileContentIntoParts()
        httpLogMessage.logStartRequestTime()

        logger.debug("Starting request ${request.method} ${request.path}")

        return try {
            runBlocking {
                httpClientFactory.create().use { ktorClient ->
                    val ktorResponse: io.ktor.client.statement.HttpResponse = ktorClient.request(url) {
                        requestWithFileContent.buildKTORRequest(this, url)
                    }

                    val outboundRequest: HttpRequest =
                        ktorHttpRequestToHttpRequestForLogging(ktorResponse.request, requestWithFileContent)
                    httpLogMessage.addRequest(outboundRequest)

                    ktorResponseToHttpResponse(ktorResponse).also {
                        httpLogMessage.addResponse(it)
                        log(httpLogMessage)
                        ktorClient.close()
                    }
                }
            }
        } catch (e: Exception) {
            httpLogMessage.addException(e)
            throw e
        }
    }

    override fun setServerState(serverState: Map<String, Value>) {
        if (serverState.isEmpty()) return

        val url = URL(baseURL + serverStateURL)

        val startTime = Date()

        runBlocking {
            httpClientFactory.create().use { ktorClient ->
                var endTime: Date? = null
                var response: HttpResponse? = null

                try {
                    val ktorResponse: io.ktor.client.statement.HttpResponse = ktorClient.request(url) {
                        this.method = HttpMethod.Post
                        this.contentType(ContentType.Application.Json)
                        this.setBody(valueMapToPlainJsonString(serverState))
                    }

                    endTime = Date()

                    response = ktorResponseToHttpResponse(ktorResponse)

                    if (ktorResponse.status != HttpStatusCode.OK)
                        throw Exception("API responded with ${ktorResponse.status}")
                } finally {
                    val serverStateLog = object : LogMessage {
                        override fun toJSONObject(): JSONObjectValue {
                            val data: MutableMap<String, String> = mutableMapOf(
                                "requestTime" to startTime.toString(),
                                "serverState" to valueMapToPlainJsonString(serverState)
                            )

                            if (endTime != null && response != null) {
                                data["endTime"] = endTime.toString()
                                data["response"] = response.toLogString()
                            }

                            return JSONObjectValue(data.mapValues { StringValue(it.value) }.toMap())
                        }

                        override fun toLogString(): String {

                            return """
                        # >> Request Sent At $startTime
                        ${startLinesWith(valueMapToPlainJsonString(serverState), "# ")}
                        "# << Complete At $endTime"
                        """.trimIndent()
                        }
                    }

                    log(serverStateLog)
                }
            }
        }
    }

    override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
        httpLogMessage = httpLogMessage.copy(scenario = scenario, request = request)
        TestInteractionsLog.addHttpLog(httpLogMessage)
    }
}

private fun ktorHttpRequestToHttpRequestForLogging(
    request: io.ktor.client.request.HttpRequest,
    specmaticRequest: HttpRequest
): HttpRequest {
    val (body, formFields, multiPartFormData) =
        when (request.content) {
            is FormDataContent -> Triple(EmptyString, specmaticRequest.formFields, emptyList())
            is TextContent -> {
                val bodyValue = when (specmaticRequest.body) {
                    is NoBodyValue -> NoBodyValue
                    else -> specmaticRequest.body
                }

                Triple(bodyValue, emptyMap(), emptyList())
            }
            is MultiPartFormDataContent -> Triple(EmptyString, emptyMap(), specmaticRequest.multiPartFormData)
            is EmptyContent -> Triple(EmptyString, emptyMap(), emptyList())
            else -> throw ContractException("Unknown type of body content sent in the request")
        }

    val requestHeaders: Map<String, String> = request.headers.toMap().mapValues { it.value[0] }.plus(
        CONTENT_TYPE to (request.content.contentType?.toString() ?: "NOT SENT")
    )

    return HttpRequest(
        method = request.method.value,
        path = request.url.encodedPath,
        headers = requestHeaders,
        body = body,
        queryParams = QueryParameters(paramPairs = toParams(request.url.parameters)),
        formFields = formFields,
        multiPartFormData = multiPartFormData
    )
}

suspend fun ktorResponseToHttpResponse(ktorResponse: io.ktor.client.statement.HttpResponse): HttpResponse {
    val (headers, body) = decodeBody(ktorResponse)
    return HttpResponse(ktorResponse.status.value, body, headers)
}

suspend fun decodeBody(ktorResponse: io.ktor.client.statement.HttpResponse): Pair<Map<String, String>, String> {
    val encoding = ktorResponse.headers["Content-Encoding"]
    val headers = ktorResponse.headers.toMap().mapValues { it.value.first() }

    return try {
        decodeBody(ktorResponse.readBytes(), encoding, ktorResponse.charset(), headers)
    } catch (e: ClientRequestException) {
        decodeBody(e.response.readBytes(), encoding, ktorResponse.charset(), headers)
    }
}

fun decodeBody(
    bytes: ByteArray,
    encoding: String?,
    receivedCharset: Charset?,
    headers: Map<String, String>
): Pair<Map<String, String>, String> =
    when (encoding) {
        "gzip" -> {
            Pair(
                headers.minus("Content-Encoding"),
                unzip(bytes, receivedCharset)
            )
        }

        else -> Pair(headers, String(bytes))
    }

fun unzip(bytes: ByteArray, receivedCharset: Charset?): String {
    val charset = Charset.forName(receivedCharset?.name() ?: "UTF-8")
    return GZIPInputStream(bytes.inputStream()).bufferedReader(charset).use { it.readText() }
}
