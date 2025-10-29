package io.specmatic.test.handlers

import io.ktor.http.*
import io.specmatic.core.*
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
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
        ))

        private val tooManyRequestsScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = HttpStatusCode.TooManyRequests.value),
        ))

        private val throwAwayExecutor = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse { throw AssertionError() }
        }
    }

    @Test
    fun `should retry failure if too-many-requests response is not possible`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario))
        val handler = TooManyRequestsHandler(feature, postScenario)
        val result = handler.handle(
            HttpRequest(),
            HttpResponse(status = 429),
            postScenario,
            throwAwayExecutor,
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.result.reportString()).isEqualToNormalizingWhitespace("""
        No tooManyRequests scenario found for POST /(id:string) -> 201
        """.trimIndent())
    }

    @Test
    fun `should return failure when response doesn't mach tooManyRequests response`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario))
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
        >> RESPONSE.STATUS
        Response doesn't match processing scenario
        Expected status 429, actual was status 404
        """.trimIndent())
    }

    @Test
    fun `should retry the original request while respecting retry-after header`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario))
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
        assertThat(sleepDurations).containsExactly(5.seconds.inWholeMilliseconds)
    }

    @Test
    fun `should retry for specified times while following the next retry-after delay from response`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario))
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
                        HttpResponse(status = 402, headers = mapOf(HttpHeaders.RetryAfter to iterator.next().toString()))
                    } else {
                        HttpResponse(status = 201)
                    }
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Continue::class.java); result as ResponseHandlingResult.Continue
        assertThat(result.response).isEqualTo(HttpResponse(status = 201))
        assertThat(sleepDurations).isEqualTo(sleepSequence.map { it.seconds.inWholeMilliseconds }.toList())
    }

    @Test
    fun `should work with retry-after with ISO date-time string format`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario))
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
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario))
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
        >> RESPONSE.STATUS
        Invalid 2xx response received on retry
        Expected status 201, actual was status 202
        """.trimIndent())
        assertThat(retryAttempts).isEqualTo(0)
    }

    @Test
    fun `should match any 2xx response in-case the testScenario was for tooManyRequests`() {
        val acceptedScenario = postScenario.copy(httpResponsePattern = HttpResponsePattern(status = 202))
        val feature = Feature(name = "", scenarios = listOf(acceptedScenario, postScenario, tooManyRequestsScenario))
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
    }

    @Test
    fun `should return failure if response never resolves`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario))
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
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse(status = 429)
                }
            },
        )

        assertThat(result).isInstanceOf(ResponseHandlingResult.Stop::class.java); result as ResponseHandlingResult.Stop
        assertThat(result.result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST /(id:string) -> 201
        Max retries of 5 exceeded with POST /ABC
        """.trimIndent())
    }
}
