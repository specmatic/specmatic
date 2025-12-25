package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Dictionary
import io.specmatic.core.DictionaryMismatchMessages
import io.specmatic.core.Feature
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpQueryParamPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Resolver
import io.specmatic.core.SPECMATIC_STUB_DICTIONARY
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.withLogger
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.BooleanPattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.EmailPattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedPattern
import io.specmatic.core.utilities.toValue
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.StandardRuleViolation
import io.specmatic.toViolationReportString
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.stub.captureStandardOutput
import io.specmatic.stub.createStubFromContracts
import io.specmatic.stub.httpRequestLog
import io.specmatic.stub.httpResponseLog
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
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

    @Test
    fun `should generate test values based on a dictionary where an empty object is assigned to a top level key`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_with_nested_values/spec.yaml")
            .toFeature()
        val scenario = feature.scenarios.first()

        val request = scenario.generateHttpRequest()

        assertThat(request.body).isInstanceOf(JSONObjectValue::class.java)
        val body = request.body as JSONObjectValue
        val address = body.findFirstChildByPath("config.address") as JSONObjectValue
        assertThat(address.jsonObject).isEmpty()
    }

    @Test
    fun `stubbed responses for request with dictionary values where the dictionary has an empty object assigned to a top level key, should return correct nested response`() {
        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/spec_with_dictionary_with_nested_values/spec.yaml")
            .toFeature()

        HttpStub(feature).use { stub ->
            val response = stub.client.execute(
                HttpRequest(
                    "POST", "/data", body = parsedJSON(
                        """{"name": "name", "config": {"name": "name", "address": {}}}""".trimIndent()
                    )
                )
            )
            val jsonResponsePayload = response.body as JSONObjectValue
            assertThat(jsonResponsePayload.findFirstChildByPath("data.id")?.toStringLiteral()).isEqualTo("123")
            assertThat(jsonResponsePayload.findFirstChildByPath("data.config.name")?.toStringLiteral()).isEqualTo("name")
            val address = jsonResponsePayload.findFirstChildByPath("data.config.address")
            assertThat(address).isInstanceOf(JSONObjectValue::class.java)
            address as JSONObjectValue
            assertThat(address.jsonObject).isEmpty()
        }
    }

    @Test
    fun `if subKey has typeAlias should focus into subKey schema in dictionary instead of staying on pattern schema`() {
        val dictionary = """
        '*':
           error: Invalid-Request-Try-Again
        ErrorSchema:
            status: 400
        """.trimIndent().let(Dictionary::fromYaml)
        val pattern = JSONObjectPattern(mapOf("error" to DeferredPattern("(ErrorSchema)")))
        val errorPattern = JSONObjectPattern(mapOf("status" to NumberPattern()))

        val resolver = Resolver(dictionary = dictionary, newPatterns = mapOf("(ErrorSchema)" to errorPattern))
        val generatedValue = pattern.generate(resolver)
        val errorValue = generatedValue.jsonObject.getValue("error")

        assertThat(errorValue).isInstanceOf(JSONObjectValue::class.java); errorValue as JSONObjectValue
        assertThat(errorValue.jsonObject.getValue("status")).isEqualTo(NumberValue(400))
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
        fun `should throw an exception when array key contains invalid value and pattern is an array with strict mode`() {
            val dictionary = "Schema: { array: [1, abc, 3] }".let(Dictionary::fromYaml)
            val pattern = JSONObjectPattern(mapOf("array" to ListPattern(NumberPattern())), typeAlias = "(Schema)")
            val resolver = Resolver(dictionary = dictionary.copy(strictMode = true))
            val exception = assertThrows<ContractException> { pattern.generate(resolver) }

            assertThat(exception.report()).isEqualToNormalizingWhitespace("""
            >> array[1]
            Invalid Dictionary value at "Schema.array"
            ${DictionaryMismatchMessages.typeMismatch("number", "\"abc\"", "string")}
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

    @Nested
    inner class NegativeBasedOnTests {
        @Test
        fun `negative based path parameters should still be generated when dictionary contains substitutions`() {
            val dictionary = "PARAMETERS: { PATH: { id: 123 } }".let(Dictionary::fromYaml)
            val scenario = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/orders/(id:number)"), method = "GET"),
                httpResponsePattern = HttpResponsePattern(status = 200)
            )).copy(dictionary = dictionary)
            val feature = Feature(scenario.withBadRequest(), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val isPositiveTest = request.getHeader(SPECMATIC_RESPONSE_CODE_HEADER) == "200"
                    return if (isPositiveTest) {
                        assertThat(request.path).satisfiesAnyOf(
                            { path -> assertThat(path).isEqualTo("/orders/123") },
                            { path -> assertThat(path).isEqualTo("/orders/${Int.MAX_VALUE}") },
                            { path -> assertThat(path).isEqualTo("/orders/${Int.MIN_VALUE}") },
                        )
                        HttpResponse.OK
                    } else {
                        assertThat(request.path).satisfiesAnyOf(
                            { path -> assertThat(path).matches("/orders/(false|true)") },
                            { path -> assertThat(path).matches("/orders/[a-zA-Z]+") },
                        )
                        HttpResponse.ERROR_400
                    }
                }
            })

            assertThat(result.results).hasSize(3)
            assertThat(result.successCount).withFailMessage(result.report()).isEqualTo(3)
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
            val feature = Feature(scenario.withBadRequest(), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    if (!request.queryParams.containsKey("id")) return HttpResponse.ERROR_400
                    val isPositiveTest = request.getHeader(SPECMATIC_RESPONSE_CODE_HEADER) == "200"
                    val queryParamId = request.queryParams.getOrElse("id") { throw IllegalStateException() }
                    return if (isPositiveTest) {
                        assertThat(queryParamId).satisfiesAnyOf(
                            { paramId -> assertThat(paramId).isEqualTo("123") },
                            { paramId -> assertThat(paramId).isEqualTo("${Int.MAX_VALUE}") },
                            { paramId -> assertThat(paramId).isEqualTo("${Int.MIN_VALUE}") },
                        )
                        HttpResponse.OK
                    } else {
                        assertThat(queryParamId).satisfiesAnyOf(
                            { paramId -> assertThat(paramId).matches("(false|true)") },
                            { paramId -> assertThat(paramId).matches("[a-zA-Z]+") },
                        )
                        HttpResponse.ERROR_400
                    }
                }
            })

            assertThat(result.results).hasSize(4)
            assertThat(result.successCount).withFailMessage(result.report()).isEqualTo(4)
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
            val feature = Feature(scenario.withBadRequest(), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val isPositiveTest = request.getHeader(SPECMATIC_RESPONSE_CODE_HEADER) == "200"
                    val headerParamId = request.headers["ID"] ?: return HttpResponse.ERROR_400
                    return if (isPositiveTest) {
                        assertThat(headerParamId).satisfiesAnyOf(
                            { paramId -> assertThat(paramId).isEqualTo("123") },
                            { paramId -> assertThat(paramId).isEqualTo("${Int.MAX_VALUE}") },
                            { paramId -> assertThat(paramId).isEqualTo("${Int.MIN_VALUE}") },
                        )
                        HttpResponse.OK
                    } else {
                        assertThat(headerParamId).satisfiesAnyOf(
                            { paramId -> assertThat(paramId).matches("(false|true)") },
                            { paramId -> assertThat(paramId).matches("[a-zA-Z]+") },
                        )
                        HttpResponse.ERROR_400
                    }
                }
            })

            assertThat(result.results).hasSize(4)
            assertThat(result.successCount).withFailMessage(result.report()).isEqualTo(4)
        }

        @Test
        fun `negative based bodies should still be generated when dictionary contains substitutions`() {
            val dictionary = Dictionary.fromYaml("{ OBJECT: { id: 123 } }")
            val scenario = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = buildHttpPathPattern("/orders"), method = "GET",
                    body = JSONObjectPattern(mapOf(
                        "id" to NumberPattern()
                    ), typeAlias = "(OBJECT)")
                ),
                httpResponsePattern = HttpResponsePattern(status = 200)
            )).copy(dictionary = dictionary)
            val feature = Feature(scenario.withBadRequest(), name = "")

            val result = feature.enableGenerativeTesting().executeTests(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val isPositiveTest = request.getHeader(SPECMATIC_RESPONSE_CODE_HEADER) == "200"
                    return if (isPositiveTest) {
                        assertThat((request.body as JSONObjectValue).findFirstChildByName("id") as NumberValue).satisfiesAnyOf(
                            { id -> assertThat(id.nativeValue.toInt()).isEqualTo(123) },
                            { id -> assertThat(id.nativeValue.toInt()).isEqualTo(Int.MAX_VALUE) },
                            { id -> assertThat(id.nativeValue.toInt()).isEqualTo(Int.MIN_VALUE) }
                        )
                        HttpResponse.OK
                    } else {
                        assertThat((request.body as JSONObjectValue).findFirstChildByName("id")).satisfiesAnyOf(
                            { id -> assertThat(id).isInstanceOf(StringValue::class.java) },
                            { id -> assertThat(id).isInstanceOf(BooleanValue::class.java) },
                            { id -> assertThat(id).isInstanceOf(NullValue::class.java) }
                        )
                        HttpResponse.ERROR_400
                    }
                }
            })

            assertThat(result.results).hasSize(4)
            assertThat(result.successCount).withFailMessage(result.report()).isEqualTo(4)
        }

        private fun Scenario.withBadRequest(): List<Scenario> {
            return buildList {
                add(this@withBadRequest)
                add(this@withBadRequest.copy(httpResponsePattern = this@withBadRequest.httpResponsePattern.copy(status = 400, body = StringPattern())))
            }
        }
    }

    @Nested
    inner class ConstantSupportTests {
        @Test
        fun `should replace placeholders in dictionary with the constant values`() {
            val file = File("src/test/resources/dictionary/dictionary_with_constants.json")
            val dictionary = Dictionary.from(file)

            // Assert getOrder
            val expectedGetOrder = parsedJSONObject("""{ "orderId": 1234 }""")
            assertEquals(expectedGetOrder, dictionary.getRawValue("getOrder"))

            // Assert getOrderWithFilteredProduct
            val expectedGetOrderWithFilteredProduct = parsedJSONObject("""{
                "orderId": 1234,
                "filter": {
                    "product": {
                        "name": "iPhone",
                        "category": "gadget"
                    },
                    "quantity": 100
                }
            }""")
            assertEquals(expectedGetOrderWithFilteredProduct, dictionary.getRawValue("getOrderWithFilteredProduct"))

            // Assert postOrder
            val expectedPostOrder = parsedJSONObject("""{
                "quantity": 100,
                "product": {
                    "name": "Volleyball",
                    "category": "sports"
                }
            }""")
            assertEquals(expectedPostOrder, dictionary.getRawValue("postOrder"))

            // Assert ordersResponse
            val expectedOrdersResponse = parsedJSONObject("""{
                "orders": [
                    { "orderId": 1234 },
                    { "orderId": 4567 }
                ],
                "id": 123
            }""")
            assertEquals(expectedOrdersResponse, dictionary.getRawValue("ordersResponse"))
        }
    }

    @Nested
    inner class ConflictingValuesTest {
        @Test
        fun `should pick randomly matching value when there are conflicts in the dictionary path causing multiple types to exist`() {
            val dictionary = "'*': { commonKey: [10, Twenty, specmatic@test.io, false] } ".let(Dictionary::fromYaml)
            val patterns = listOf(
                NumberPattern() to 10,
                EmailPattern() to "specmatic@test.io",
                BooleanPattern() to false
            ).map { it.first to toValue(it.second) }

            assertThat(patterns).allSatisfy { (pattern, expectedValue) ->
                val pattern = JSONObjectPattern(mapOf("commonKey" to pattern))
                val resolver = Resolver(dictionary = dictionary)
                val value = pattern.generate(resolver).jsonObject.getValue("commonKey")
                assertThat(value).isEqualTo(expectedValue)
            }
        }

        @Test
        fun `should pick randomly matching value when there are conflicts in the dictionary path under the same schema`() {
            val dictionary = "Schema: { commonKey: [10, Twenty, specmatic@test.io, false] } ".let(Dictionary::fromYaml)
            val patterns = listOf(
                NumberPattern() to 10,
                EmailPattern() to "specmatic@test.io",
                BooleanPattern() to false
            ).map { it.first to toValue(it.second) }

            assertThat(patterns).allSatisfy { (pattern, expectedValue) ->
                val pattern = JSONObjectPattern(mapOf("commonKey" to pattern), typeAlias = "(Schema)")
                val resolver = Resolver(dictionary = dictionary)
                val value = pattern.generate(resolver).jsonObject.getValue("commonKey")
                assertThat(value).isEqualTo(expectedValue)
            }
        }

        @Test
        fun `should work with composite type multi-values`() {
            val dictionary = """
            Schema:
                commonKey:
                - objectKey: ObjectValue
                - - arrayKey: FirstArrayValue
                  - arrayKey: SecondArrayValue
            """.let(Dictionary::fromYaml)

            val pattern = JSONObjectPattern(mapOf(
                "commonKey" to AnyPattern(
                    extensions = emptyMap(),
                    pattern = listOf(
                        JSONObjectPattern(mapOf("objectKey" to StringPattern())),
                        ListPattern(JSONObjectPattern(mapOf("arrayKey" to StringPattern())))
                    ),
                )
            ), typeAlias = "(Schema)")

            val resolver = Resolver(dictionary = dictionary)
            val value = pattern.generate(resolver)
            val commonKeyValue = value.jsonObject.getValue("commonKey")
            assertThat(commonKeyValue).satisfiesAnyOf(
                {
                    assertThat(it).isInstanceOf(JSONObjectValue::class.java); it as JSONObjectValue
                    assertThat(it.jsonObject.getValue("objectKey")).isEqualTo(StringValue("ObjectValue"))
                },
                {
                    assertThat(it).isInstanceOf(JSONArrayValue::class.java) ; it as JSONArrayValue
                    assertThat(it.list).hasSize(2).containsExactlyInAnyOrder(
                        JSONObjectValue(mapOf("arrayKey" to StringValue("FirstArrayValue"))),
                        JSONObjectValue(mapOf("arrayKey" to StringValue("SecondArrayValue")))
                    )
                }
            )
        }

        @Test
        fun `should log all tried values when resolving best matching dictionary value`() {
            val dictionary = "'*': { commonKey: [10, Twenty, specmatic@test.io] } ".let(Dictionary::fromYaml)
            val pattern = JSONObjectPattern(mapOf("commonKey" to BooleanPattern()))
            val resolver = Resolver(dictionary = dictionary)
            val (stdout, value) = captureStandardOutput {
                withLogger(Verbose()) {  pattern.generate(resolver) }
            }

            val commonKeyValue = value.jsonObject.getValue("commonKey")
            assertThat(commonKeyValue).isInstanceOf(BooleanValue::class.java)
            assertThat(stdout).containsIgnoringWhitespaces(toViolationReportString(
                breadCrumb = "DICTIONARY..commonKey",
                details = DictionaryMismatchMessages.typeMismatch("boolean", "\"Twenty\"", "string"),
                StandardRuleViolation.TYPE_MISMATCH
            ))

            assertThat(stdout).containsIgnoringWhitespaces(toViolationReportString(
                breadCrumb = "DICTIONARY..commonKey",
                details = DictionaryMismatchMessages.typeMismatch("boolean", "10", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            ))

            assertThat(stdout).containsIgnoringWhitespaces(toViolationReportString(
                breadCrumb = "DICTIONARY..commonKey",
                details = DictionaryMismatchMessages.typeMismatch("boolean", "\"specmatic@test.io\"", "string"),
                StandardRuleViolation.TYPE_MISMATCH
            ))
        }

        @Test
        fun `should result in an exception if the value picked is invalid in strict-mode`() {
            val dictionary = "'*': { commonKey: [10, Twenty, specmatic@test.io] } ".let(Dictionary::fromYaml)
            val pattern = JSONObjectPattern(mapOf("commonKey" to BooleanPattern()))
            val resolver = Resolver(dictionary = dictionary.copy(strictMode = true))
            val exception = assertThrows<ContractException> { pattern.generate(resolver) }

            assertThat(exception.report()).containsIgnoringWhitespaces("""
            >> commonKey
            None of the dictionary values matched the schema.
            This could happen due to conflicts in the dictionary at the same json path, due to conflicting dataTypes at the same json path between multiple payloads
            strictMode enforces the presence of matching values in the dictionary if the json-path is present
            Either ensure that a matching value exists in the dictionary or disable strictMode
            """.trimIndent())
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
                )
            )
        }

        private fun listPatternOf(pattern: Pattern, nestedLevel: Int = 0): ListPattern {
            return ListPattern((1..nestedLevel).fold(pattern) { acc, _ -> ListPattern(acc) })
        }
    }
}
