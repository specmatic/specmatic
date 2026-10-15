package integration_tests

import io.specmatic.testPort
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SPECMATIC_TYPE_HEADER
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.mock.ScenarioStub
import io.specmatic.mock.TRANSIENT_MOCK_ID
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResponseCodeHintingTest {
    @Test
    fun `stub should return stubbed response as per the hinted status code`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /person:
                post:
                  summary: Simple POST endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                    '202':
                      description: OK
                      content:
                        text/plain:
                          schema:
                              type: string
            """.trimIndent(), ""
        ).toFeature()

        HttpStub(listOf(feature), port = testPort()).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "John Doe"}"""))

            val dynamic200Example = ScenarioStub(request, HttpResponse.ok("200 response")).toJSON()
            val dynamic202Example = ScenarioStub(request, HttpResponse(202, "202 response")).toJSON()

            listOf(dynamic202Example, dynamic200Example).forEach {
                stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = it)).let { response ->
                    assertThat(response.status).isIn(200)
                }
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "200")).let { response ->
                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "202")).let { response ->
                assertThat(response.status).isEqualTo(202)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }
        }
    }

    @Test
    fun `stub should generate random response as per the hinted status code`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /:
                post:
                  summary: Simple POST endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                    '202':
                      description: OK
                      content:
                        text/plain:
                          schema:
                              type: string
            """.trimIndent(), ""
        ).toFeature()

        HttpStub(listOf(feature), port = testPort()).use { stub ->
            val request = HttpRequest("POST", "/", body = parsedJSONObject("""{"name": "John Doe"}"""))

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "200")).let { response ->
                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers).containsEntry(SPECMATIC_TYPE_HEADER, "random")
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "202")).let { response ->
                assertThat(response.status).isEqualTo(202)
                assertThat(response.headers).containsEntry(SPECMATIC_TYPE_HEADER, "random")
            }
        }
    }

    @Test
    fun `random response should be generated by the stub using the hinted status code even if a matching stub is found for the same request with another status code`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /person:
                post:
                  summary: Simple POST endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                    '202':
                      description: OK
                      content:
                        text/plain:
                          schema:
                              type: string
            """.trimIndent(), ""
        ).toFeature()

        HttpStub(listOf(feature), port = testPort()).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "John Doe"}"""))

            val dynamic200Example = ScenarioStub(request, HttpResponse.ok("200 response")).toJSON()

            stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = dynamic200Example)).let { response ->
                assertThat(response.status).isIn(200)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "202")).let { response ->
                assertThat(response.status).isEqualTo(202)
                assertThat(response.headers).containsEntry(SPECMATIC_TYPE_HEADER, "random")
            }
        }
    }

    @Test
    fun `stub should return response from transient mock if it has the expected status code`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /person:
                post:
                  summary: Simple POST endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                    '202':
                      description: OK
                      content:
                        text/plain:
                          schema:
                              type: string
            """.trimIndent(), ""
        ).toFeature()

        HttpStub(listOf(feature), port = testPort()).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "John Doe"}"""))

            val dynamic200Example = ScenarioStub(request, HttpResponse(202, "202 response")).toJSON().addEntry(TRANSIENT_MOCK_ID, "abc123")

            stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = dynamic200Example)).let { response ->
                assertThat(response.status).isIn(200)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "202")).let { response ->
                assertThat(response.status).isEqualTo(202)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "202")).let { response ->
                assertThat(response.status).isEqualTo(202)
                assertThat(response.headers).containsEntry(SPECMATIC_TYPE_HEADER, "random")
            }
        }
    }

    @Test
    fun `stub should return a random response if it has a matching transient mock but not with the expected status code`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /person:
                post:
                  summary: Simple POST endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                    '202':
                      description: OK
                      content:
                        text/plain:
                          schema:
                              type: string
            """.trimIndent(), ""
        ).toFeature()

        HttpStub(listOf(feature), port = testPort()).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "John Doe"}"""))

            val dynamic200Example = ScenarioStub(request, HttpResponse(202, "202 response")).toJSON().addEntry(TRANSIENT_MOCK_ID, "abc123")

            stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = dynamic200Example)).let { response ->
                assertThat(response.status).isIn(200)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "200")).let { response ->
                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers).containsEntry(SPECMATIC_TYPE_HEADER, "random")
            }
        }
    }

    @Test
    fun `strict mode stub should return a failure if a status code is hinted but it is not stubbed out`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /:
                post:
                  summary: Simple POST endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                    '202':
                      description: OK
                      content:
                        text/plain:
                          schema:
                              type: string
            """.trimIndent(), ""
        ).toFeature()

        HttpStub(listOf(feature), strictMode = true, port = testPort()).use { stub ->
            val request = HttpRequest("POST", "/", body = parsedJSONObject("""{"name": "John Doe"}"""))

            val dynamic200Example = ScenarioStub(request, HttpResponse.ok("200 response")).toJSON()

            stub.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = dynamic200Example)).let { response ->
                assertThat(response.status).isIn(200)
            }

            stub.client.execute(request.addHeaderIfMissing(SPECMATIC_RESPONSE_CODE_HEADER, "202")).let { response ->
                assertThat(response.status).isEqualTo(400)
            }
        }
    }

    @Test
    fun `test should send the relevant response code for both positive and negative tests`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 1.0.0
            paths:
              /:
                post:
                  summary: Simple POST endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                    '400':
                      description: Bad Request
                      content:
                        text/plain:
                          schema:
                              type: string
            """.trimIndent(), "").toFeature().enableGenerativeTesting()

        val results = feature.executeTests(object : TestExecutor {
            var expectedResponseCode = 0
            var testDescription = ""

            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.expectedResponseCode()).withFailMessage("Expected hinted response code ${request.expectedResponseCode()} to be equal to $expectedResponseCode in test scenario $testDescription").isEqualTo(expectedResponseCode)
                return HttpResponse(expectedResponseCode, "done")
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                testDescription = scenario.testDescription()
                expectedResponseCode = scenario.status
            }
        })

        assertThat(results.testCount).isGreaterThan(0)
        assertThat(results.success()).withFailMessage(results.report()).isTrue()

    }
}
