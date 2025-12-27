package io.specmatic.test

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.Flags
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File

class ExamplePostValidatorTest {

    @Test
    fun `should return failures when asserts fail`() {
        val scenario = scenarioFrom(HttpResponse(body = parsedJSONObject("""{"data": "${"$"}eq(REQUEST.BODY.data)"}""")))
        val request = HttpRequest(body = parsedJSONObject("""{"data": "hello"}"""), method = "POST")
        val response = HttpResponse(body = parsedJSONObject("""{"data": "bye"}"""))
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.data",
                details = "Expected \"bye\" to equal \"hello\"",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should not run asserts for negative scenarios`() {
        val scenario = scenarioFrom(
            responseExampleForAssertion = HttpResponse(body = parsedJSONObject("""{"data": "${"$"}eq(REQUEST.BODY.data)"}"""))
        ).copy(isNegative = true)
        val request = HttpRequest(body = parsedJSONObject("""{"data": "hello"}"""))
        val response = HttpResponse(body = parsedJSONObject("""{"data": "bye"}"""))
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isNull()
    }

    @Test
    fun `should return null when asserts pass`() {
        val scenario = scenarioFrom(HttpResponse(body = parsedJSONObject("""{"data": "${"$"}eq(REQUEST.BODY.data)"}""")))
        val request = HttpRequest(body = parsedJSONObject("""{"data": "hello"}"""), method = "POST")
        val response = HttpResponse(body = parsedJSONObject("""{"data": "hello"}"""))
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isNull()
    }

    @Test
    fun `should work with nested array structure`() {
        val scenario = scenarioFrom(responseExampleForAssertion = HttpResponse(body = parsedValue("""
            [
              {
                "details": [
                  {
                    "name": "(string)",
                    "age": "${"$"}eq(REQUEST.BODY.age)"
                  }
                ]
              }
            ]
            """.trimIndent())))
        val request = HttpRequest(method = "POST", body = parsedJSONObject("""{"age": 20}"""))
        val response = HttpResponse(body ="""
        [
          {
            "details": [
              {
                "name": "John",
                "age": 20
              },
              {
                "name": "Jane",
                "age": 20
              }
            ]
          },
          {
            "details": [
              {
                "name": "John",
                "age": 20
              },
              {
                "name": "Jane",
                "age": 20
              }
            ]
          }
        ]
        """.trimIndent())
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isNull()
    }

    @Test
    fun `should have accurate breadcrumbs for assertion failures`() {
        val scenario = scenarioFrom(responseExampleForAssertion = HttpResponse(body = parsedValue("""
            [
              {
                "details": [
                  {
                    "name": "(string)",
                    "age": "${"$"}eq(REQUEST.BODY.age)"
                  }
                ]
              }
            ]
            """.trimIndent())))
        val request = HttpRequest(method = "POST", body = parsedJSONObject("""{"age": 20}"""))
        val response = HttpResponse(body ="""
        [
          {
            "details": [
              {
                "name": 1,
                "age": 1
              },
              {
                "name": 2,
                "age": 2
              }
            ]
          },
          {
            "details": [
              {
                "name": 3,
                "age": 3
              },
              {
                "name": 3,
                "age": 4
              }
            ]
          }
        ]
        """.trimIndent())
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].details[0].name",
                details = "Expected string, actual was 1 (number)",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].details[1].name",
                details = "Expected string, actual was 2 (number)",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[1].details[0].name",
                details = "Expected string, actual was 3 (number)",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[1].details[1].name",
                details = "Expected string, actual was 3 (number)",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].details[0].age",
                details = "Expected 1 to equal 20",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].details[1].age",
                details = "Expected 2 to equal 20",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[1].details[0].age",
                details = "Expected 3 to equal 20",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[1].details[1].age",
                details = "Expected 4 to equal 20",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `when value is missing from the actual fact store the failure should be intuitive`() {
        val scenario = scenarioFrom(HttpResponse(body = parsedJSONObject("""{"data": "${"$"}eq(REQUEST.BODY.data)"}""")))
        val request = HttpRequest(body = parsedJSONObject("""{"data": "hello"}"""), method = "POST")
        val response = HttpResponse(body = parsedJSONObject("""{}"""))
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        >> RESPONSE.BODY.data
        Could not resolve "RESPONSE.BODY.data" in response
        """.trimIndent())
    }

    @Test
    fun `should be able to discern between array assert and array items assert`() {
        val arrayItemsAssert = scenarioFrom(responseExampleForAssertion = HttpResponse(body = parsedValue("""
        {
          "details": [
            "(string)"
          ]
        }
        """.trimIndent())))
        val arrayAssert = scenarioFrom(responseExampleForAssertion = HttpResponse(body = parsedValue("""
        {
          "details": "(ListOfString)"
        }
        """.trimIndent())), newPatterns = mapOf("(ListOfString)" to ListPattern(StringPattern())))
        val request = HttpRequest(method = "POST")
        val response = HttpResponse(body ="""
        {
          "details": [
            "John",
            "Jane"
          ]
        }
        """.trimIndent())

        val arrayItemsResult = ExamplePostValidator.postValidate(arrayItemsAssert, arrayItemsAssert, request, response)
        assertThat(arrayItemsResult).isNull()

        val arrayAssertResult = ExamplePostValidator.postValidate(arrayAssert, arrayAssert, request, response)
        assertThat(arrayAssertResult).isNull()
    }

    @Test
    fun `should fail when array assert does not match`() {
        val arrayAssert = scenarioFrom(responseExampleForAssertion = HttpResponse(body = parsedValue("""
        {
          "details": "(ListOfString)"
        }
        """.trimIndent())), newPatterns = mapOf("(ListOfString)" to ListPattern(StringPattern())))
        val request = HttpRequest(method = "POST")
        val failingBodyValuesToErrorMessage = listOf(
            """{"details": "Not an array"}""" to "Expected list of string, actual was \"Not an array\"",
            """{"details": 123}""" to "Expected list of string, actual was 123 (number)",
            """{"details": null}""" to "Expected list of string, actual was null",
            """{"details": {"key": "value"}}""" to """Expected list of string, actual was JSON object {"key": "value"}""",
        )

        assertThat(failingBodyValuesToErrorMessage).allSatisfy {
            val response = HttpResponse(body = parsedValue(it.first))
            val result = ExamplePostValidator.postValidate(arrayAssert, arrayAssert, request, response)

            assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
            assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
            ${toViolationReportString(breadCrumb = "RESPONSE.BODY.details", details = it.second, StandardRuleViolation.TYPE_MISMATCH)}
            """.trimIndent())
        }
    }

    @Test
    fun `should fail when individual array item assert does not match`() {
        val arrayItemsAssert = scenarioFrom(responseExampleForAssertion = HttpResponse(body = parsedValue("""
        {
          "details": [
            "(string)"
          ]
        }
        """.trimIndent())))
        val request = HttpRequest(method = "POST")
        val failingBodyValuesToErrorMessage = listOf(
            """{"details": [123]}""" to "Expected string, actual was 123 (number)",
            """{"details": [null]}""" to "Expected string, actual was null",
            """{"details": [{"key": "value"}]}""" to """Expected string, actual was JSON object { "key": "value" }""",
        )

        assertThat(failingBodyValuesToErrorMessage).allSatisfy {
            val response = HttpResponse(body = parsedValue(it.first))
            val result = ExamplePostValidator.postValidate(arrayItemsAssert, arrayItemsAssert, request, response)

            assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
            println(result.reportString())
            assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
            ${toViolationReportString(breadCrumb = "RESPONSE.BODY.details[0]", details = it.second, StandardRuleViolation.TYPE_MISMATCH)}
            """.trimIndent())
        }
    }

    @Test
    fun `should work with nested array structures`() {
        val scenario = scenarioFrom(responseExampleForAssertion = HttpResponse(body = parsedValue("""
        [
          {
            "details": [
              [
                "(number)"
              ]
            ]
          }
        ]
        """.trimIndent())))
        val request = HttpRequest(method = "POST")
        val response = HttpResponse(body ="""
        [
          {
            "details": [
              [1, 2, 3]
            ]
          },
          {
            "details": [
              [false, "5", null]
            ]
          }
        ]
        """.trimIndent())
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[1].details[0][0]",
                details = "Expected number, actual was false (boolean)",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[1].details[0][1]",
                details = """Expected number, actual was "5"""",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[1].details[0][2]",
                details = "Expected number, actual was null",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())

    }

    @Test
    fun `should be able to assert on request path parameters`() {
        val scenario = scenarioFrom(
            responseExampleForAssertion = HttpResponse(
                body = parsedValue("""{"id": "${"$"}eq(REQUEST.PARAMETERS.PATH.id)"}""".trimIndent())
            ),
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/users/(id:number)"), method = "GET")
        )
        val request = HttpRequest(method = "GET", path = "/users/123")
        val response = HttpResponse(body = parsedValue("""{"id": 456}"""))
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.id",
                details = "Expected 456 to equal 123",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should be able to assert on request query parameter`() {
        val scenario = scenarioFrom(
            responseExampleForAssertion = HttpResponse(
                body = parsedValue("""{"id": "${"$"}eq(REQUEST.PARAMETERS.QUERY.id)"}""".trimIndent())
            ),
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/users"), method = "GET",
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("id" to QueryParameterScalarPattern(NumberPattern())))
            )
        )
        val request = HttpRequest(method = "GET", queryParams = QueryParameters(mapOf("id" to "123")))
        val response = HttpResponse(body = parsedValue("""{"id": 456}"""))
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.id",
                details = "Expected 456 to equal 123",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should be able to assert on request headers`() {
        val scenario = scenarioFrom(
            responseExampleForAssertion = HttpResponse(
                body = parsedValue("""{"id": "${"$"}eq(REQUEST.PARAMETERS.HEADER.id)"}""".trimIndent())
            ),
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/users"), method = "GET",
                headersPattern = HttpHeadersPattern(mapOf("id" to NumberPattern()))
            )
        )
        val request = HttpRequest(method = "GET", headers = mapOf("id" to "123"))
        val response = HttpResponse(body = parsedValue("""{"id": 456}"""))
        val result = ExamplePostValidator.postValidate(scenario, scenario, request, response)

        assertThat(result).isInstanceOf(Result.Failure::class.java); result as Result.Failure
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.id",
                details = "Expected 456 to equal 123",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should work e2e for request parameters assertions using external examples`() {
        val specFile = File("src/test/resources/openapi/partial_example_tests/simple.yaml")
        val examplesDir = specFile.parentFile.resolve("assert_example")
        val feature = Flags.using(Flags.EXAMPLE_DIRECTORIES to examplesDir.canonicalPath) {
            OpenApiSpecification.fromFile(specFile.path).toFeature().loadExternalisedExamples()
        }

        assertDoesNotThrow { feature.validateExamplesOrException() }
        val result = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(
                    status = 201,
                    body = parsedJSONObject("""{
                    "id": ${request.path!!.substringAfter("creators/").substringBefore("/pets").toInt()},
                    "traceId": "TRACE-123",
                    "creatorId": ${request.headers["CREATOR-ID"]!!.toInt()},
                    "petId": ${request.queryParams.asValueMap()["petId"]}
                    }""".trimIndent())
                )
            }
        })

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
    }

    companion object {
        fun scenarioFrom(
            responseExampleForAssertion: HttpResponse,
            httpRequestPattern: HttpRequestPattern = HttpRequestPattern(),
            httpResponsePattern: HttpResponsePattern = HttpResponsePattern(),
            newPatterns: Map<String, Pattern> = emptyMap()
        ): Scenario {
            return Scenario(ScenarioInfo(
                httpRequestPattern = httpRequestPattern,
                httpResponsePattern = httpResponsePattern,
                patterns = newPatterns
            )).copy(exampleRow = Row(responseExampleForAssertion = responseExampleForAssertion))
        }
    }
}