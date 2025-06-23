package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.SPECMATIC_STUB_DICTIONARY
import io.specmatic.core.log.DebugLogger
import io.specmatic.core.log.withLogger
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.osAgnosticPath
import io.specmatic.stub.HttpStub
import io.specmatic.stub.captureStandardOutput
import io.specmatic.stub.createStubFromContracts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PartialExampleTest {
    @Test
    fun `stub should load and match a partial example with concrete values in request and response bodies`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_in_response_body.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Stan", "department": "engineering"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue
            assertThat(responseBody.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo("Mumbai")
        }
    }

    @Test
    fun `stub should use a partial example with template params in request and response`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_and_substitution_in_response_body.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Stan", "department": "engineering"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue

            assertThat(responseBody.jsonObject).containsKeys("id")
            assertThat(responseBody.jsonObject).containsKeys("name")

            assertThat(responseBody.findFirstChildByPath("department")?.toStringLiteral()).isEqualTo("engineering")
            assertThat(responseBody.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo("Mumbai")
        }
    }

    @Test
    fun `stub should honor schema key optionality in response returned using a partial example`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_and_optional_key_in_response_body.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Stan", "department": "engineering"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue

            assertThat(responseBody.jsonObject).containsKeys("id")

            assertThat(responseBody.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo("Mumbai")
        }
    }

    @Test
    fun `stub should match partial query params`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_and_key_in_query.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person", queryParametersMap = mapOf("category" to "technology"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue

            assertThat(responseBody.jsonObject).containsKeys("id")

            assertThat(responseBody.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo("Chennai")
        }
    }

    @Test
    fun `stub generate optional templated response header`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_of_optional_response_header.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person", queryParametersMap = mapOf("category" to "technology"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            assertThat(response.headers["X-Trace-ID"]).isEqualTo("abc123")
            val responseBody = response.body as JSONObjectValue
            assertThat(responseBody.jsonObject).containsKeys("id")
        }
    }

    @Test
    fun `stub should fail to match request missing mandatory query params`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_and_mandatory_key_in_query.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person", queryParametersMap = mapOf("category" to "technology"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains(">> REQUEST.PARAMETERS.QUERY.words")
        }
    }

    @Test
    fun `stub should match partial request headers`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_and_key_in_header.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person", headers = mapOf("category" to "technology"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue

            assertThat(responseBody.jsonObject).containsKeys("id")

            assertThat(responseBody.findFirstChildByPath("location")?.toStringLiteral()).isEqualTo("Chennai")
        }
    }

    @Test
    fun `stub should fail to match request missing mandatory request header`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_template_and_mandatory_key_in_header.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person", headers = mapOf("category" to "technology"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains(">> REQUEST.PARAMETERS.HEADER.words")
        }
    }

    @Test
    fun `stub should match with request bodies with header in request and response templated`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_multiple_pieces_and_one_templated_item.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", path = "/person", headers = mapOf("X-Trace-ID" to "abc123"), body = parsedJSONObject("""{"department": "marketing"}"""))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            assertThat(response.headers["X-Trace-ID"]).isEqualTo("abc123")

            val responseBody = response.body as JSONObjectValue

            assertThat(responseBody.jsonObject).containsKeys("id")
            assertThat(responseBody.jsonObject).containsEntry("location", StringValue("Mumbai"))
        }
    }

    @Test
    fun `partial with invalid json value should get rejected` () {
        val (output, _) = captureStandardOutput {
            withLogger(DebugLogger) {
                val stub = createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/spec_with_invalid_partial_json_value.yaml")), timeoutMillis = 0)
                stub.close()
            }
        }

        println(output)

        assertThat(output).contains(">> REQUEST.BODY.department")
        assertThat(output).contains(">> REQUEST.PARAMETERS.PATH.personId")
        assertThat(output).contains(">> REQUEST.PARAMETERS.HEADER.id")
        assertThat(output).contains(">> REQUEST.PARAMETERS.QUERY.data")
        assertThat(output).contains(">> RESPONSE.HEADER.data")
        assertThat(output).contains(">> RESPONSE.BODY.location")
    }

    @Test
    fun `ability to set dynamic expectations using partial examples`() {
        val feature = OpenApiSpecification.fromYAML("""
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
                            - name
                          properties:
                            name:
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
                                type: number
                              name:
                                type: string
        """.trimIndent(), "").toFeature()

        HttpStub(feature).use { stub ->
            val partialExample = """
                {
                    "partial": {
                        "http-request": {
                            "method": "POST",
                            "path": "/person",
                            "body": {
                                "name": "(NAME:string)"
                            }
                        },
                        "http-response": {
                            "status": 200,
                            "body": {
                                "name": "$(NAME)"
                            }
                        }
                    }
                }
            """.trimIndent()

            val expectationRequest = HttpRequest("POST", path = "/_specmatic/expectations", body = parsedJSONObject(partialExample))
            stub.client.execute(expectationRequest).also { response ->
                assertThat(response.status).isEqualTo(200)
            }

            val response = stub.client.execute(
                HttpRequest(
                    "POST",
                    path = "/person",
                    body = parsedJSONObject("""{"name": "Juste"}""")
                )
            )

            assertThat(response.status).isEqualTo(200)
            val jsonResponseBody = response.body as JSONObjectValue
            assertThat(jsonResponseBody.jsonObject).containsKey("id")
            assertThat(jsonResponseBody.jsonObject).containsEntry("name", StringValue("Juste"))
        }
    }

    @Test
    fun `non-transient expectations should be honored in order of last-in first-out`() {
        val feature = OpenApiSpecification.fromYAML("""
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
                            - name
                            - department
                          properties:
                            name:
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
                                type: number
                              name:
                                type: string
        """.trimIndent(), "").toFeature()

        HttpStub(feature).use { stub ->
            val partialExample1 = """
                {
                    "partial": {
                        "http-request": {
                            "method": "POST",
                            "path": "/person",
                            "body": {
                                "name": "(NAME:string)"
                            }
                        },
                        "http-response": {
                            "status": 200,
                            "body": {
                                "name": "$(NAME)"
                            }
                        }
                    }
                }
            """.trimIndent()

            val partialExample2 = """
                {
                    "partial": {
                        "http-request": {
                            "method": "POST",
                            "path": "/person",
                            "body": {
                                "name": "John"
                            }
                        },
                        "http-response": {
                            "status": 200,
                            "body": {
                                "name": "Nocke"
                            }
                        }
                    }
                }
            """.trimIndent()

            stub.client.execute(
                HttpRequest(
                    "POST",
                    path = "/_specmatic/expectations",
                    body = parsedJSONObject(partialExample1)
                )
            ).also { response ->
                assertThat(response.status).isEqualTo(200)
            }

            stub.client.execute(
                HttpRequest(
                    "POST",
                    path = "/_specmatic/expectations",
                    body = parsedJSONObject(partialExample2)
                )
            ).also { response ->
                assertThat(response.status).isEqualTo(200)
            }

            stub.client.execute(
                HttpRequest(
                    "POST",
                    path = "/person",
                    body = parsedJSONObject("""{"name": "Juste"}""")
                )
            ).also { response ->
                assertThat(response.status).isEqualTo(200)
                val jsonResponseBody = response.body as JSONObjectValue
                assertThat(jsonResponseBody.jsonObject).containsKey("id")
                assertThat(jsonResponseBody.jsonObject).containsEntry("name", StringValue("Juste"))
            }

            stub.client.execute(
                HttpRequest(
                    "POST",
                    path = "/person",
                    body = parsedJSONObject("""{"name": "John"}""")
                )
            ).also { response ->
                assertThat(response.status).isEqualTo(200)
                val jsonResponseBody = response.body as JSONObjectValue
                assertThat(jsonResponseBody.jsonObject).containsKey("id")
                assertThat(jsonResponseBody.jsonObject).containsEntry("name", StringValue("Nocke"))
            }
        }
    }

    @Test
    fun `partial match of AnyPattern in response`() {
        createStubFromContracts(listOf("src/test/resources/openapi/substitutions/spec_with_oneOf_in_response.yaml"), timeoutMillis = 0).use { stub ->
            stub.client.execute(HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Jane"}"""))).also { response ->
                assertThat(response.status).isEqualTo(200)

                val jsonResponseBody = response.body as JSONObjectValue
                assertThat(jsonResponseBody.findFirstChildByPath("customername")?.toStringLiteral()).isEqualTo("Jane")
            }

            stub.client.execute(HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Joan"}"""))).also { response ->
                assertThat(response.status).isEqualTo(200)

                val jsonResponseBody = response.body as JSONObjectValue
                assertThat(jsonResponseBody.findFirstChildByPath("employeename")?.toStringLiteral()).isEqualTo("Joan")
            }
        }
    }

    @Test
    fun `partial example using dictionary populates mandatory key and header but not non-mandatory missing ones`() {
        try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, "src/test/resources/openapi/substitutions/dictionary.json")

            createStubFromContracts(
                listOf("src/test/resources/openapi/substitutions/partial_using_dictionary.yaml"),
                timeoutMillis = 0
            ).use { stub ->
                stub.client.execute(HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Jane"}""")))
                    .also { response ->
                        assertThat(response.status).isEqualTo(200)

                        assertThat(response.headers["X-Region"]).isEqualTo("Asia")
                        assertThat(response.headers).doesNotContainKey("X-Data-Token")

                        val jsonResponseBody = response.body as JSONObjectValue
                        assertThat(jsonResponseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(10))
                        assertThat(jsonResponseBody.jsonObject).doesNotContainKeys("name")
                    }
            }
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }

    @Test
    fun `partial example using dictionary populates missing mandatory key from the dictionary`() {
        try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, "src/test/resources/openapi/substitutions/dictionary.json")

            createStubFromContracts(
                listOf("src/test/resources/openapi/substitutions/partial_using_dictionary_testing_mandarory_key_generation.yaml"),
                timeoutMillis = 0
            ).use { stub ->
                stub.client.execute(HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Jane"}""")))
                    .also { response ->
                        assertThat(response.status).isEqualTo(200)

                        assertThat(response.headers["X-Region"]).isEqualTo("Asia")
                        assertThat(response.headers["X-Data-Token"]).isEqualTo("pqr")

                        val jsonResponseBody = response.body as JSONObjectValue
                        assertThat(jsonResponseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(10))
                        assertThat(jsonResponseBody.findFirstChildByPath("name")).isEqualTo(StringValue("George"))
                    }
            }
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }


    @Test
    fun `partial example using invalid dictionary should throw an error at runtime`() {
        try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, "src/test/resources/openapi/substitutions/dictionary.json")

            createStubFromContracts(
                listOf("src/test/resources/openapi/substitutions/partial_with_invalid_dictionary_value.yaml"),
                timeoutMillis = 0
            ).use { stub ->
                val response = stub.client.execute(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSONObject("""{"name": "Jason"}""")
                    )
                )

                assertThat(response.status).isEqualTo(400)
                assertThat(response.body.toStringLiteral()).contains(">> RESPONSE.BODY.id")
            }
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }

    @Test
    fun `partial example should favour concrete value over dictionary value`() {
        createStubFromContracts(listOf(("src/test/resources/openapi/substitutions/partial_with_dictionary_conflict.yaml")), timeoutMillis = 0).use { stub ->
            val request = HttpRequest(
                "POST",
                "/person",
                body = parsedJSONObject("""{"name": "Jodie"}""")
            )

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue
            assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(20))
        }
    }

    @Test
    fun `data substitution in response body at second level using dictionary`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/dictionary_value_at_second_level.yaml")

        try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, "src/test/resources/openapi/substitutions/dictionary.json")

            createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
                val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Janet"}"""))
                val response = stub.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                val responseBody = response.body as JSONObjectValue

                assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(10))
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
                val request = HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Janet"}"""))
                val response = stub.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                val responseBody = response.body as JSONObjectValue

                assertThat(responseBody.findFirstChildByPath("id")).isEqualTo(NumberValue(10))
                assertThat(responseBody.findFirstChildByPath("addresses.[0].street")).isEqualTo(StringValue("Baker Street"))
            }
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }

    @Test
    fun `more complicated data substitutions in response body with 3 level deep partial generation`() {
        val specWithSubstitution = osAgnosticPath("src/test/resources/openapi/substitutions/complicated_spec_with_dictionary_substitutions.yaml")

        try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, "src/test/resources/openapi/substitutions/complicated_dictionary.json")

            createStubFromContracts(listOf(specWithSubstitution), timeoutMillis = 0).use { stub ->
                stub.client.execute(
                    HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Charles"}"""))).let { response ->

                    assertThat(response.status).isEqualTo(200)
                    val responseBody = response.body as JSONObjectValue

                    assertThat(responseBody.findFirstChildByPath("person.name")).isEqualTo(StringValue("Jackie"))
                    assertThat(responseBody.findFirstChildByPath("person.addresses.[0].street")).isEqualTo(StringValue("Baker Street"))
                    assertThat(responseBody.findFirstChildByPath("person.addresses.[1].street")).isEqualTo(StringValue("Baker Street"))
                    assertThat(responseBody.findFirstChildByPath("person.past_companies.[0].name")).isEqualTo(StringValue("Acme Inc"))
                    assertThat(responseBody.findFirstChildByPath("person.past_companies.[1].name")).isEqualTo(StringValue("Acme Inc"))
                }

            }
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }
    }

    @Test
    fun `object to object recursions should be detected and should not overflow the stack`() {
        val specWithPartial = osAgnosticPath("src/test/resources/openapi/substitutions/recursion_object_to_object.yaml")

        createStubFromContracts(listOf(specWithPartial), timeoutMillis = 0).use { stub ->
            stub.client.execute(
                HttpRequest("POST", "/person", body = parsedJSONObject("""{"name": "Charles"}"""))).let { response ->

                assertThat(response.status).isEqualTo(400)
                assertThat(response.body.toStringLiteral()).contains("Invalid pattern cycle")
            }
        }
    }

    @Test
    fun `should be able to load 400 partial example without errors and respond back`() {
        val examplesDir = "src/test/resources/openapi/partial_example_tests/bad_request_valid_example"
        val apiSpecification = "src/test/resources/openapi/partial_example_tests/simple.yaml"

        createStubFromContracts(listOf(apiSpecification), listOf(examplesDir), timeoutMillis = 0).use { stub ->
            val httpRequest = HttpRequest(
                path = "/creators/abc/pets/def", method = "PATCH",
                body = parsedJSONObject("""{"creatorId": "JohnDoe", "petId": 123}""")
            )

            stub.client.execute(httpRequest).let { response -> 
                assertThat(response.status).isEqualTo(400)
                assertThat(response.body).isEqualTo(parsedJSONObject("""
                {"code": 400, "message": "Bad Request"}
                """.trimIndent()))
            }
        }
    }

    @Test
    fun `should result in failure when partial 400 example has invalid response`() {
        val examplesDir = "src/test/resources/openapi/partial_example_tests/bad_request_invalid_example"
        val apiSpecification ="src/test/resources/openapi/partial_example_tests/simple.yaml"

        val (output, _) = captureStandardOutput { createStubFromContracts(listOf(apiSpecification), listOf(examplesDir)).also { it.close() } }
        val exampleFile = osAgnosticPath("src/test/resources/openapi/partial_example_tests/bad_request_invalid_example/pets_post.json")

        print(output)
        assertThat(output).containsIgnoringWhitespaces("""
        Could not load partial example $exampleFile
        >> RESPONSE.BODY.code
        Expected number, actual was "400"
        >> RESPONSE.BODY.message
        Expected string, actual was number
        """.trimIndent())
    }
}