package io.specmatic.test

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResponseMonitorTest {
    companion object {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 201,
                body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
            )
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 202,
                headersPattern = HttpHeadersPattern(mapOf("Link" to StringPattern()))
            )
        ))
        val monitorScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/monitor/(id:number)"), method = "GET"),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(mapOf("request" to AnyNonNullJSONValue(), "response?" to AnyNonNullJSONValue()))
            )
        ))
    }

    private val throwAwayExecutor = object : TestExecutor {
        override fun execute(request: HttpRequest): HttpResponse { throw AssertionError() }
    }

    @Test
    fun `should return failure if accepted scenario doesn't exist`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario))
        val result = ResponseMonitor(
            feature, postScenario, response = HttpResponse(status = 202)
        ).waitForResponse(throwAwayExecutor)

        assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
        println(result.failure.reportString())
        assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
        No accepted response scenario found for POST / -> 201
        """.trimIndent())
    }

    @Test
    fun `should return failure when response doesn't mach accepted response`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario))
        val result = ResponseMonitor(
            feature, postScenario, response = HttpResponse(status = 404)
        ).waitForResponse(throwAwayExecutor)

        assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
        println(result.failure.reportString())
        assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST /  -> 202
        >> RESPONSE.STATUS
        Expected status 202, actual was status 404
        """.trimIndent())
    }

    @Test
    fun `should return failure if monitor link is not found in the response`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario))
        val result = ResponseMonitor(
            feature, postScenario, response = HttpResponse(status = 202)
        ).waitForResponse(throwAwayExecutor)

        assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
        println(result.failure.reportString())
        assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST / -> 202
        >> RESPONSE.HEADER.Link
        Expected header named "Link" was missing
        """.trimIndent())
    }

    @Test
    fun `should return failure when scenario matching monitor link is not found`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(method = "POST"),
            httpResponsePattern = HttpResponsePattern(status = 201)
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 202,
                headersPattern = HttpHeadersPattern(mapOf("Link" to StringPattern()))
            )
        ))
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario))

        val result = ResponseMonitor(feature, postScenario, response = HttpResponse(
            status = 202,
            headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor,</product/123>;rel=self")
        )).waitForResponse(throwAwayExecutor)

        assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
        println(result.failure.reportString())
        assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
        No monitor scenario found matching link: Link(url=/monitor/123, rel=related, title=monitor)
        """.trimIndent())
    }

    @Test
    fun `should make a request to the monitor link provided in headers`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario))
        val result = ResponseMonitor(feature, postScenario, response = HttpResponse(
            status = 202,
            headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor")
        ), backOffDelay = 0).waitForResponse(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/monitor/123")
                assertThat(request.method).isEqualTo("GET")
                return HttpResponse(
                    status = 200,
                    body = parsedJSONObject("""
                    {
                        "request": {
                            "method": "POST",
                            "header": [
                                { "name": "Content-Type", "value": "application/json" }
                            ]
                        },
                        "response": {
                            "statusCode": 201,
                            "header": [
                                { "name": "Content-Type", "value": "application/json" }
                            ],
                            "body": { "name": "John", "age": 20 }
                        }
                    }
                    """.trimIndent())
                )
            }
        })

        assertThat(result).isInstanceOf(HasValue::class.java); result as HasValue
        assertThat(result.value).isEqualTo(HttpResponse(
            status = 201,
            headers = mapOf("Content-Type" to "application/json"),
            body = parsedJSONObject("""{"name": "John", "age": 20}""")
        ))
    }

    @Test
    fun `should retry if the monitor response is not complete`() {
        var count = 0
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario))

        val result = ResponseMonitor(feature, postScenario, response = HttpResponse(
            status = 202,
            headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor")
        ), backOffDelay = 0).waitForResponse(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println("Got request for ${request.path}, count=$count")
                assertThat(request.path).isEqualTo("/monitor/123")
                assertThat(request.method).isEqualTo("GET")
                if (count == 0) {
                    count++
                    return HttpResponse(
                        status = 200,
                        body = parsedJSONObject("""
                        {
                            "request": {
                                "method": "POST",
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ]
                            },
                            "response": {}
                        }
                        """.trimIndent())
                    )
                }

                return HttpResponse(
                    status = 200,
                    body = parsedJSONObject("""
                    {
                        "request": {
                            "method": "POST",
                            "header": [
                                { "name": "Content-Type", "value": "application/json" }
                            ]
                        },
                        "response": {
                            "statusCode": 201,
                            "header": [
                                { "name": "Content-Type", "value": "application/json" }
                            ],
                            "body": { "name": "John", "age": 20 }
                        }
                    }
                    """.trimIndent())
                )
            }
        })

        assertThat(result).isInstanceOf(HasValue::class.java); result as HasValue
        assertThat(count).isEqualTo(1)
        assertThat(result.value).isEqualTo(HttpResponse(
            status = 201,
            headers = mapOf("Content-Type" to "application/json"),
            body = parsedJSONObject("""{"name": "John", "age": 20}""")
        ))
    }

    @Test
    fun `should return failure when max retries have exceeded`() {
        var count = 0
        val maxRetries = 2
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario))

        val result = ResponseMonitor(feature, postScenario, response = HttpResponse(
            status = 202,
            headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor")
        ), maxRetry = maxRetries, backOffDelay = 0).waitForResponse(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                count++
                println("Got request for ${request.path}, count=$count")
                assertThat(request.path).isEqualTo("/monitor/123")
                assertThat(request.method).isEqualTo("GET")
                return HttpResponse(
                    status = 200,
                    body = parsedJSONObject("""
                        {
                            "request": {
                                "method": "POST",
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ]
                            },
                            "response": {}
                        }
                        """.trimIndent())
                )
            }
        })

        assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
        assertThat(count).isEqualTo(maxRetries)
        assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
        Max retries exceeded, monitor link: Link(url=/monitor/123, rel=related, title=monitor)
        """.trimIndent())
    }

    @Test
    fun `should perform exponential backoff between retries`() {
        val sleepDurations = mutableListOf<Long>()
        val customSleeper = object : Sleeper {
            override fun sleep(milliSeconds: Long) { sleepDurations.add(milliSeconds) }
        }
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario))

        val result = ResponseMonitor(feature, postScenario, response = HttpResponse(
            status = 202,
            headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor")
        ), maxRetry = 5, backOffDelay = 1000, sleeper = customSleeper).waitForResponse(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/monitor/123")
                assertThat(request.method).isEqualTo("GET")
                return HttpResponse(
                    status = 200,
                    body = parsedJSONObject("""
                    {
                        "request": {
                            "method": "POST",
                            "header": [
                                { "name": "Content-Type", "value": "application/json" }
                            ]
                        },
                        "response": {}
                    }
                """.trimIndent())
                )
            }
        })

        assertThat(sleepDurations).containsExactly(1000L, 2000L, 4000L, 8000L)
        assertThat(result).isInstanceOf(HasFailure::class.java)
        result as HasFailure
        assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
        Max retries exceeded, monitor link: Link(url=/monitor/123, rel=related, title=monitor)
        """.trimIndent())
    }

    @Test
    fun `should return an error when monitor response is invalid`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario))
        val result = ResponseMonitor(feature, postScenario, response = HttpResponse(
            status = 202,
            headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor")
        ), backOffDelay = 0).waitForResponse(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/monitor/123")
                assertThat(request.method).isEqualTo("GET")
                return HttpResponse(
                    status = 200,
                    body = parsedJSONObject("""
                    {
                        "request": {
                            "method": "POST",
                            "header": [
                                { "name": "Content-Type", "value": "application/json" }
                            ]
                        },
                        "response": {
                            "statusCode": 201,
                            "header": [
                                { "name": "Content-Type", "value": "application/json" }
                            ],
                            "body": { "name": 123, "age": "John" }
                        }
                    }
                    """.trimIndent())
                )
            }
        })

        assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
        assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
        >> MONITOR.RESPONSE.BODY.name
        Expected string, actual was 123 (number)
        >> MONITOR.RESPONSE.BODY.age
        Expected number, actual was "John"
        """.trimIndent())
    }

    @Test
    fun `extraHeaders from monitor response payload should be allowed`() {
        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario))
        val response = JSONObjectValue(mapOf(
            "statusCode" to NumberValue(201),
            "body" to JSONObjectValue(mapOf("name" to StringValue("John"), "age" to NumberValue(20))),
            "header" to JSONArrayValue(listOf(
                JSONObjectValue(mapOf("name" to StringValue("Content-Type"), "value" to StringValue("application/json"))),
                JSONObjectValue(mapOf("name" to StringValue("X-Extra"), "value" to StringValue("Extra-Value"))),
            ))
        ))

        val result = ResponseMonitor(feature, postScenario, response = HttpResponse(
            status = 202,
            headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor")
        ), backOffDelay = 0).waitForResponse(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/monitor/123")
                assertThat(request.method).isEqualTo("GET")
                return HttpResponse(
                    status = 200,
                    body = JSONObjectValue(mapOf(
                        "request" to postScenario.generateHttpRequest().updateHeader("EXTRA-HEADER", "Extra-Value").toJSON(),
                        "response" to response
                    ))
                )
            }
        })

        assertThatExtraHeaderWasAccepted(result)

        result as HasValue
        assertThat(result.value.status).isEqualTo(201)
        assertThat(result.value.headers).isEqualTo(mapOf(
            "Content-Type" to "application/json",
            "X-Extra" to "Extra-Value"
        ))
        assertThat(result.value.body).isEqualTo(response.jsonObject["body"])
    }

    private fun assertThatExtraHeaderWasAccepted(result: ReturnValue<HttpResponse>) {
        assertThat(result).isInstanceOf(HasValue::class.java)
    }
}