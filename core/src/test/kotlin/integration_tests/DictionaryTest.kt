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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

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
            System.setProperty(SPECMATIC_DICTIONARY, dictionaryPath)

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
            System.clearProperty(SPECMATIC_DICTIONARY)
        }

        assertThat(testCountWithDictionary).isEqualTo(testCountWithoutDictionary)
    }

    @Test
    fun `should not re-use top-level keys for deep nested patterns with same key`() {
        val pattern = parsedPattern("""{
        "name": "(string)",
        "details": {
            "name": "(string)"
        }
        }""".trimIndent(), typeAlias = "(Test)")
        val dictionary = """
        '*':
          name: John Doe
        Test:
          name: John Doe
        """.trimIndent().let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary)
        val generatedValue = pattern.generate(resolver) as JSONObjectValue
        val details = generatedValue.jsonObject["details"] as JSONObjectValue

        assertThat(generatedValue.jsonObject["name"]?.toStringLiteral()).isEqualTo("John Doe")
        assertThat(details.jsonObject["name"]?.toStringLiteral()).isNotEqualTo("John Doe")
        assertThat(details.jsonObject["name"]?.toStringLiteral()).isNotEqualTo("Jane Doe")
    }

    @Test
    fun `should fill-in partial values in an array when picking values from dictionary`() {
        val pattern = JSONObjectPattern(mapOf(
            "details" to ListPattern(JSONObjectPattern(mapOf(
                "name" to StringPattern(), "email" to EmailPattern())
            ))
        ), typeAlias = "(Test)")
        val dictionary = parsedJSONObject("""{
        "Test": {
            "details": [
                [{"name": "John Doe"}],
                [{"name": "Jane Doe", "email": "JaneDoe@mail.com"}]
            ]
        }
        }""".trimIndent()).jsonObject.let(Dictionary::from)
        val resolver = Resolver(dictionary = dictionary).partializeKeyCheck()
        val partialValue = parsedJSONObject("""{
        "details": [
            "(anyvalue)",
            { "name": "(string)" },
            { "name": "(string)", "email": "(email)" }
        ]
        }""".trimIndent())
        val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value as JSONObjectValue
        val details = filledInValue.jsonObject["details"] as JSONArrayValue

        assertThat(details.list).allSatisfy { detail ->
            assertThat(detail).isInstanceOf(JSONObjectValue::class.java); detail as JSONObjectValue
            assertThat(detail).satisfiesAnyOf(
                {
                    assertThat(it.jsonObject["name"]?.toStringLiteral()).isEqualTo("John Doe")
                    assertThat(it.jsonObject["email"]?.toStringLiteral()).isNotEqualTo("JaneDoe@mail.com")
                },
                {
                    assertThat(it.jsonObject["name"]?.toStringLiteral()).isEqualTo("Jane Doe")
                    assertThat(it.jsonObject["email"]?.toStringLiteral()).isEqualTo("JaneDoe@mail.com")
                }
            )
        }
    }

    @Test
    fun `should fill-in partial values in an scalar array when picking values from dictionary`() {
        val pattern = JSONObjectPattern(mapOf("numbers" to ListPattern(NumberPattern())), typeAlias = "(Test)")
        val dictionary = parsedJSONObject("""{
        "Test": { "numbers": [ [123], [456] ] } }
        """.trimIndent()).jsonObject.let(Dictionary::from)
        val resolver = Resolver(dictionary = dictionary).partializeKeyCheck()
        val partialValue = parsedJSONObject("""{
        "numbers": [
            "(anyvalue)",
            "(number)"
        ]
        }""".trimIndent())
        val filledInValue = pattern.fillInTheBlanks(partialValue, resolver).value as JSONObjectValue
        val numbers = filledInValue.jsonObject["numbers"] as JSONArrayValue

        println(filledInValue)
        assertThat(numbers.list).allSatisfy { numberValue ->
            assertThat((numberValue as NumberValue).nativeValue).isIn(123, 456)
        }
    }

    @Test
    fun `should remove extra-keys which are spec-valid but not valid as per newBasedOn pattern`() {
        val dictionary = """
        Schema:
            arrayOfObjects:
            - - mandatory: value
                optional: value
        """.trimIndent().let(Dictionary::fromYaml)
        val pattern = JSONObjectPattern(mapOf("arrayOfObjects" to ListPattern(
            JSONObjectPattern(mapOf("mandatory" to StringPattern(), "optional?" to StringPattern()))
        )), typeAlias = "(Schema)")
        val resolver = Resolver(dictionary = dictionary)
        val newBasedPatterns = pattern.newBasedOn(Row(), resolver).toList()

        assertThat(newBasedPatterns).allSatisfy { basedPattern ->
            val generated = basedPattern.value.generate(resolver) as JSONObjectValue
            val array = generated.jsonObject["arrayOfObjects"] as JSONArrayValue

            assertThat(array.list).allSatisfy { item ->
                val obj = item as JSONObjectValue
                val mandatory = obj.jsonObject["mandatory"]?.toStringLiteral()
                val optional = obj.jsonObject["optional"]?.toStringLiteral()

                assertThat(mandatory).isEqualTo("value")
                if ("optional" in obj.jsonObject) {
                    assertThat(optional).isEqualTo("value")
                }
            }
        }
    }

    @Nested
    inner class NegativeBasedOnTests {

        @Test
        fun `negative based path parameters should still be generated when dictionary contains substitutions`() {
            val dictionary = "PARAMETERS: { PATH: { id: 123 } }".let(Dictionary::fromYaml)
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
            assertThat(result.successCount).isEqualTo(1)
        }

        @Test
        fun `negative based query parameters should still be generated when dictionary contains substitutions`() {
            val dictionary = "PARAMETERS: { QUERY: { id: 123 } }".let(Dictionary::fromYaml)
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
            assertThat(result.successCount).isEqualTo(1)
        }

        @Test
        fun `negative based headers should still be generated when dictionary contains substitutions`() {
            val dictionary = "PARAMETERS: { HEADER: { ID: 123 } }".let(Dictionary::fromYaml)
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
            assertThat(result.successCount).isEqualTo(1)
        }

        @Test
        fun `negative based bodies should still be generated when dictionary contains substitutions`() {
            val dictionary = mapOf("OBJECT.id" to NumberValue(123)).let(Dictionary::from)
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
        )).copy(dictionary = dictionary.let(Dictionary::from))
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

    @Nested
    inner class MultiValueDictionaryTests {

        @Test
        fun `should randomly pick one of the dictionary values when generating`() {
            val dictionary = "Schema: { number: [10, 20, 30], string: [a, b, c] } ".let(Dictionary::fromYaml)
            val pattern = parsedPattern("""{
                "number": "(number)",
                "string": "(string)"
            }""".trimIndent(), typeAlias = "(Schema)")
            val resolver = Resolver(dictionary = dictionary)
            val value = pattern.generate(resolver) as JSONObjectValue

            assertThat(value.jsonObject["number"]).isIn(listOf(10, 20, 30).map(::NumberValue))
            assertThat(value.jsonObject["string"]).isIn(listOf("a", "b", "c").map(::StringValue))
        }

        @Test
        fun `should use the array value as is when pattern is an array and dictionary contains array level key`() {
            val dictionary = "Schema: { array: [10, 20, 30] }".let(Dictionary::fromYaml)
            val pattern = JSONObjectPattern(mapOf("array" to ListPattern(NumberPattern())), typeAlias = "(Schema)")
            val resolver = Resolver(dictionary = dictionary)
            val value = pattern.generate(resolver)

            assertThat(value.jsonObject["array"]).isInstanceOf(JSONArrayValue::class.java)
            assertThat((value.jsonObject["array"])).isEqualTo(listOf(10, 20, 30).map(::NumberValue).let(::JSONArrayValue))
        }

        @Test
        fun `should throw an exception when array key contains invalid value and pattern is an array`() {
            val dictionary = "Schema: { array: [1, abc, 3] }".let(Dictionary::fromYaml)
            val pattern = JSONObjectPattern(mapOf("array" to ListPattern(NumberPattern())), typeAlias = "(Schema)")
            val resolver = Resolver(dictionary = dictionary)
            val exception = assertThrows<ContractException> { pattern.generate(resolver) }

            assertThat(exception.report()).isEqualToNormalizingWhitespace("""
            >> array[1]
            Invalid Dictionary value at "Schema.array"
            Expected number, actual was "abc"
            """.trimIndent())
        }

        @Test
        fun `should look for default dictionary values when schema key is missing`() {
            val dictionary = """
            (number): [1, 2, 3]
            (string): [a, b, c]
            """.let(Dictionary::fromYaml)
            val pattern = parsedPattern("""{
            "numberKey": "(number)",
            "stringKey": "(string)"
            }""".trimIndent(), typeAlias = "(Schema)")
            val resolver = Resolver(dictionary = dictionary)
            val value = pattern.generate(resolver) as JSONObjectValue

            assertThat(value.jsonObject["numberKey"]).isIn(listOf(1, 2, 3).map(::NumberValue))
            assertThat(value.jsonObject["stringKey"]).isIn(listOf("a", "b", "c").map(::StringValue))
        }
        
        @Test
        fun `should pick up default value for complex pattern if exists in dictionary`() {
            val dictionary = """
            (list of number): [1, 2, 3]
            (list of email): [john@mail.com, jane@mail.com, bob@mail.com]
            """.let(Dictionary::fromYaml)
            val pattern = JSONObjectPattern(mapOf(
                "numbers" to ListPattern(NumberPattern()),
                "emails" to ListPattern(EmailPattern())
            ), typeAlias = "(Schema)")
            val resolver = Resolver(dictionary = dictionary)
            val value = pattern.generate(resolver)

            assertThat(value.jsonObject["numbers"]).isInstanceOf(JSONArrayValue::class.java)
            assertThat((value.jsonObject["numbers"] as JSONArrayValue).list).isEqualTo(
                listOf(1, 2, 3).map(::NumberValue)
            )

            assertThat(value.jsonObject["emails"]).isInstanceOf(JSONArrayValue::class.java)
            assertThat((value.jsonObject["emails"] as JSONArrayValue).list).isEqualTo(
                listOf("john@mail.com", "jane@mail.com", "bob@mail.com").map(::StringValue)
            )
        }

        @Test
        fun `should prioritise schema keys over default values in dictionary`() {
            val dictionary = """
            (number): [1, 2, 3]
            Schema: { number: [10, 20, 30] }
            """.let(Dictionary::fromYaml)
            val pattern = parsedPattern("""{ "number": "(number)" }""".trimIndent(), typeAlias = "(Schema)")
            val resolver = Resolver(dictionary = dictionary)
            val value = pattern.generate(resolver) as JSONObjectValue

            assertThat(value.jsonObject["number"]).isIn(listOf(10, 20, 30).map(::NumberValue))
        }

        @Nested
        inner class ListPatternTests {

            @ParameterizedTest
            @MethodSource("integration_tests.DictionaryTest#listPatternToSingleValueProvider")
            fun `should use the dictionary value as is when when pattern and value depth matches`(pattern: ListPattern, value: JSONArrayValue) {
                val testPattern = JSONObjectPattern(mapOf("test" to pattern), typeAlias = "(Test)")
                val resolver = Resolver(dictionary = "Test: { test: $value }".let(Dictionary::fromYaml))
                val generatedValue = resolver.generate(testPattern)

                assertThat(generatedValue).isInstanceOf(JSONObjectValue::class.java); generatedValue as JSONObjectValue
                assertThat(generatedValue.jsonObject["test"]).isEqualTo(value)
            }

            @ParameterizedTest
            @MethodSource("integration_tests.DictionaryTest#listPatternToMultiValueProvider")
            fun `should pick random value from the dictionary when value depth is higher than pattern`(pattern: ListPattern, value: JSONArrayValue) {
                val testPattern = JSONObjectPattern(mapOf("test" to pattern), typeAlias = "(Test)")
                val resolver = Resolver(dictionary = "Test: { test: $value }".let(Dictionary::fromYaml))
                val generatedValue = resolver.generate(testPattern)

                assertThat(generatedValue).isInstanceOf(JSONObjectValue::class.java); generatedValue as JSONObjectValue
                assertThat(generatedValue.jsonObject["test"]).isIn(value.list)
            }
        }
    }

    companion object {
        @JvmStatic
        fun listPatternToSingleValueProvider(): Stream<Arguments> {
            return Stream.of(
                // List[Pattern]
                Arguments.of(
                    listPatternOf(NumberPattern()), parsedJSONArray("""[1, 2]""")
                ),
                Arguments.of(
                    listPatternOf(NumberPattern()), parsedJSONArray("""[]""")
                ),
                // List[List[Pattern]]
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 1), parsedJSONArray("""[[1, 2], [3, 4]]""")
                ),
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 1), parsedJSONArray("""[[], [3, 4]]""")
                ),
                // List[List[List[Pattern]]]
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 2), parsedJSONArray("""[[[1, 2]], [[3, 4]]]""")
                ),
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 2), parsedJSONArray("""[[[]], [[3, 4]]]""")
                )
            )
        }

        @JvmStatic
        fun listPatternToMultiValueProvider(): Stream<Arguments> {
            return Stream.of(
                // List[Pattern]
                Arguments.of(
                    listPatternOf(NumberPattern()), parsedJSONArray("""[[1, 2], [3, 4]]""")
                ),
                Arguments.of(
                    listPatternOf(NumberPattern()), parsedJSONArray("""[[], [3, 4]]""")
                ),
                // List[List[Pattern]]
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 1), parsedJSONArray("""[[[1, 2]], [[3, 4]]]""")
                ),
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 1), parsedJSONArray("""[[[1, 2]], [[]]]""")
                ),
                // List[List[List[Pattern]]]
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 2),
                    parsedJSONArray("""[[[[1, 2]], [[3, 4]]], [[[5, 6]], [[7, 8]]]]""")
                ),
                Arguments.of(
                    listPatternOf(NumberPattern(), nestedLevel = 2),
                    parsedJSONArray("""[[[[1, 2]], [[3, 4]]], [[[]], [[7, 8]]]]""")
                )
            )
        }

        private fun listPatternOf(pattern: Pattern, nestedLevel: Int = 0): ListPattern {
            return ListPattern((1..nestedLevel).fold(pattern) { acc, _ -> ListPattern(acc) })
        }
    }
}