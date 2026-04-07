package io.specmatic.test.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.NoBodyValue
import io.specmatic.core.QueryParameters
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.parsedJsonValue
import io.specmatic.core.pattern.parsedPattern
import io.specmatic.core.urlToQueryParams
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

sealed interface ExpectationLifetime {
    data object Permanent : ExpectationLifetime
    data class Times(val remaining: Int) : ExpectationLifetime
}

data class RegisteredExpectation(val request: HttpRequestPattern, val response: HttpResponsePattern, val otherwise: HttpResponsePattern?, val lifetime: ExpectationLifetime = ExpectationLifetime.Permanent) {
    val isExhausted: Boolean = lifetime is ExpectationLifetime.Times && lifetime.remaining <= 0

    fun matches(request: HttpRequest, resolver: Resolver): Boolean {
        return !isExhausted && this.request.matches(request, resolver, resolver).isSuccess()
    }

    fun routeMatches(request: HttpRequest, resolver: Resolver): Boolean {
        return this.request.matchesPathAndMethod(request, resolver).isSuccess()
    }

    fun consume(): RegisteredExpectation = when (lifetime) {
        ExpectationLifetime.Permanent -> this
        is ExpectationLifetime.Times -> copy(lifetime = lifetime.copy(remaining = lifetime.remaining.minus(1).coerceAtLeast(0)))
    }

    companion object {
        class RuleDsl(private val method: String, private val path: String) {
            private var body: String? = null
            private var response: HttpResponsePattern? = null
            private var otherwise: HttpResponsePattern? = null
            private val headers = linkedMapOf<String, String>()
            private val queryParameters = linkedMapOf<String, String>()
            private var lifetime: ExpectationLifetime = ExpectationLifetime.Permanent

            fun header(name: String, value: String) {
                headers[name] = value
            }

            fun body(value: String) {
                body = value
            }

            fun times(count: Int) {
                lifetime = ExpectationLifetime.Times(count)
            }

            fun respond(status: Int) {
                respond(HttpResponse(status = status, body = NoBodyValue))
            }

            fun respond(response: HttpResponse) {
                this.response = response.toPattern()
            }

            fun otherwise(status: Int) {
                otherwise(HttpResponse(status = status))
            }

            fun otherwise(response: HttpResponse) {
                this.otherwise = response.toPattern()
            }

            private fun HttpResponse.toPattern(): HttpResponsePattern {
                return HttpResponsePattern(
                    status = this.status,
                    body = this.body.exactMatchElseType(),
                    headersPattern = HttpHeadersPattern(this.headers.mapValues { parsedPattern(it.value) }),
                )
            }

            private fun toRequestPattern(): HttpRequestPattern {
                return HttpRequest(
                    path = path,
                    method = method,
                    headers = headers,
                    queryParams = QueryParameters(queryParameters),
                    body = body?.let(::parsedJsonValue) ?: NoBodyValue
                ).toPattern()
            }

            internal fun toRule(): RegisteredExpectation {
                return RegisteredExpectation(
                    lifetime = lifetime,
                    otherwise = otherwise,
                    request = toRequestPattern(),
                    response = requireNotNull(response) { "respond(...) must be called" },
                )
            }
        }
    }
}

class MockHttpServer(port: Int = randomFreePort(), private val resolver: Resolver = Resolver()) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(port), 0)
    private val expectations = CopyOnWriteArrayList<RegisteredExpectation>()
    val port: Int = server.address.port
    val baseUrl: String = "http://localhost:$port"

    init {
        server.createContext("/", MockHttpServerHandler(::dispatch))
        server.start()
    }

    fun on(path: String, method: String, block: RegisteredExpectation.Companion.RuleDsl.() -> Unit) {
        expectations.add((RegisteredExpectation.Companion.RuleDsl(method = method, path = path).apply(block).toRule()))
    }

    override fun close() {
        server.stop(0)
    }

    private fun dispatch(request: HttpRequest): HttpResponse {
        expectations.forEachIndexed { index, expectation ->
            if (expectation.matches(request, resolver)) {
                expectations[index] = expectation.consume()
                return expectation.response.generateResponseWithAll(resolver)
            }

            if (expectation.routeMatches(request, resolver) && expectation.otherwise != null) {
                return expectation.otherwise.generateResponseWithAll(resolver)
            }
        }

        return notFoundResponse(request)
    }

    private fun notFoundResponse(request: HttpRequest): HttpResponse {
        val json = jsonObject(
            "error" to "No expectation matched",
            "method" to (request.method.orEmpty()),
            "path" to (request.path.orEmpty()),
            "contentType" to (request.contentType().orEmpty()),
            "body" to request.body.toStringLiteral(),
        )
        return HttpResponse(status = 404, body = parsedJsonValue(json))
    }

    private fun jsonObject(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${k.jsonEscape()}\":\"${v.jsonEscape()}\""
        }
    }

    companion object {
        private fun randomFreePort(): Int {
            return ServerSocket(0).use { it.localPort }
        }

        private fun String.jsonEscape(): String {
            return replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        }
    }
}

private class MockHttpServerHandler(private val dispatch: (HttpRequest) -> HttpResponse) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val request  = exchange.toInternalRequest()
        val response = dispatch(request)
        exchange.send(response)
    }

    private fun HttpExchange.toInternalRequest(): HttpRequest {
        val rawBody = requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return HttpRequest(
            method = requestMethod,
            path = requestURI.path,
            queryParams = QueryParameters(urlToQueryParams(requestURI)),
            headers = requestHeaders.mapValues { it.value.joinToString("~~") },
            body = parsedJsonValue(rawBody),
        )
    }

    private fun HttpExchange.send(response: HttpResponse) {
        response.headers.forEach { (key, value) -> responseHeaders.add(key, value) }
        response.contentType()?.let { responseHeaders.set("Content-Type", it) }
        val bytes = response.body.toStringLiteral().toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(response.status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
