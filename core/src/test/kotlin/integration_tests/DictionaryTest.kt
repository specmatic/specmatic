package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import io.specmatic.stub.*
import io.specmatic.stub.createStubFromContracts
import io.specmatic.stub.httpRequestLog
import io.specmatic.stub.httpResponseLog
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DictionaryTest {
    @Test
    fun `should generate test values based on a dictionary found by convention in the same directory`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary/spec.yaml")
            .toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val jsonPayload = request.body as JSONObjectValue

                assertThat(jsonPayload.findFirstChildByPath("name")?.toStringLiteral()).isEqualTo("input123")

                return HttpResponse.ok(parsedJSONObject("""{"data": "success"}"""))
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `stubbed responses for request with no matching example should return dictionary values if available`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val response = stub.client.execute(HttpRequest("POST", "/data", body = parsedJSON("""{"name": "data"}""")))

            val jsonResponsePayload = response.body as JSONObjectValue

            assertThat(response.status).isEqualTo(200)

            assertThat(jsonResponsePayload.findFirstChildByPath("data")?.toStringLiteral()).isEqualTo("output123")
        }
    }

    @Test
    fun `tests should use dictionary to generate query params`() {
        val queryParamValueFromDictionary = "input123"

        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_and_query_params/spec.yaml")
            .toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.queryParams.asMap()["name"]).isEqualTo(queryParamValueFromDictionary)
                return HttpResponse.ok(parsedJSONObject("""{"data": "success"}""")).also {
                    println(httpRequestLog(request))
                    println(httpResponseLog(it))
                }
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `tests should use dictionary to generate request headers`() {
        val requestHeaderValueFromDictionary = "abc123"

        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_and_request_headers/spec.yaml")
            .toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers["X-LoginID"]).isEqualTo(requestHeaderValueFromDictionary)
                return HttpResponse.ok(parsedJSONObject("""{"data": "success"}""")).also {
                    println(httpRequestLog(request))
                    println(httpResponseLog(it))
                }
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `stub should return dictionary value if available for response header instead of random data`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_and_response_headers/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val response = stub.client.execute(HttpRequest("POST", "/data", body = parsedJSONObject("""{"name": "data"}""")))
            assertThat(response.status).isEqualTo(200)
            assertThat(response.headers["X-Trace-ID"]).isEqualTo("trace123")
        }
    }

    @Test
    fun `stub should return dictionary value at the second level in a payload`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_and_multilevel_response/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val request = HttpRequest("GET", "/person")
            println(request.toLogString())

            val response = stub.client.execute(request)

            println(response.toLogString())
            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue
            assertThat(json.findFirstChildByPath("name.salutation")?.toStringLiteral()).isEqualTo("Ms")
            assertThat(json.findFirstChildByPath("name.full_name")?.toStringLiteral()).isEqualTo("Lena Schwartz")
        }
    }

    @Test
    fun `stub should return dictionary value at the second level in a schema`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_and_multilevel_schema/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            assertThat(json.findFirstChildByPath("name.salutation")?.toStringLiteral()).isEqualTo("Ms")
            assertThat(json.findFirstChildByPath("name.details.first_name")?.toStringLiteral()).isEqualTo("Leanna")
            assertThat(json.findFirstChildByPath("name.details.last_name")?.toStringLiteral()).isEqualTo("Schwartz")
        }
    }

    @Test
    fun `stub should leverage dictionary object value at the second level in a schema`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_with_multilevel_schema_and_dictionary_object_value/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            assertThat(json.findFirstChildByPath("name.salutation")?.toStringLiteral()).isEqualTo("Ms")
            assertThat(json.findFirstChildByPath("name.details.first_name")?.toStringLiteral()).isEqualTo("Leanna")
            assertThat(json.findFirstChildByPath("name.details.last_name")?.toStringLiteral()).isEqualTo("Schwartz")
        }
    }

    @Test
    fun `stub should leverage dictionary array scalar value at the second level in a schema`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_with_multilevel_schema_and_dictionary_array_value/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            assertThat(json.findFirstChildByPath("details.addresses.[0]")?.toStringLiteral()).isEqualTo("22B Baker Street")
            assertThat(json.findFirstChildByPath("details.addresses.[1]")?.toStringLiteral()).isEqualTo("10A Horowitz Street")
        }
    }

    @Test
    fun `stub should leverage dictionary array object value at the second level in a schema`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_with_multilevel_schema_and_dictionary_array_objects/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            val addresses = json.findFirstChildByPath("details.addresses") as JSONArrayValue

            assertThat(addresses.list).allSatisfy {
                val jsonAddressObject = it as JSONObjectValue
                assertThat(jsonAddressObject.jsonObject["street"]?.toStringLiteral()).isEqualTo("22B Baker Street")
            }
        }
    }

    @Test
    fun `stub should leverage dictionary array object value at the second level in a schema in an example`() {
        val specSourceDir = "spec_with_dictionary_with_multilevel_schema_and_dictionary_array_objects_with_example"
        createStubFromContracts(
            listOf("src/test/resources/openapi/$specSourceDir/spec.yaml"),
            listOf("src/test/resources/openapi/$specSourceDir/spec_examples"),
            timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            val addresses = json.findFirstChildByPath("details.addresses") as JSONArrayValue

            assertThat(addresses.list).allSatisfy {
                val jsonAddressObject = it as JSONObjectValue
                assertThat(jsonAddressObject.jsonObject["street"]?.toStringLiteral()).isEqualTo("22B Baker Street")
            }
        }
    }

    @Test
    fun `stub should leverage dictionary object value at the second level given allOf in a schema in an example`() {
        val specSourceDir = "spec_with_dictionary_with_multilevel_schema_and_dictionary_objects_with_allOf_and_example"

        createStubFromContracts(
            listOf("src/test/resources/openapi/$specSourceDir/spec.yaml"),
            listOf("src/test/resources/openapi/$specSourceDir/spec_examples"),
            timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            assertThat(json.findFirstChildByPath("name")).isNotNull()
            assertThat(json.findFirstChildByPath("address")?.toStringLiteral()).isEqualTo("22B Baker Street")
        }
    }

    @Test
    fun `stub should leverage dictionary object value at the second level given oneOf in a schema in an example`() {
        val specSourceDir = "spec_with_dictionary_with_multilevel_schema_and_dictionary_objects_with_oneOf_and_example"
        createStubFromContracts(
            listOf("src/test/resources/openapi/$specSourceDir/spec.yaml"),
            listOf("src/test/resources/openapi/$specSourceDir/spec_examples"),
            timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            assertThat(json.findFirstChildByPath("full_name")?.toStringLiteral()).isEqualTo("Jack Sprat")
        }
    }

    @Test
    fun `stub should leverage dictionary object value at the second level given oneOf in allOf in a schema in an example`() {
        val specSourceDir = "spec_with_dictionary_with_multilevel_schema_and_dictionary_objects_with_oneOf_in_allOf_and_example"

        createStubFromContracts(
            listOf("src/test/resources/openapi/$specSourceDir/spec.yaml"),
            listOf("src/test/resources/openapi/$specSourceDir/spec_examples"),
            timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person")

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)

            val json = response.body as JSONObjectValue

            assertThat(json.findFirstChildByPath("full_name")?.toStringLiteral()).isEqualTo("Jack Sprat")
        }
    }

    @Test
    fun `generative tests with a dictionary work as usual`() {
        val parentPath = "src/test/resources/openapi/simple_spec_with_dictionary"

        val openApiFilePath = "${parentPath}/spec.yaml"
        val dictionaryPath = "${parentPath}/dictionary.json"

        println("Tests WITHOUT the dictionary")

        val testCountWithoutDictionary = OpenApiSpecification
    .fromFile(openApiFilePath)
    .toFeature()
    .enableGenerativeTesting().let { feature ->
        feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.ok("success").also {
                    println(request.toLogString())
                    println()
                    println(it.toLogString())

                    println()
                    println()
                }
            }
        })
    }.testCount

        println("Tests WITH the dictionary")

        val testCountWithDictionary = try {
            System.setProperty(SPECMATIC_STUB_DICTIONARY, dictionaryPath)

            OpenApiSpecification
        .fromFile(openApiFilePath)
        .toFeature()
        .enableGenerativeTesting().let { feature ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse.ok("success").also {
                        println(request.toLogString())
                        println()
                        println(it.toLogString())

                        println()
                        println()
                    }
                }
            })
        }.testCount
        } finally {
            System.clearProperty(SPECMATIC_STUB_DICTIONARY)
        }

        assertThat(testCountWithDictionary).isEqualTo(testCountWithoutDictionary)
    }

    @Nested
    inner class NegativeBasedOnTests {

        @Test
        fun `negative based path parameters should still be generated when dictionary contains substitutions`() {
            val dictionary = mapOf("PATH-PARAMS.id" to NumberValue(123))
            val scenario = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/orders/(id:number)"), method = "GET"),
                httpResponsePattern = HttpResponsePattern(status = 200)
            )).copy(dictionary = dictionary)
            val feature = Feature(listOf(scenario), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse.OK.also {
                        val logs = listOf(request.toLogString(), it.toLogString())
                        println(logs.joinToString(separator = "\n", postfix = "\n\n"))

                        assertThat(request.path).satisfiesAnyOf(
                            { path -> assertThat(path).isEqualTo("/orders/123") },
                            { path -> assertThat(path).matches("/orders/(false|true)") },
                            { path -> assertThat(path).matches("/orders/[a-zA-Z]+") },
                        )
                    }
                }
            })

            assertThat(result.results).hasSize(3)
        }

        @Test
        fun `negative based query parameters should still be generated when dictionary contains substitutions`() {
            val dictionary = mapOf("QUERY-PARAMS.id" to NumberValue(123))
            val scenario = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = buildHttpPathPattern("/orders"), method = "GET",
                    httpQueryParamPattern = HttpQueryParamPattern(mapOf("id" to QueryParameterScalarPattern(NumberPattern())))
                ),
                httpResponsePattern = HttpResponsePattern(status = 200)
            )).copy(dictionary = dictionary)
            val feature = Feature(listOf(scenario), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse.OK.also {
                        val logs = listOf(request.toLogString(), it.toLogString())
                        println(logs.joinToString(separator = "\n", postfix = "\n\n"))

                        assertThat(request.queryParams.keys).contains("id")
                        assertThat(request.queryParams.getOrElse("id") { throw AssertionError("Query param id not found") }).satisfiesAnyOf(
                            { id -> assertThat(id).isEqualTo("123") },
                            { id -> assertThat(id).matches("(false|true)") },
                            { id -> assertThat(id).matches("[a-zA-Z]+") },
                            { id -> assertThat(id).isEmpty() }
                        )
                    }
                }
            })

            assertThat(result.results).hasSize(4)
        }

        @Test
        fun `negative based headers should still be generated when dictionary contains substitutions`() {
            val dictionary = mapOf("HEADERS.ID" to NumberValue(123))
            val scenario = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = buildHttpPathPattern("/orders"), method = "GET",
                    headersPattern = HttpHeadersPattern(mapOf("ID" to NumberPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(status = 200)
            )).copy(dictionary = dictionary)
            val feature = Feature(listOf(scenario), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse.OK.also {
                        val logs = listOf(request.toLogString(), it.toLogString())
                        println(logs.joinToString(separator = "\n", postfix = "\n\n"))

                        assertThat(request.headers).containsKey("ID")
                        assertThat(request.headers["ID"]).satisfiesAnyOf(
                            { id -> assertThat(id).isEqualTo("123") },
                            { id -> assertThat(id).matches("(false|true)") },
                            { id -> assertThat(id).matches("[a-zA-Z]+") },
                            { id -> assertThat(id).isEmpty() }
                        )
                    }
                }
            })

            assertThat(result.results).hasSize(4)
        }

        @Test
        fun `negative based bodies should still be generated when dictionary contains substitutions`() {
            val dictionary = mapOf("OBJECT.id" to NumberValue(123))
            val scenario = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = buildHttpPathPattern("/orders"), method = "GET",
                    body = JSONObjectPattern(mapOf(
                        "id" to NumberPattern()
                    ), typeAlias = "(OBJECT)")
                ),
                httpResponsePattern = HttpResponsePattern(status = 200)
            )).copy(dictionary = dictionary)
            val feature = Feature(listOf(scenario), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse.OK.also {
                        val logs = listOf(request.toLogString(), it.toLogString())
                        println(logs.joinToString(separator = "\n", postfix = "\n\n"))

                        assertThat(request.body).isInstanceOf(JSONObjectValue::class.java)
                        assertThat((request.body as JSONObjectValue).findFirstChildByName("id")).satisfiesAnyOf(
                            { id -> assertThat(id).isEqualTo(NumberValue(123)) },
                            { id -> assertThat(id).isInstanceOf(StringValue::class.java) },
                            { id -> assertThat(id).isInstanceOf(BooleanValue::class.java) },
                            { id -> assertThat(id).isInstanceOf(NullValue::class.java) }
                        )
                    }
                }
            })

            assertThat(result.results).hasSize(4)
        }
    }

    @Test
    fun `basedOn bodies should use dictionary values when applicable`() {
        val dictionary = mapOf("OBJECT.id" to NumberValue(123), "OBJECT.name" to StringValue("test"))
        val scenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "GET",
                body = JSONObjectPattern(mapOf(
                    "id" to NumberPattern(),
                    "name" to StringPattern()
                ), typeAlias = "(OBJECT)")
            ),
            httpResponsePattern = HttpResponsePattern(status = 200)
        )).copy(dictionary = dictionary)
        val feature = Feature(listOf(scenario), name = "")


        feature.enableGenerativeTesting().executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val isNegative = request.headers[SPECMATIC_RESPONSE_CODE_HEADER] != "200"
                val requestBody = request.body as JSONObjectValue
                val idValue = requestBody.findFirstChildByName("id")
                val nameValue = requestBody.findFirstChildByName("name")

                return HttpResponse.OK.also {
                    val logs = listOf(request.toLogString(), it.toLogString())
                    println(logs.joinToString(separator = "\n", postfix = "\n\n"))

                    if (isNegative) {
                        assertThat(requestBody).satisfiesAnyOf(
                            { assertThat(idValue).isEqualTo(NumberValue(123)) },
                            { assertThat(nameValue).isEqualTo(StringValue("test")) }
                        )
                    } else {
                        assertThat(idValue).isEqualTo(NumberValue(123))
                        assertThat(nameValue).isEqualTo(StringValue("test"))
                    }
                }
            }
        })
    }
}