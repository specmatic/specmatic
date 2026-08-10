package io.specmatic.test.handlers

import io.ktor.http.*
import io.specmatic.core.*
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.toViolationReportString
import io.specmatic.test.MonitorResult
import io.specmatic.test.TestExecutor
import io.specmatic.test.utils.DelayStrategy
import io.specmatic.test.utils.RetryHandler
import io.specmatic.test.utils.Sleeper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class TooManyRequestsHandlerTest {
    companion object {
        private val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = 201),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))

        private val tooManyRequestsScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = HttpStatusCode.TooManyRequests.value),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))

        private val throwAwayExecutor = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse { throw AssertionError() }
        }
    }

    @Test
    fun `should handle documented too-many-requests responses for a 2xx test`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val handler = TooManyRequestsHandler(feature, postScenario)

        assertThat(handler.canHandle(HttpResponse(status = 429), postScenario)).isTrue()
    }

    @Test
    fun `should handle documented too-many-requests responses for a non-2xx test`() {
        val forbiddenScenario = postScenario.copy(
            httpResponsePattern = HttpResponsePattern(status = HttpStatusCode.Forbidden.value)
        )
        val feature = Feature(name = "", scenarios = listOf(forbiddenScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val handler = TooManyRequestsHandler(feature, forbiddenScenario)

        assertThat(handler.canHandle(HttpResponse(status = 429), forbiddenScenario)).isTrue()
    }

    @Test
    fun `should handle too-many-requests responses when they are undocumented`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario), protocol = SpecmaticProtocol.HTTP)
        val handler = TooManyRequestsHandler(feature, postScenario)

        assertThat(handler.canHandle(HttpResponse(status = 429), postScenario)).isTrue()
    }

    @Test
    fun `should validate too-many-requests response against the matching request and response content types`() {
        val jsonPostScenario = postScenario.copy(
            httpRequestPattern = postScenario.httpRequestPattern.copy(
                headersPattern = HttpHeadersPattern(contentType = ContentType.Application.Json.toString()),
            ),
        )
        val xmlTooManyRequestsScenario = tooManyRequestsScenario.copy(
            httpRequestPattern = tooManyRequestsScenario.httpRequestPattern.copy(
                headersPattern = HttpHeadersPattern(contentType = ContentType.Application.Xml.toString()),
            ),
            httpResponsePattern = HttpResponsePattern(
                status = HttpStatusCode.TooManyRequests.value,
                headersPattern = HttpHeadersPattern(contentType = ContentType.Application.Xml.toString()),
                body = ExactValuePattern(StringValue("xml rate limit")),
            ),
        )
        val jsonTooManyRequestsScenario = tooManyRequestsScenario.copy(
            httpRequestPattern = tooManyRequestsScenario.httpRequestPattern.copy(
                headersPattern = HttpHeadersPattern(contentType = ContentType.Application.Json.toString()),
            ),
            httpResponsePattern = HttpResponsePattern(
                status = HttpStatusCode.TooManyRequests.value,
                headersPattern = HttpHeadersPattern(contentType = ContentType.Application.Json.toString()),
                body = ExactValuePattern(StringValue("json rate limit")),
            ),
        )
        val feature = Feature(
            name = "",
            scenarios = listOf(jsonPostScenario, xmlTooManyRequestsScenario, jsonTooManyRequestsScenario),
            protocol = SpecmaticProtocol.HTTP,
        )

        val result = TooManyRequestsHandler(feature, jsonPostScenario).handle(
            request = HttpRequest(
                method = "POST",
                path = "/ABC",
                headers = mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString()),
                body = JSONObjectValue(mapOf("age" to NumberValue(10))),
            ),
            response = HttpResponse(
                status = HttpStatusCode.TooManyRequests.value,
                headers = mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString()),
                body = StringValue("json rate limit"),
            ),
            testScenario = jsonPostScenario,
            testExecutor = object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = HttpResponse(status = 201)
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java)
    }

    @Test
    fun `should retry too-many-requests response without validating it when it is undocumented`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario), protocol = SpecmaticProtocol.HTTP)
        val handler = TooManyRequestsHandler(feature, postScenario)
        val result = handler.handle(
            HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10)))),
            HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to "0")),
            postScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = HttpResponse(status = 201)
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java); result as ResponseHandlingResult.Continue
        assertThat(result.response).isEqualTo(HttpResponse(status = 201))
    }

    @Test
    fun `should not validate undocumented too-many-requests response against default response`() {
        val defaultScenario = tooManyRequestsScenario.copy(
            httpResponsePattern = HttpResponsePattern(
                status = DEFAULT_RESPONSE_CODE,
                body = ExactValuePattern(StringValue("default error")),
            ),
        )
        val feature = Feature(name = "", scenarios = listOf(postScenario, defaultScenario), protocol = SpecmaticProtocol.HTTP)
        val handler = TooManyRequestsHandler(feature, postScenario)

        val result = handler.handle(
            HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10)))),
            HttpResponse(status = 429, body = StringValue("rate limited")),
            postScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = HttpResponse(status = 201)
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java); result as ResponseHandlingResult.Continue
        assertThat(result.response).isEqualTo(HttpResponse(status = 201))
    }

    @Test
    fun `should return failure when response doesn't mach tooManyRequests response`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val handler = TooManyRequestsHandler(feature, postScenario)
        val result = handler.handle(
            HttpRequest(),
            HttpResponse(status = 404),
            postScenario,
            throwAwayExecutor,
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST /(id:string) -> 429
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.STATUS",
                details = "Response doesn't match processing scenario\n${DefaultMismatchMessages.mismatchMessage("status 429", "status 404")}",
                OpenApiRuleViolation.STATUS_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should retry the original request while respecting retry-after header`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val sleepDurations = mutableListOf<Long>()
        val customRetryHandler = RetryHandler<MonitorResult, HttpResponse>(
            maxAttempts = 3,
            delayStrategy = DelayStrategy.RespectRetryAfter(),
            sleeper = object : Sleeper {
                override fun sleep(milliSeconds: Long) { sleepDurations.add(milliSeconds) }
            },
        )

        val handler = TooManyRequestsHandler(feature, postScenario, customRetryHandler)
        val initialRequest = HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10))))

        val result = handler.handle(
            initialRequest,
            HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to "5")),
            postScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request).isEqualTo(initialRequest)
                    return HttpResponse(status = 201)
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java); result as ResponseHandlingResult.Continue
        assertThat(result.response).isEqualTo(HttpResponse(status = 201))
        assertThat(result.responseForTestResultOverride).isEqualTo(HttpResponse(status = 201))
        assertThat(sleepDurations).containsExactly(5.seconds.inWholeMilliseconds)
    }

    @Test
    fun `should retry repeated valid too-many-requests responses using each retry-after delay`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val sleepSequence = sequenceOf(1, 2, 3, 4, 5)
        val sleepDurations = mutableListOf<Long>()
        val customRetryHandler = RetryHandler<MonitorResult, HttpResponse>(
            maxAttempts = sleepSequence.count().inc(),
            delayStrategy = DelayStrategy.RespectRetryAfter(),
            sleeper = object : Sleeper {
                override fun sleep(milliSeconds: Long) { sleepDurations.add(milliSeconds) }
            },
        )

        val handler = TooManyRequestsHandler(feature, postScenario, customRetryHandler)
        val initialRequest = HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10))))

        val result = handler.handle(
            initialRequest,
            HttpResponse(status = 429),
            postScenario,
            object : TestExecutor {
                private val iterator = sleepSequence.iterator()

                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request).isEqualTo(initialRequest)
                    return if (iterator.hasNext()) {
                        HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to iterator.next().toString()))
                    } else {
                        HttpResponse(status = 201)
                    }
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java); result as ResponseHandlingResult.Continue
        assertThat(result.response).isEqualTo(HttpResponse(status = 201))
        assertThat(result.responseForTestResultOverride).isEqualTo(HttpResponse(status = 201))
        assertThat(sleepDurations).isEqualTo(sleepSequence.map { it.seconds.inWholeMilliseconds }.toList())
    }

    @Test
    fun `should fail immediately when a later too-many-requests response violates its contract`() {
        val tooManyRequestsWithRequiredHeader = tooManyRequestsScenario.copy(
            httpResponsePattern = HttpResponsePattern(
                status = HttpStatusCode.TooManyRequests.value,
                headersPattern = HttpHeadersPattern(pattern = mapOf(HttpHeaders.RetryAfter to NumberPattern())),
            )
        )
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsWithRequiredHeader), protocol = SpecmaticProtocol.HTTP)
        var executions = 0
        val handler = TooManyRequestsHandler(
            feature,
            postScenario,
            RetryHandler(
                maxAttempts = 3,
                delayStrategy = DelayStrategy.RespectRetryAfter(),
                sleeper = object : Sleeper {
                    override fun sleep(milliSeconds: Long) = Unit
                },
            ),
        )

        val invalidRetryResponse = HttpResponse(status = 429)
        val result = handler.handle(
            HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10)))),
            HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to "0")),
            postScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    executions++
                    return invalidRetryResponse
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.response).isEqualTo(invalidRetryResponse)
        assertThat(result.result.reportString()).contains("Response doesn't match processing scenario")
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `should fail immediately when a retry returns an unexpected non-429 response`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        var executions = 0
        val unexpectedResponse = HttpResponse(status = 500)
        val handler = TooManyRequestsHandler(feature, postScenario)

        val result = handler.handle(
            HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10)))),
            HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to "0")),
            postScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    executions++
                    return unexpectedResponse
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.response).isEqualTo(unexpectedResponse)
        assertThat(result.result.reportString()).contains("Invalid response received on retry")
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `should validate the terminal response against the generated test scenario`() {
        val originalScenario = postScenario.copy(
            httpResponsePattern = HttpResponsePattern(status = 201, body = StringPattern())
        )
        val generatedTestScenario = originalScenario.copy(
            httpResponsePattern = HttpResponsePattern(
                status = 201,
                body = ExactValuePattern(StringValue("expected")),
            )
        )
        val feature = Feature(name = "", scenarios = listOf(originalScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val terminalResponse = HttpResponse(status = 201, body = "different")
        val handler = TooManyRequestsHandler(feature, originalScenario)

        val result = handler.handle(
            HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10)))),
            HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to "0")),
            generatedTestScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = terminalResponse
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.response).isEqualTo(terminalResponse)
        assertThat(result.result.reportString()).contains("Invalid response received on retry")
    }

    @Test
    fun `should work with retry-after with ISO date-time string format`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val expectedDelay = 10.seconds.inWholeMilliseconds
        val tolerance: Long = 0.5.seconds.inWholeMilliseconds
        val sleepDurations = mutableListOf<Long>()
        val customRetryHandler = RetryHandler<MonitorResult, HttpResponse>(
            maxAttempts = 3,
            delayStrategy = DelayStrategy.RespectRetryAfter(),
            sleeper = object : Sleeper {
                override fun sleep(milliSeconds: Long) { sleepDurations.add(milliSeconds) }
            },
        )

        val handler = TooManyRequestsHandler(feature, postScenario, customRetryHandler)
        val initialRequest = HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10))))
        val futureDateTime = Instant.now().plus(10.seconds.toJavaDuration()).toString()

        val result = handler.handle(
            initialRequest,
            HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to futureDateTime)),
            postScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request).isEqualTo(initialRequest)
                    return HttpResponse(status = 201)
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java); result as ResponseHandlingResult.Continue
        assertThat(result.response).isEqualTo(HttpResponse(status = 201))
        assertThat(sleepDurations.single()).isBetween(expectedDelay - tolerance, expectedDelay + tolerance)
    }

    @Test
    fun `should return failure immediately without retires when response doesn't match the expected 2xx`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        var retryAttempts = 0
        val customRetryHandler = RetryHandler<MonitorResult, HttpResponse>(
            maxAttempts = 3,
            delayStrategy = DelayStrategy.RespectRetryAfter(),
            sleeper = object : Sleeper {
                override fun sleep(milliSeconds: Long) { retryAttempts++ }
            },
        )

        val handler = TooManyRequestsHandler(feature, postScenario, customRetryHandler)
        val initialRequest = HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10))))

        val result = handler.handle(
            initialRequest,
            HttpResponse(status = 429),
            postScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse(status = 202)
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST /(id:string) -> 201
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.STATUS",
                details = "Invalid response received on retry\n${DefaultMismatchMessages.mismatchMessage("status 201", "status 202")}",
                OpenApiRuleViolation.STATUS_MISMATCH
            )
        }
        """.trimIndent())
        assertThat(retryAttempts).isEqualTo(0)
    }

    @Test
    fun `should match any 2xx response in-case the testScenario was for tooManyRequests`() {
        val acceptedScenario = postScenario.copy(httpResponsePattern = HttpResponsePattern(status = 202))
        val feature = Feature(name = "", scenarios = listOf(acceptedScenario, postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val handler = TooManyRequestsHandler(feature, tooManyRequestsScenario)
        val initialRequest = HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10))))

        val result = handler.handle(
            initialRequest,
            HttpResponse(status = 429),
            tooManyRequestsScenario,
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse(status = 202)
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java); result as ResponseHandlingResult.Continue
        assertThat(result.response).isEqualTo(HttpResponse(status = 202))
        assertThat(result.responseForTestResultOverride).isNull()
    }

    @Test
    fun `should return failure if response never resolves`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val customRetryHandler = RetryHandler<MonitorResult, HttpResponse>(
            maxAttempts = 5,
            delayStrategy = DelayStrategy.RespectRetryAfter(),
            sleeper = object : Sleeper {
                override fun sleep(milliSeconds: Long) { }
            },
        )
        val handler = TooManyRequestsHandler(feature, postScenario, customRetryHandler)
        val initialRequest = HttpRequest("POST", "/ABC", body = JSONObjectValue(mapOf("age" to NumberValue(10))))

        val result = handler.handle(
            initialRequest,
            HttpResponse(status = 429),
            postScenario,
            object : TestExecutor {
                var retryCount = 0

                override fun execute(request: HttpRequest): HttpResponse {
                    retryCount++
                    return HttpResponse(
                        status = 429,
                        headers = mapOf(HttpHeaders.RetryAfter to retryCount.toString()),
                    )
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST /(id:string) -> 201
        Max retries of 5 exceeded with POST /ABC
        """.trimIndent())
        assertThat(result.response).isEqualTo(
            HttpResponse(status = 429, headers = mapOf(HttpHeaders.RetryAfter to "5"))
        )
    }
}
