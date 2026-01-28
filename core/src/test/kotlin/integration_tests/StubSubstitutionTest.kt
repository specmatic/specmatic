package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.DefaultMismatchMessages
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SPECMATIC_STUB_DICTIONARY
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.StubConfiguration
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.osAgnosticPath
import io.specmatic.stub.HttpStub
import io.specmatic.stub.HttpStubResponse
import io.specmatic.stub.captureStandardOutput
import io.specmatic.stub.createStubFromContracts
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StubSubstitutionTest {
    @Test
    fun `stub example with substitution in response body`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_substitution_in_response_body.yaml")

        createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Jane"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val jsonResponse = response.body as JSONObjectValue
            assertThat(jsonResponse.findFirstChildByPath("name")?.toStringLiteral()).isEqualTo("Jane")
        }
    }

    @Test
    fun `stub example with substitution in response header`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_substitution_in_response_header.yaml")

        createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Jane"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseHeaders = response.headers
            assertThat(responseHeaders["X-Name"]).isEqualTo("Jane")
        }
    }

    @Test
    fun `stub example with substitution in response using request headers`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                get:
                  summary: Get data
                  parameters:
                    - in: header
                      name: X-Trace
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      headers:
                        X-Trace:
                          description: Trace id
                          schema:
                            type: string
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exampleRequest = HttpRequest("GET", "/data", headers = mapOf("X-Trace" to "(TRACE:string)"))
        val exampleResponse = HttpResponse(200, mapOf("X-Trace" to "$(TRACE)"))

        HttpStub(feature, listOf(ScenarioStub(exampleRequest, exampleResponse))).use { stub ->
            val request = HttpRequest("GET", "/data", headers = mapOf("X-Trace" to "abc123"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseHeaders = response.headers
            assertThat(responseHeaders["X-Trace"]).isEqualTo("abc123")
        }
    }

    @Test
    fun `stub example with substitution in response using request query params`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                get:
                  summary: Get data
                  parameters:
                    - in: query
                      name: traceId
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      headers:
                        X-Trace:
                          description: Trace id
                          schema:
                            type: string
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exampleRequest = HttpRequest("GET", "/data", queryParametersMap = mapOf("traceId" to "(TRACE_ID:string)"))
        val exampleResponse = HttpResponse(200, mapOf("X-Trace" to "$(TRACE_ID)"))

        HttpStub(feature, listOf(ScenarioStub(exampleRequest, exampleResponse))).use { stub ->
            val request = HttpRequest("GET", "/data", queryParametersMap = mapOf("traceId" to "abc123"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseHeaders = response.headers
            assertThat(responseHeaders["X-Trace"]).isEqualTo("abc123")
        }
    }

    @Test
    fun `fallback to the spec when substitution data is not found`() {
        createStubFromContracts(listOf("src/test/resources/openapi/substitutions/spec_with_map_substitution_in_response_body.yaml"), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"department": "facilities"}"""))
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
        }
    }

    @Test
    fun `stub example with substitution in response using request path param`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exampleRequest = HttpRequest("GET", "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, headers = mapOf("Content-Type" to "application/json"), body = parsedJSONObject("""{"id": "$(ID)"}"""))

        HttpStub(feature, listOf(ScenarioStub(exampleRequest, exampleResponse))).use { stub ->
            val request = HttpRequest("GET", "/data/abc123")
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val jsonResponseBody = response.body as JSONObjectValue
            assertThat(jsonResponseBody.findFirstChildByPath("id")).isEqualTo(StringValue("abc123"))
        }
    }

    @Test
    fun `type coersion when a stringly request param and the response value are different`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exampleRequest = HttpRequest("GET", "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, headers = mapOf("Content-Type" to "application/json"), body = parsedJSONObject("""{"id": "$(ID)"}"""))

        HttpStub(feature, listOf(ScenarioStub(exampleRequest, exampleResponse))).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val jsonResponseBody = response.body as JSONObjectValue
            assertThat(jsonResponseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(123))
        }
    }

    @Test
    fun `type coersion when a request object field value and the response object field value are different`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                post:
                  summary: Get data
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - id
                          properties:
                            id:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exampleRequest = HttpRequest("POST", "/data", body = parsedJSONObject("""{"id": "(ID:string)"}"""))
        val exampleResponse = HttpResponse(200, headers = mapOf("Content-Type" to "application/json"), body = parsedJSONObject("""{"id": "$(ID)"}"""))

        HttpStub(feature, listOf(ScenarioStub(exampleRequest, exampleResponse))).use { stub ->
            val response = stub.client.execute(HttpRequest("POST", "/data", body = parsedJSONObject("""{"id": "123"}""")))

            assertThat(response.status).isEqualTo(200)
            val jsonResponseBody = response.body as JSONObjectValue
            assertThat(jsonResponseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(123))
        }
    }

    @Test
    fun `throw an error when the value in the request body cannot be used in the body due to schema mismatch`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                post:
                  summary: Get data
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - id
                          properties:
                            id:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exampleRequest = HttpRequest("POST", "/data", body = parsedJSONObject("""{"id": "(ID:string)"}"""))
        val exampleResponse = HttpResponse(200, headers = mapOf("Content-Type" to "application/json"), body = parsedJSONObject("""{"id": "$(ID)"}"""))

        HttpStub(feature, listOf(ScenarioStub(exampleRequest, exampleResponse))).use { stub ->
            val response = stub.client.execute(exampleRequest)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains("RESPONSE.BODY.id")
        }
    }

    @Test
    fun `throw an error when the value in the request header cannot be used in the body due to schema mismatch`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                post:
                  summary: Get data
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - id
                          properties:
                            id:
                              type: string
                  responses:
                    '200':
                      description: OK
                      headers:
                        X-Id:
                          description: id from the body
                          schema:
                            type: integer
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exampleRequest = HttpRequest("POST", "/data", body = parsedJSONObject("""{"id": "(ID:string)"}"""))
        val exampleResponse = HttpResponse(200, headers = mapOf("Content-Type" to "text/plain", "X-Id" to "$(ID)"), body = StringValue("success"))

        HttpStub(feature, listOf(ScenarioStub(exampleRequest, exampleResponse))).use { stub ->
            val response = stub.client.execute(exampleRequest)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains("RESPONSE.HEADER.X-Id")
        }
    }

    @ParameterizedTest
    @CsvSource("engineering,Bangalore", "sales,Mumbai")
    fun `stub example with data substitution`(department: String, location: String) {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_map_substitution_in_response_body.yaml")

        createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"department": "$department"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val jsonResponse = response.body as JSONObjectValue
            assertThat(jsonResponse.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo(location)
        }
    }

    @ParameterizedTest
    @CsvSource("1,Bangalore", "2,Mumbai")
    fun `stub example with data substitution having integer in request`(id: String, location: String) {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_map_substitution_with_int_in_request.yaml")

        createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": $id}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val jsonResponse = response.body as JSONObjectValue
            assertThat(jsonResponse.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo(location)
        }
    }

    @Test
    fun `data substitution involving all GET request parts and response parts`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_map_substitution_in_all_get_sections.yaml")

        createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/data/abc", headers = mapOf("X-Routing-Token" to "AB"), queryParametersMap = mapOf("location" to "IN"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            assertThat(response.headers["X-Region"]).isEqualTo("IN")

            val jsonResponse = response.body as JSONObjectValue
            assertThat(jsonResponse.findFirstChildByPath("city")?.toStringLiteral()).isEqualTo("Mumbai")
            assertThat(jsonResponse.findFirstChildByPath("currency")?.toStringLiteral()).isEqualTo("INR")

        }
    }

    @Test
    fun `data substitution with explicitly referenced data key in response body`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_map_substitution_with_different_lookup_key_in_response_body.yaml")

        createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"department": "engineering"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val jsonResponse = response.body as JSONObjectValue
            assertThat(jsonResponse.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo("Mumbai")

        }
    }

    @Test
    fun `data substitution with explicitly referenced data key in response header`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_map_substitution_with_different_lookup_key_in_response_header.yaml")

        createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"department": "engineering"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            assertThat(response.headers["X-Location"]).isEqualTo("Mumbai")

        }
    }

    @Test
    @Disabled
    fun `should log the file in which a substitution error has occurred`() {
        val (output, _) = captureStandardOutput {
            try {
                val stub = createStubFromContracts(listOf(osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_non_existent_data_key.yaml")), timeoutMillis = 0)
                stub.close()
            } catch(_: Throwable) {

            }
        }

        println(output)

        assertThat(output)
            .contains("Error resolving template data for example")
            .contains(osAgnosticPath("spec_with_non_existent_data_key_examples/substitution.json"))
            .contains("@id")
    }

    @Test
    @Disabled
    fun `should flag an error when data substitution keys are not found in @data`() {
        val (output, _) = captureStandardOutput {
            try {
                val stub = createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_example_missing_the_data_section.yaml")), timeoutMillis = 0)
                stub.close()
            } catch(_: Throwable) {

            }
        }

        assertThat(output).contains("@department")
    }

    @Test
    fun `dynamic stub example with substitution in response body`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /person:
                post:
                  summary: Add person
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - department
                          properties:
                            department:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - department
                            properties:
                              department:
                                type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        HttpStub(feature).use { stub ->
            val example = """
                {
                  "http-request": {
                    "method": "POST",
                    "path": "/person",
                    "body": {
                      "department": "(DEPARTMENT:string)"
                    }
                  },
                  "http-response": {
                    "status": 200,
                    "body": {
                      "department": "$(DEPARTMENT)"
                    }
                  }
                }
            """.trimIndent()

            val exampleRequest = HttpRequest("POST", "/_specmatic/expectations", body = parsedJSONObject(example))
            stub.client.execute(exampleRequest).also { response ->
                assertThat(response.status).isEqualTo(200)
            }

            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"department": "engineering"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val responseBody = response.body as JSONObjectValue
            assertThat(responseBody.findFirstChildByPath("department")?.toStringLiteral()).isEqualTo("engineering")

        }
    }

    @Test
    fun `dynamic stub example with data substitution`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /person:
                post:
                  summary: Add person
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - department
                          properties:
                            department:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - location
                            properties:
                              location:
                                type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        HttpStub(feature).use { stub ->
            val example = """
                {
                  "data": {
                    "dept": {
                      "engineering": {
                        "city": "Mumbai"
                      }
                    }
                  },
                  "http-request": {
                    "method": "POST",
                    "path": "/person",
                    "body": {
                      "department": "(DEPARTMENT:string)"
                    }
                  },
                  "http-response": {
                    "status": 200,
                    "body": {
                      "location": "$(data.dept[DEPARTMENT].city)"
                    }
                  }
                }
            """.trimIndent()

            val exampleRequest = HttpRequest("POST", "/_specmatic/expectations", body = parsedJSONObject(example))
            stub.client.execute(exampleRequest).also { response ->
                assertThat(response.status).isEqualTo(200)
            }

            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"department": "engineering"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val responseBody = response.body as JSONObjectValue
            assertThat(responseBody.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo("Mumbai")

        }
    }

    @Test
    fun `data substitution in response body using dictionary`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/spec_with_no_substitutions.yaml")
        val dictionaryPath = osAgnosticPath("src/test/resources/openapi/substitutions/dictionary.json")
        val specmaticConfig = SpecmaticConfig(stub = StubConfiguration(dictionary = dictionaryPath))

        createStubFromContracts(
            listOf(specWithSubstitution),
            dataDirPaths = emptyList(),
            timeoutMillis = 0,
            specmaticConfig = specmaticConfig,
        ).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Charles"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.headers["X-Region"]).isEqualTo("Asia")

            val responseBody = response.body as JSONObjectValue

            assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(10))
            assertThat(responseBody.findFirstChildByPath("name")).isEqualTo(StringValue("George"))
        }
    }

    @Test
    fun `data substitution in response body at second level using dictionary`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/dictionary_value_at_second_level.yaml")

        try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, "src/test/resources/openapi/substitutions/dictionary.json")

            createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
                val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Charles"}"""))
                val response = stub.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                val responseBody = response.body as JSONObjectValue

                assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(100))
                assertThat(responseBody.findFirstChildByPath("address.street")).isEqualTo(StringValue("Baker Street"))
            }
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }

    @Test
    fun `data substitution in response body at second level within array using dictionary`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/dictionary_value_at_second_level_with_array.yaml")

        try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, "src/test/resources/openapi/substitutions/dictionary.json")

            createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
                val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Charles"}"""))
                val response = stub.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                val responseBody = response.body as JSONObjectValue

                assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(100))
                assertThat(responseBody.findFirstChildByPath("addresses.[0].street")).isEqualTo(StringValue("Baker Street"))
            }
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }

    @Test
    fun `broken dictionary should be mentioned in an error message at stub load time`() {
        try {
            val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/dictionary_value_at_second_level.yaml")
            val brokenDictionaryPath = "src/test/resources/openapi/substitutions/broken-dictionary.json"
            System.setProperty(SPECMATIC_STUB_DICTIONARY, brokenDictionaryPath)

            val (output, _) = captureStandardOutput {
                try {
                    val stub = createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0)
                    stub.close()
                } catch(e: Throwable) {
                    println(e)
                }
            }

            println(output)

            assertThat(output).contains(osAgnosticPath(brokenDictionaryPath))
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }

    @Test
    fun `should validate scalar values substituted in the dictionary at run-time`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              email:
                                type: string
                                format: email
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "email" to StringValue("$(lookup.data[ID].email)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "123": {
                        "email": "not-an-email"
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains("email")
        }
    }

    @Test
    fun `should validate complex values substituted in the dictionary at run-time`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to StringValue("$(lookup.data[ID].names)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "123": {
                        "names": [{"name": "John"}]
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains("RESPONSE.BODY.names[0]")
        }
    }

    @Test
    fun `should be able to dereference lookup from the example lookup dictionary for composite values`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to StringValue("$(lookup.data[ID].names)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "123": {
                        "names": ["John", "Jane"]
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)
            val responseBody = response.body as JSONObjectValue

            assertThat(response.status).isEqualTo(200)
            assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(StringValue("123"))
            assertThat(responseBody.findFirstChildByPath("names.[0]")).isEqualTo(StringValue("John"))
            assertThat(responseBody.findFirstChildByPath("names.[1]")).isEqualTo(StringValue("Jane"))
        }
    }

    @Test
    fun `should generate a random value using the pattern when the directive is any-value`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to StringValue("$(lookup.data[ID].names)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "123": {
                        "names": "(anyvalue)"
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)
            val responseBody = response.body as JSONObjectValue

            assertThat(response.status).isEqualTo(200)
            assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(StringValue("123"))
            assertThat(responseBody.findFirstChildByPath("names")).isInstanceOf(JSONArrayValue::class.java)
            assertThat(responseBody.findFirstChildByPath("names.[0]")).isInstanceOf(StringValue::class.java)
        }
    }

    @Test
    fun `should drop the key when the directive is drop`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to StringValue("$(lookup.data[ID].names)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "123": {
                        "names": "$(drop)"
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)
            val responseBody = response.body as JSONObjectValue

            assertThat(response.status).isEqualTo(200)
            assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(StringValue("123"))
            assertThat(responseBody.findFirstChildByPath("names")).isNull()
        }
    }

    @Test
    fun `should drop the key when there is a drop directive as direct value`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("123"),
            "names" to StringValue("$(drop)")
        )))
        val stubResponse = HttpStubResponse(exampleResponse, feature = feature, scenario = feature.scenarios.first())
        val substitutedExample = stubResponse.resolveSubstitutions(exampleRequest, exampleRequest, JSONObjectValue())

        assertThat((substitutedExample.responseBody as JSONObjectValue).keys()).containsOnly("id")
        assertThat((substitutedExample.responseBody as JSONObjectValue).getString("id")).isEqualTo("123")
    }

    @Test
    fun `should return empty array when only array values are drop directives`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("123"),
            "names" to JSONArrayValue(listOf(StringValue("$(drop)"), StringValue("$(drop)")))
        )))
        val stubResponse = HttpStubResponse(exampleResponse, feature = feature, scenario = feature.scenarios.first())
        val substitutedExample = stubResponse.resolveSubstitutions(exampleRequest, exampleRequest, JSONObjectValue())

        assertThat((substitutedExample.responseBody as JSONObjectValue).keys()).containsOnly("id", "names")
        assertThat((substitutedExample.responseBody as JSONObjectValue).getJSONArray("names").size).isEqualTo(0)
    }

    @Test
    fun `should complain when underlying schema is madnatory and value is a direct drop directive`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf("id" to StringValue("$(drop)"))))
        val stubResponse = HttpStubResponse(exampleResponse, feature = feature, scenario = feature.scenarios.first())
        val exception = assertThrows<ContractException> {
            stubResponse.resolveSubstitutions(exampleRequest, exampleRequest, JSONObjectValue())
        }

        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        >> RESPONSE.BODY.id
        Cannot drop mandatory key "id"
        """.trimIndent())
    }

    @Test
    fun `should return failure if drop directive is used on mandatory key`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to StringValue("$(lookup.data[ID].names)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "123": {
                        "names": "$(drop)"
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).isEqualToNormalizingWhitespace("""
            >> RESPONSE.BODY.names
            Cannot drop mandatory key "names"
            """.trimIndent())
        }
    }

    @Test
    fun `should not allow pattern token directive to generate mismatch values for the pattern`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to JSONArrayValue(listOf(
                StringValue("$(lookup.data[ID].name)")
            ))
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "123": {
                        "name": "(number)"
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).isEqualToNormalizingWhitespace(
                toViolationReportString(
                    breadCrumb = "RESPONSE.BODY.names[0]",
                    details = DefaultMismatchMessages.patternMismatch("string", "number"),
                    StandardRuleViolation.TYPE_MISMATCH
                )
            )
        }
    }

    @Test
    fun `should lookup wildcard value in-case no keys match the substitution key`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")
        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to StringValue("$(lookup.data[ID].names)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "*": {
                        "names": ["John", "Jane"]
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)
            val responseBody = response.body as JSONObjectValue

            assertThat(response.status).isEqualTo(200)
            assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(StringValue("123"))
            assertThat(responseBody.findFirstChildByPath("names.[0]")).isEqualTo(StringValue("John"))
            assertThat(responseBody.findFirstChildByPath("names.[1]")).isEqualTo(StringValue("Jane"))
        }
    }

    @Test
    fun `should lookup wildcard value in-case the substitution key is not captured`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data/{id}:
                get:
                  summary: Get data
                  parameters:
                    - in: path
                      name: id
                      schema:
                        type: string
                      required: true
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                items:
                                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val exampleRequest = HttpRequest(method = "GET", path = "/data/(ID:string)")

        val uncapturedVariable = "DATA_ID"

        val exampleResponse = HttpResponse(200, body = JSONObjectValue(mapOf(
            "id" to StringValue("$(ID)"),
            "names" to StringValue("$(lookup.data[$uncapturedVariable].names)")
        )))
        val dataLookup = parsedJSONObject("""
        {
            "lookup": {
                "data": {
                    "*": {
                        "names": ["John", "Jane"]
                    }
                }
            }
        }
        """.trimIndent())
        val scenarioStub = ScenarioStub(request = exampleRequest, response = exampleResponse, data = dataLookup)

        HttpStub(feature, listOf(scenarioStub)).use { stub ->
            val request = HttpRequest("GET", "/data/123")
            val response = stub.client.execute(request)
            val responseBody = response.body as JSONObjectValue

            assertThat(response.status).isEqualTo(200)
            assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(StringValue("123"))
            assertThat(responseBody.findFirstChildByPath("names.[0]")).isEqualTo(StringValue("John"))
            assertThat(responseBody.findFirstChildByPath("names.[1]")).isEqualTo(StringValue("Jane"))
        }
    }
}
