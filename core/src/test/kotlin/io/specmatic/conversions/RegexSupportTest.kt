package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Results
import io.specmatic.core.pattern.RegExSpec
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class RegexSupportTest {
    private val regex = "[0-9a-f]{24}"

    @Test
    fun `invalid regex results in exception`() {
        assertThatThrownBy {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                                  pattern: '[0-9a-f]{24'
                      responses:
                        204:
                          description: "Get person by id"
                          content: {}
                """.trimIndent(), "").toFeature()}.satisfies(Consumer {
            assertThat(it).isInstanceOf(ContractException::class.java)
        })
    }

    @Test
    fun `a test having string with pattern in a request should generate a string matching the pattern`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                                  pattern: '$regex'
                      responses:
                        204:
                          description: "Get person by id"
                          content: {}
                """.trimIndent(), "").toFeature()

        val mockTestClient = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())

                val requestBodyAsJSON = request.body as JSONObjectValue

                assertThat(requestBodyAsJSON.getString("id")).matches(regex)

                return HttpResponse(204)
            }
        }

        val results: Results = feature.executeTests(mockTestClient)

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `a test having string with a non-capturing group pattern should generate a matching string`() {
        val nonCapturingRegex = "[A-Z]+(?:_[A-Z]+)"
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                                  pattern: '$nonCapturingRegex'
                      responses:
                        204:
                          description: "Get person by id"
                          content: {}
                """.trimIndent(), "").toFeature()

        val mockTestClient = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val requestBodyAsJSON = request.body as JSONObjectValue
                assertThat(requestBodyAsJSON.getString("id")).matches(nonCapturingRegex)
                return HttpResponse(204)
            }
        }

        val results: Results = feature.executeTests(mockTestClient)

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `non-capturing groups are stripped from the regex used for generation`() {
        val nonCapturingRegex = "[A-Z]+(?:_[A-Z]+)"
        val cleanedRegex = RegExSpec(nonCapturingRegex).toString()

        assertThat(cleanedRegex).isEqualTo("[A-Z]+(_[A-Z]+)")
        assertThat(cleanedRegex).doesNotContain("?:")
    }

    @Test
    fun `a test having string with pattern in a request should accept a string matching the pattern`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                                  pattern: '$regex'
                            examples:
                              ADD:
                                value:
                                  id: "012345678901234567890123"
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                ADD:
                                  value: "success"
                """.trimIndent(), "").toFeature()

        val mockTestClient = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())

                val requestBodyAsJSON = request.body as JSONObjectValue

                assertThat(requestBodyAsJSON.getString("id")).isEqualTo("012345678901234567890123")

                return HttpResponse(200)
            }
        }

        val results: Results = feature.executeTests(mockTestClient)

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `a test having string with pattern in a request should reject a string that does not match the pattern`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                                  pattern: '$regex'
                            examples:
                              ADD:
                                value:
                                  id: "1"
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                ADD:
                                  value: "success"
                """.trimIndent(), "").toFeature()

        val mockTestClient = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(200)
            }
        }

        val results = feature.executeTests(mockTestClient)

        assertThat(results.success()).isFalse()
        assertThat(results.report()).contains(regex)
    }

    @Nested
    inner class Stubbing {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                                  pattern: '$regex'
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            application/json:
                              schema:
                                type: object
                                required:
                                - id
                                properties:
                                  id:
                                    type: string
                                    pattern: '$regex'
                """.trimIndent(), "").toFeature()

        private val doesNotMatchRegex = "123"
        private val matchesReqex = "012345678901234567890123"

        @Test
        fun `an API spec with pattern in a request and response match expectations that match the patterns`() {
            val expectation = ScenarioStub(
                HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "$matchesReqex"}""")),
                HttpResponse(200, body = parsedJSONObject("""{"id": "$matchesReqex"}"""))
            )

            HttpStub(feature).use { stub ->
                stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = expectation.toJSON())).let { response ->
                    assertThat(response.status).isEqualTo(200)
                }
            }
        }

        @Test
        fun `an API spec with pattern in a request and response should reject expectations that do not match the patterns`() {
            val regexBreakingRequest = HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "$doesNotMatchRegex"}"""))
            val regexBreakingResponse = HttpResponse(200, body = parsedJSONObject("""{"id": "$doesNotMatchRegex"}"""))
            val goodRequest = HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "$matchesReqex"}"""))
            val goodResponse = HttpResponse(200, body = parsedJSONObject("""{"id": "$matchesReqex"}"""))

            val badRequestExpectation = ScenarioStub(
                regexBreakingRequest,
                goodResponse
            )

            val badResponseExpectation = ScenarioStub(
                goodRequest,
                regexBreakingResponse
            )

            HttpStub(feature).use { stub ->
                stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = badRequestExpectation.toJSON())).let { response ->
                    assertThat(response.status).isEqualTo(400)
                }

                stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = badResponseExpectation.toJSON())).let { response ->
                    assertThat(response.status).isEqualTo(400)
                }
            }
        }

        @Test
        fun `an API spec with pattern in a request and response should match request values against the regex and generate response values when there are no expectations`() {
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "$matchesReqex"}"""))

            HttpStub(feature).use { stub ->
                val response = stub.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                val responseBody = response.body as JSONObjectValue

                assertThat(responseBody.jsonObject["id"]?.toStringLiteral()).matches(regex)
            }
        }

        @Test
        fun `an API spec with pattern in a request should reject requests that do not match the pattern when there are no expectations`() {
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "$doesNotMatchRegex"}"""))

            HttpStub(feature).use { stub ->
                val response = stub.client.execute(request)

                assertThat(response.status).isEqualTo(400)
            }

        }
    }
}
