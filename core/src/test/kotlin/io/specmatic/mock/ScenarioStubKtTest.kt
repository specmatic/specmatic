package io.specmatic.mock

import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import io.specmatic.core.*
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.examples.preprocessor.ExamplePreProcessResult
import io.specmatic.core.examples.preprocessor.ExamplePreProcessor
import io.specmatic.core.examples.preprocessor.PreProcessorAttributes
import io.specmatic.core.examples.server.ExampleMismatchMessages
import io.specmatic.core.value.NumberValue
import io.specmatic.toViolationReportString
import io.specmatic.shouldMatch
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.function.Consumer
import java.util.stream.Stream
import kotlin.to

internal class ScenarioStubKtTest {
    @Test
    fun `conversion of json string to mock should load form fields`() {
        val mockString = """
{
    "http-request": {
        "method": "POST",
        "form-fields": {
            "Data": "10"
        }
    },
    
    "http-response": {
        "status": 200
    }
}
""".trimIndent()

        val mockData = jsonStringToValueMap(mockString)
        val mockScenario = mockFromJSON(mockData)

        assertThat(mockScenario.request.formFields.getValue("Data")).isEqualTo("10")
    }

    @Test
    fun `nullable number in string should load from a mock`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": {
      "number": 10,
      "description": "(number in string?)"
    }
  },

  "http-response": {
    "status": 200,
    "body": 100
  }
}
        """.trim()

        val mock = mockFromJSON(jsonStringToValueMap((mockText)))
        val pattern = mock.request.toPattern()

        parsedValue("""{"number": 10, "description": "10"}""") shouldMatch pattern.body
        parsedValue("""{"number": 10, "description": null}""") shouldMatch pattern.body

        assertThat(pattern.body.matches(parsedValue("""{"number": 10, "description": "test"}"""), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should deserialize multipart content form data mock`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "multipart-formdata": [
      {
        "name": "employeeid",
        "content": "10"
      }
    ]
  },

  "http-response": {
    "status": 200,
    "body": 100
  }
}
        """.trim()

        val mock = mockFromJSON(jsonStringToValueMap((mockText)))
        assertThat(mock.request.multiPartFormData).hasSize(1)
        assertThat(mock.request.multiPartFormData.first()).isInstanceOf(MultiPartContentValue::class.java)
        assertThat(mock.request.multiPartFormData.first().name).isEqualTo("employeeid")

        val part = mock.request.multiPartFormData.first() as MultiPartContentValue
        assertThat(part.content.toStringLiteral()).isEqualTo("10")
    }

    @Test
    fun `should deserialize multipart file form data mock`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "multipart-formdata": [
      {
        "name": "employees",
        "filename": "@employees.csv",
        "contentType": "text/csv",
        "contentEncoding": "gzip"
      }
    ]
  },

  "http-response": {
    "status": 200,
    "body": 100
  }
}
        """.trim()

        val mock = mockFromJSON(jsonStringToValueMap((mockText)))
        assertThat(mock.request.multiPartFormData).hasSize(1)
        assertThat(mock.request.multiPartFormData.first()).isInstanceOf(MultiPartFileValue::class.java)
        assertThat(mock.request.multiPartFormData.first().name).isEqualTo("employees")

        val part = mock.request.multiPartFormData.first() as MultiPartFileValue
        assertThat(part.filename).isEqualTo("employees.csv")
        assertThat(part.contentType).isEqualTo("text/csv")
        assertThat(part.contentEncoding).isEqualTo("gzip")
    }

    @Test
    fun `should generate request pattern containing multipart content from mock data`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "multipart-formdata": [
      {
        "name": "employeeid",
        "content": "10"
      }
    ]
  },

  "http-response": {
    "status": 200,
    "body": 100
  }
}
        """.trim()

        val mock = mockFromJSON(jsonStringToValueMap((mockText)))
        val pattern = mock.request.toPattern()
        assertThat(pattern.multiPartFormDataPattern).hasSize(1)
        assertThat(pattern.multiPartFormDataPattern.single().matches(MultiPartContentValue("employeeid", StringValue("10")), Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `should generate request pattern containing multipart file from mock data`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "multipart-formdata": [
      {
        "name": "employees",
        "filename": "@employees.csv",
        "contentType": "text/csv",
        "contentEncoding": "gzip"
      }
    ]
  },

  "http-response": {
    "status": 200,
    "body": 100
  }
}
        """.trim()

        val mock = mockFromJSON(jsonStringToValueMap((mockText)))
        val pattern = mock.request.toPattern()
        assertThat(pattern.multiPartFormDataPattern).hasSize(1)
        assertThat(pattern.multiPartFormDataPattern.single().matches(MultiPartFileValue("employees", "employees.csv", "text/csv", "gzip"), Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `load delay from stub info in seconds`() {
        val stubText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square"
  },

  "http-response": {
    "status": 200
  },
  
  "$DELAY_IN_SECONDS": 10
}
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.delayInMilliseconds).isEqualTo(10000)
    }

    @Test
    fun `load delay from stub info in milliseconds`() {
        val stubText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square"
  },

  "http-response": {
    "status": 200
  },
  
  "$DELAY_IN_MILLISECONDS": 1000
}
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.delayInMilliseconds).isEqualTo(1000)
    }

    @Test
    fun `delay in milliseconds priority over delay in seconds`() {
        val stubText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square"
  },

  "http-response": {
    "status": 200
  },
  
  "$DELAY_IN_SECONDS": 10,
  "$DELAY_IN_MILLISECONDS": 1000
}
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.delayInMilliseconds).isEqualTo(1000)
    }

    @Test
    fun `stub with no delay should result in empty delay value in loaded stub`() {
        val stubText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square"
  },

  "http-response": {
    "status": 200
  }
}
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.delayInMilliseconds).isNull()
    }

    @Test
    fun `show only the error for the scenario with matching status when there is a request mismatch for an OpenAPI contract having multiple error statuses`() {
        val openAPI = """
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://api.example.com/v1
    description: Optional server description, e.g. Main (production) server
  - url: http://staging-api.example.com
    description: Optional server description, e.g. Internal staging server for testing
paths:
  /hello/{id}:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: path
          name: id
          schema:
            type: integer
          required: true
          description: Numeric ID
        - in: header
          name: X-Value
          schema:
            type: number
          required: true
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '404':
          description: Not Found
          content:
            application/json:
              schema:
                type: string
    """.trim()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()
        val request = HttpRequest(method = "GET", path = "/hello/10", headers = mapOf("X-Value" to "data"))
        val response = HttpResponse.ok("success")

        assertThatThrownBy {
            feature.matchingStub(request, response, ExampleMismatchMessages)
        }.satisfies(Consumer {
            assertThat((it as NoMatchingScenario).report(request)).isEqualToIgnoringWhitespace("""
            In scenario "hello world. Response: Says hello"
            API: GET /hello/(id:number) -> 200
            ${
                toViolationReportString(
                    breadCrumb = "REQUEST.PARAMETERS.HEADER.X-Value",
                    details = ExampleMismatchMessages.typeMismatch("number", "\"data\"", "string"),
                    StandardRuleViolation.TYPE_MISMATCH
                )
            }
            """.trimIndent())
        })
    }

    @Test
    fun `should be able to serialize partial stubs to JSON`() {
        val request = HttpRequest(method = "POST", path = "/customer", headers = emptyMap(), formFields = emptyMap(), multiPartFormData = emptyList())
        val response = HttpResponse(status = 200, body = parsedValue("""{"id": 10}"""))
        val scenarioStub = ScenarioStub(partial = ScenarioStub(request, response))

        val json = scenarioStub.toJSON()
        assertThat(json.keys()).containsExactly(PARTIAL)
        assertThat((json.jsonObject[PARTIAL] as JSONObjectValue).keys()).containsExactly(MOCK_HTTP_REQUEST, MOCK_HTTP_RESPONSE)
        assertThat((json.jsonObject[PARTIAL] as JSONObjectValue).jsonObject[MOCK_HTTP_REQUEST]).isEqualTo(request.toJSON())
        assertThat((json.jsonObject[PARTIAL] as JSONObjectValue).jsonObject[MOCK_HTTP_RESPONSE]).isEqualTo(response.toJSON())
    }

    @Test
    fun `additional data should be added to partial stub JSON`() {
        val request = HttpRequest(method = "POST", path = "/customer", headers = emptyMap(), formFields = emptyMap(), multiPartFormData = emptyList())
        val response = HttpResponse(status = 200, body = parsedValue("""{"id": 10}"""))
        val additionalData = JSONObjectValue(mapOf("foo" to StringValue("bar")))
        val scenarioStub = ScenarioStub(partial = ScenarioStub(request, response), data = additionalData)

        val json = scenarioStub.toJSON()
        assertThat(json.keys()).containsExactlyInAnyOrder("foo", PARTIAL)
        assertThat(json.jsonObject["foo"]).isEqualTo(StringValue("bar"))
    }

    @Test
    fun `should be able to update request in an partial stub`() {
        val request = HttpRequest(method = "POST", path = "/customer")
        val stub = ScenarioStub(partial = ScenarioStub(request))
        val updatedRequest = HttpRequest(method = "GET", path = "/customer")
        val withUpdatedRequest = stub.updateRequest(updatedRequest)

        assertThat(withUpdatedRequest.requestElsePartialRequest()).isEqualTo(updatedRequest)
        assertThat(withUpdatedRequest.partial?.request).isEqualTo(updatedRequest)
    }

    @Test
    fun `should be able to update response in an partial stub`() {
        val response = HttpResponse(status = 200)
        val stub = ScenarioStub(partial = ScenarioStub(response = response))
        val updatedResponse = HttpResponse(status = 201)
        val withUpdatedResponse = stub.updateResponse(updatedResponse)

        assertThat(withUpdatedResponse.response()).isEqualTo(updatedResponse)
        assertThat(withUpdatedResponse.partial?.response).isEqualTo(updatedResponse)
    }

    @ParameterizedTest
    @MethodSource("io.specmatic.mock.ScenarioStubKtTest#invalidExampleToMessageProvider")
    fun `should provide appropriate error message when example is invalid with missing or invalid keys`(mockString: String, expectedMessage: String) {
        val exception = assertThrows<ContractException> { ScenarioStub.parse(mockString) }
        assertThat(exception.report()).isEqualToNormalizingWhitespace(expectedMessage)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `should be able to parse mocks with transient boolean property`(isTransient: Boolean) {
        val stubText = """
        {
          "$IS_TRANSIENT_MOCK": $isTransient,
          "http-request": {
            "method": "POST",
            "path": "/square"
          },
          "http-response": {
            "status": 200
          }
        }
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.stubToken != null).isEqualTo(isTransient)
    }

    @Test
    fun `should prefer explicit http-stub-id over transient boolean random value`() {
        val stubText = """
        {
          "$IS_TRANSIENT_MOCK": true,
          "http-stub-id": "10",
          "http-request": {
            "method": "POST",
            "path": "/square"
          },
          "http-response": {
            "status": 200
          }
        }
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.stubToken).isEqualTo("10")
    }

    @Test
    fun `should not be considered transient if transient property and http-stub-id is not defined`() {
        val stubText = """
        {
          "http-request": {
            "method": "POST",
            "path": "/square"
          },
          "http-response": {
            "status": 200
          }
        }
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.stubToken).isNull()
    }

    @Test
    fun `mockFromJSON should apply registered example preprocessor and keep attributes`() {
        val outputKey = object : PreProcessorAttributes.Key<String> {}
        val processor = object : ExamplePreProcessor {
            override fun process(rawData: Map<String, io.specmatic.core.value.Value>, filePath: String?): ExamplePreProcessResult {
                return ExamplePreProcessResult(
                    result = Success(),
                    outcome = rawData + ("processed" to StringValue("yes")),
                    attributes = PreProcessorAttributes.Empty.put(outputKey, "stored-value")
                )
            }
        }

        val mockSpec = mapOf(
            "name" to StringValue("original"),
            MOCK_HTTP_RESPONSE to JSONObjectValue(mapOf("status" to NumberValue(200))),
            MOCK_HTTP_REQUEST to JSONObjectValue(mapOf("method" to StringValue("GET"), "path" to StringValue("/before"))),
        )

        ExamplePreProcessor.withPreProcessor(processor) {
            val scenarioStub = mockFromJSON(mockSpec)
            assertThat(scenarioStub.data.jsonObject["processed"]).isEqualTo(StringValue("yes"))
            assertThat(scenarioStub.preProcessorAttributes[outputKey]).isEqualTo("stored-value")
        }
    }

    companion object {
        @JvmStatic
        fun invalidExampleToMessageProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("""{
                    "supposed-to-be-http-request": { "path": "/add", "method": "POST" },
                    "http-response": { "status": 200 }
                    }""".trimIndent(),
                    toViolationReportString(
                        breadCrumb = "supposed-to-be-http-request",
                        details = unexpectedKeyButMatches("supposed-to-be-http-request", "http-request"),
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "method": "POST" },
                    "supposed-to-be-http-response": { "status": 200 }
                    }""".trimIndent(),
                    toViolationReportString(
                        breadCrumb = "supposed-to-be-http-response",
                        details = unexpectedKeyButMatches("supposed-to-be-http-response", "http-response"),
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "supposed-to-be-method": "POST" },
                    "http-response": { "status": 200 }
                    }""".trimIndent(),
                    toViolationReportString(
                        breadCrumb = "http-request.supposed-to-be-method",
                        details = unexpectedKeyButMatches("supposed-to-be-method", "method"),
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "method": "POST" },
                    "http-response": { "supposed-to-be-status": 200 }
                    }""".trimIndent(),
                    toViolationReportString(
                        breadCrumb = "http-response.supposed-to-be-status",
                        details = unexpectedKeyButMatches("supposed-to-be-status", "status"),
                        StandardRuleViolation.REQUIRED_PROPERTY_MISSING
                    )
                )
            )
        }

        private fun unexpectedKeyButMatches(unexpectedKey: String, candidate: String): String {
            return "${FuzzyExampleMisMatchMessages.unexpectedKey("property", unexpectedKey)}. Did you mean \"$candidate\"?"
        }
    }
}
