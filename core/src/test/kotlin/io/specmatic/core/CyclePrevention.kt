package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

class CyclePrevention {
    @Test
    fun `one level test`() {
        val contract = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                post:
                  description: Get data
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/TopLevel'
                  responses:
                    '200':
                      description: data
            components:
              schemas:
                TopLevel:
                  nullable: true
                  type: object
                  properties:
                    key:
                      ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        var testCount = 0

        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                testCount += 1
                println(request.toLogString())
                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }

        })

        assertThat(testCount).isEqualTo(4)
    }

    @Test
    fun `two level test`() {
        val contract = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                post:
                  description: Get data
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/TopLevel'
                  responses:
                    '200':
                      description: data
            components:
              schemas:
                TopLevel:
                  nullable: true
                  type: object
                  properties:
                    key:
                      ${'$'}ref: '#/components/schemas/NextLevel'
                NextLevel:
                  nullable: true
                  type: object
                  properties:
                    subkey:
                      ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        var testCount = 0

        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                testCount += 1
                println(request.toLogString())
                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }

        })

        assertThat(testCount).isEqualTo(8)
    }

    @Test
    fun `openapi response generation terminates for indirect optional cycle`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/A'
            components:
              schemas:
                A:
                  type: object
                  properties:
                    b:
                      ${'$'}ref: '#/components/schemas/B'
                B:
                  type: object
                  properties:
                    a:
                      ${'$'}ref: '#/components/schemas/A'
        """.trimIndent(), ""
        ).toFeature()

        val scenario = feature.scenarios.single()

        val generated = scenario.httpResponsePattern.body.generate(scenario.resolver).toStringLiteral()

        assertThat(generated).contains("\"b\"")
        assertThat(Regex("\"a\"").findAll(generated).count()).isLessThan(3)
    }

    @RepeatedTest(5)
    fun `test cycle in optional key to circular ref`() {
        val stubContract = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  properties:
                    key:
                      ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        HttpStub(stubContract).use { stub ->
            val randomResponse = stub.client.execute(HttpRequest("GET", "/data"))
            assertThat(
                stubContract.scenarios.first().let { scenario ->
                    scenario.httpResponsePattern.matchesResponse(
                        randomResponse,
                        scenario.resolver
                    )
                }).isInstanceOf(
                Success::class.java
            )

            val rawJSON = """
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/data"
                    },
                    "http-response": {
                        "status": 200,
                        "body": {
                            "key": {
                                "key": {
                                    "key": {}
                                }
                            }
                        }
                    }
                }
            """.trimIndent()

            val expectationSettingResponse = setExpectation(rawJSON, stub)

            assertThat(expectationSettingResponse.status).isEqualTo(200)
        }

    }

    private fun setExpectation(rawJSON: String, it: HttpStub): HttpResponse {
        val expectation = parsedJSON(rawJSON)
        return it.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = expectation))
    }

    @RepeatedTest(5)
    fun `test cycle in required key to nullable ref`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  required:
                    - key
                  properties:
                    key:
                      oneOf:
                        - type: object
                          properties: {}
                          nullable: true
                        - ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        HttpStub(feature).use { stub ->
            val response = stub.client.execute(HttpRequest("GET", "/data"))

            assertThat(
                feature.scenarios.first().let { scenario ->
                    scenario.httpResponsePattern.matchesResponse(
                        response,
                        scenario.resolver
                    )
                }).isInstanceOf(
                Success::class.java
            )

            assertThat(
                setExpectation(
                    """
                        {
                            "http-request": {
                                "method": "GET",
                                "path": "/data"
                            },
                            "http-response": {
                                "status": 200,
                                "body": {
                                    "key": {
                                        "key": {
                                            "key": null
                                        }
                                    }
                                }
                            }
                        }
                    """.trimIndent(), stub
                ).status
            ).isEqualTo(200)
        }
    }

    @RepeatedTest(5)
    fun `test cycle in optional key to nullable ref`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  nullable: true
                  properties:
                    key:
                      ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        HttpStub(feature).use { stub ->
            val response = stub.client.execute(HttpRequest("GET", "/data"))

            assertThat(
                feature.scenarios.first().let { scenario ->
                    scenario.httpResponsePattern.matchesResponse(
                        response,
                        scenario.resolver
                    )
                }).isInstanceOf(
                Success::class.java
            )

            assertThat(
                setExpectation(
                    """
                        {
                            "http-request": {
                                "method": "GET",
                                "path": "/data"
                            },
                            "http-response": {
                                "status": 200,
                                "body": {
                                    "key": {
                                        "key": {
                                            "key": null
                                        }
                                    }
                                }
                            }
                        }
                    """.trimIndent(), stub
                ).status
            ).isEqualTo(200)

            assertThat(
                setExpectation(
                    """
                        {
                            "http-request": {
                                "method": "GET",
                                "path": "/data"
                            },
                            "http-response": {
                                "status": 200,
                                "body": {
                                    "key": {
                                        "key": {
                                            "key": {}
                                        }
                                    }
                                }
                            }
                        }
                    """.trimIndent(), stub
                ).status
            ).isEqualTo(200)
        }
    }

    @Test
    fun `test cycle in key to circular ref`() {
//        key -> circular-ref-value

        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  required:
                  - key
                  properties:
                    key:
                      ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        HttpStub(feature).use {
            val response = it.client.execute(HttpRequest("GET", "/data"))
            assertThat(response.status).isEqualTo(400)

            assertThat(
                setExpectation(
                    """
                        {
                            "http-request": {
                                "method": "GET",
                                "path": "/data"
                            },
                            "http-response": {
                                "status": 200,
                                "body": {
                                    "key": {
                                        "key": {
                                            "key": {}
                                        }
                                    }
                                }
                            }
                        }
                    """.trimIndent(), it
                ).status
            ).isEqualTo(400)
        }
    }

    @RepeatedTest(5)
    fun `test cycle in required key to optional key to circular ref`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  required:
                    - key1
                  properties:
                    key1:
                      ${'$'}ref: '#/components/schemas/NextLevel'
                NextLevel:
                  type: object
                  properties:
                    key2:
                      ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        val response = HttpStub(feature).use {
            it.client.execute(HttpRequest("GET", "/data"))
        }

        assertThat(
            feature.scenarios.first().let { it.httpResponsePattern.matchesResponse(response, it.resolver) }).isInstanceOf(
            Success::class.java
        )
    }

    @RepeatedTest(5)
    fun `test cycle in required key to required key to nullable circular ref`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  required:
                    - key1
                  properties:
                    key1:
                      ${'$'}ref: '#/components/schemas/NextLevel'
                NextLevel:
                  type: object
                  required:
                    - key2
                  properties:
                    key2:
                      oneOf:
                        - type: object
                          properties: {}
                          nullable: true
                        - ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        val response = HttpStub(feature).use {
            it.client.execute(HttpRequest("GET", "/data"))
        }

        assertThat(
            feature.scenarios.first().let { it.httpResponsePattern.matchesResponse(response, it.resolver) }).isInstanceOf(
            Success::class.java
        )
    }

    @RepeatedTest(5)
    fun `test cycle in required key to optional key to nullable circular ref`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  required:
                    - key1
                  properties:
                    key1:
                      ${'$'}ref: '#/components/schemas/NextLevel'
                NextLevel:
                  type: object
                  properties:
                    key2:
                      oneOf:
                        - type: object
                          properties: {}
                          nullable: true
                        - ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        val response = HttpStub(feature).use {
            it.client.execute(HttpRequest("GET", "/data"))
        }

        assertThat(
            feature.scenarios.first().let { it.httpResponsePattern.matchesResponse(response, it.resolver) }).isInstanceOf(
            Success::class.java
        )
    }

    @Test
    fun `test cycle in required key to required key to circular ref`() {
//        key1 -> { key2 -> circular-ref-value }

        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.0"
            info:
              version: 1.0.0
              title: Data API
            paths:
              /data:
                get:
                  description: Get data
                  responses:
                    '200':
                      description: data
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TopLevel'
            components:
              schemas:
                TopLevel:
                  type: object
                  required:
                  - key1
                  properties:
                    key1:
                      ${'$'}ref: '#/components/schemas/NextLevel'
                NextLevel:
                  type: object
                  required:
                  - key2
                  properties:
                    key2:
                      ${'$'}ref: '#/components/schemas/TopLevel'
        """.trimIndent(), ""
        ).toFeature()

        val response = HttpStub(feature).use {
            it.client.execute(HttpRequest("GET", "/data"))
        }

        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `cycle prevention across arrays`() {
        val spec = """
openapi: 3.0.0
info:
  title: Add Person API
  version: 1.0.0

# Path for adding a person
paths:
  /person:
    post:
      summary: Add a new person
      requestBody:
        required: true
        content:
          application/json:
            schema:
              ${"$"}ref: '#/components/schemas/Person'
      responses:
        '200':
          description: Person created successfully
          content:
            text/plain:
              schema:
                type: string
components:
  schemas:
    Person:
      type: object
      required:
        - name
        - relatives
      properties:
        name:
          type: string
          description: Name of the person
        relatives:
          type: array
          items:
            ${"$"}ref: '#/components/schemas/Person'
          description: Array of relative Person objects (optional)

        """.trimIndent()

        try {
            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    println(request.toLogString())

                    val relatives = (request.body as JSONObjectValue).getJSONArray("relatives")
                    relatives.forEach {
                        assertThat((it as JSONObjectValue).jsonObject).containsKeys("relatives")
                    }

                    return HttpResponse.Companion.OK
                }
            })

            assertThat(results.successCount).withFailMessage(results.report()).isPositive()
            assertThat(results.success()).withFailMessage(results.report()).isTrue()
        } catch (e: Throwable) {
            println(exceptionCauseMessage(e))
            throw e
        }

    }

    @Test
    fun `cycle prevention across array of array of objects pointing to parent via a mandatory key`() {
        val spec = """
openapi: 3.0.0
info:
  title: Add Person API
  version: 1.0.0

# Path for adding a person
paths:
  /person:
    post:
      summary: Add a new person
      requestBody:
        required: true
        content:
          application/json:
            schema:
              ${"$"}ref: '#/components/schemas/Person'
      responses:
        '200':
          description: Person created successfully
          content:
            text/plain:
              schema:
                type: string
components:
  schemas:
    Person:
      type: object
      required:
        - name
        - relatives
      properties:
        name:
          type: string
          description: Name of the person
        relatives:
          type: array
          items:
            ${"$"}ref: '#/components/schemas/Relative'
    Relative:
      type: object
      required:
        - id
        - acquaintances
      properties:
        id:
          type: number
        acquaintances:
          type: array
          items:
            ${"$"}ref: '#/components/schemas/Acquaintance'
    Acquaintance:
      type: object
      required:
        - id
        - relative
      properties:
        id:
          type: number
        relative:
          ${"$"}ref: '#/components/schemas/Person'

        """.trimIndent()

        val distantRelativesSeen = mutableListOf<JSONArrayValue>()

        try {
            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    println(request.toLogString())

                    val requestBody = request.body as JSONObjectValue

                    val distantRelatives = requestBody.findFirstChildByPath("relatives.[0].acquaintances.[0].relative.relatives.[0].acquaintances")

                    if(distantRelatives != null && distantRelatives is JSONArrayValue)
                        distantRelativesSeen.add(distantRelatives)

                    return HttpResponse.Companion.OK
                }
            })

            assertThat(results.successCount).withFailMessage(results.report()).isPositive()

            assertThat(distantRelativesSeen).allSatisfy {
                assertThat(it.list).isEmpty()
            }

            assertThat(results.success()).withFailMessage(results.report()).isTrue()
        } catch (e: Throwable) {
            println(exceptionCauseMessage(e))
            throw e
        }

    }
}
