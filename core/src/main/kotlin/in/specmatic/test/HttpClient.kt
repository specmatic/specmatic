package `in`.specmatic.test

import `in`.specmatic.core.*
import `in`.specmatic.core.HttpRequest
import `in`.specmatic.core.HttpResponse
import `in`.specmatic.core.log.HttpLogMessage
import `in`.specmatic.core.log.LogMessage
import `in`.specmatic.core.log.consoleLog
import `in`.specmatic.core.log.logger
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.startLinesWith
import `in`.specmatic.core.utilities.valueMapToPlainJsonString
import `in`.specmatic.core.value.EmptyString
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.Value
import `in`.specmatic.stub.toParams
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
fun createHttpClient(baseURL: String, timeout: Int) = HttpClient(baseURL, timeout)

class HttpClient(
    val baseURL: String,
    private val timeout: Int = 60,
    private val log: (event: LogMessage) -> Unit = ::consoleLog,
    private val httpClientFactory: HttpClientFactory = RealHttpClientFactory
) : TestExecutor {
    private val serverStateURL = "/_$APPLICATION_NAME_LOWER_CASE/state"

    override fun execute(request: HttpRequest): HttpResponse {
        val url = URL(request.getURL(baseURL))

        val requestWithFileContent = request.loadFileContentIntoParts()

        val httpLogMessage = HttpLogMessage(targetServer = baseURL)
        httpLogMessage.logStartRequestTime()

        logger.debug("Starting request ${request.method} ${request.path}")

        return runBlocking {
            httpClientFactory.create(timeout).use { ktorClient ->
                val ktorResponse: io.ktor.client.statement.HttpResponse = ktorClient.request(url) {
                    requestWithFileContent.buildRequest(this, url)
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
    }

    override fun setServerState(serverState: Map<String, Value>) {
        if (serverState.isEmpty()) return

        val url = URL(baseURL + serverStateURL)

        val startTime = Date()

        runBlocking {
            httpClientFactory.create(timeout).use { ktorClient ->
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
}

private fun ktorHttpRequestToHttpRequestForLogging(
    request: io.ktor.client.request.HttpRequest,
    specmaticRequest: HttpRequest
): HttpRequest {
    val (body, formFields, multiPartFormData) =
        when (request.content) {
            is FormDataContent -> Triple(EmptyString, specmaticRequest.formFields, emptyList())
            is TextContent -> Triple(specmaticRequest.body, emptyMap(), emptyList())
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
        queryParams = toParams(request.url.parameters),
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
