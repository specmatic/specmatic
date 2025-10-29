package io.specmatic.mock

import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import io.specmatic.core.*
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import io.specmatic.trimmedLinesList
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.function.Consumer
import java.util.stream.Stream
import kotlin.time.Duration.Companion.seconds

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
    
    "mock-http-response": {
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

  "mock-http-response": {
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

  "mock-http-response": {
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

  "mock-http-response": {
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

  "mock-http-response": {
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

  "mock-http-response": {
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
    fun `request-response with json body in request to gherkin string`() {
        val request = HttpRequest(method = "POST", path = "/customer", headers = emptyMap(), body = parsedValue("""{"name": "John Doe", "address": {"street": "High Street", "city": "Manchester"}}"""), queryParametersMap = emptyMap(), formFields = emptyMap(), multiPartFormData = emptyList())
        val response = HttpResponse(status = 200, body = parsedValue("""{"id": 10}"""))

        validateStubAndSpec(request, response, """Feature: New Feature
  Scenario: New scenario
    Given type Address
      | street | (string) |
      | city | (string) |
    And type RequestBody
      | name | (string) |
      | address | (Address) |
    And type ResponseBody
      | id | (integer) |
    When POST /customer
    And request-body (RequestBody)
    Then status 200
    And response-body (ResponseBody)
  
    Examples:
    | name | street | city |
    | John Doe | High Street | Manchester |""")
    }

    @Test
    fun `request-response with headers to gherkin string`() {
        val request = HttpRequest(method = "POST", path="/customer", headers = mapOf("X-Header1" to "value 1", "X-Header2" to "value 2"), body = parsedValue("""{"name": "John Doe", "address": {"street": "High Street", "city": "Manchester"}}"""), queryParametersMap = emptyMap(), formFields = emptyMap(), multiPartFormData = emptyList())
        val response = HttpResponse(status = 200, headers = mapOf("X-Required" to "this is a must", "X-Extra" to "something more"), body = parsedValue("""{"id": 10}"""))

        validateStubAndSpec(request, response, """Feature: New Feature
  Scenario: New scenario
    Given type Address
      | street | (string) |
      | city | (string) |
    And type RequestBody
      | name | (string) |
      | address | (Address) |
    And type ResponseBody
      | id | (integer) |
    When POST /customer
    And request-header X-Header1 (string)
    And request-header X-Header2 (string)
    And request-body (RequestBody)
    Then status 200
    And response-header X-Required (string)
    And response-header X-Extra (string)
    And response-body (ResponseBody)
  
    Examples:
    | X-Header1 | X-Header2 | name | street | city |
    | value 1 | value 2 | John Doe | High Street | Manchester |""")
    }

    @Test
    fun `request-response with form fields to gherkin string`() {
        val request = HttpRequest(method = "POST", path = "/customer", headers = emptyMap(), formFields = mapOf("X-FormData1" to "some value"), multiPartFormData = emptyList())
        val response = HttpResponse(status = 200, body = parsedValue("""{"id": 10}"""))

        validateStubAndSpec(request, response, """Feature: New Feature
  Scenario: New scenario
    Given type ResponseBody
      | id | (integer) |
    When POST /customer
    And form-field X-FormData1 (string)
    Then status 200
    And response-body (ResponseBody)
  
    Examples:
    | X-FormData1 |
    | some value |""")
    }

    @Test
    fun `request-response with multipart form data content to gherkin string`() {
        val request = HttpRequest(method = "POST", path = "/customer", headers = emptyMap(), formFields = emptyMap(), multiPartFormData = listOf(MultiPartContentValue("name", StringValue("John Doe"))))
        val response = HttpResponse(status = 200, body = parsedValue("""{"id": 10}"""))

        validateStubAndSpec(request, response, """Feature: New Feature
  Scenario: New scenario
    Given type ResponseBody
      | id | (integer) |
    When POST /customer
    And request-part name (string)
    Then status 200
    And response-body (ResponseBody)
  
    Examples:
    | name |
    | John Doe |""")
    }

    @Test
    fun `request-response with multipart form data file to gherkin string`() {
        val request = HttpRequest(method = "POST", path = "/customer", headers = emptyMap(), formFields = emptyMap(), multiPartFormData = listOf(MultiPartFileValue("customer_csv", "customer.csv", "text/csv", "identity")))
        val response = HttpResponse(status = 200, body = parsedValue("""{"id": 10}"""))

        validateStubAndSpec(request, response, """Feature: New Feature
  Scenario: New scenario
    Given type ResponseBody
      | id | (integer) |
    When POST /customer
    And request-part customer_csv @(string) text/csv identity
    Then status 200
    And response-body (ResponseBody)
  
    Examples:
    | customer_csv_filename |
    | customer.csv |""")
    }

    @Test
    fun `converts mock json to gherkin`() {
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    When POST /square
    And request-part employees @(string) text/csv gzip
    Then status 200
    And response-body (integer)
  
    Examples:
    | employees_filename |
    | employees.csv |"""
        )
    }

    @Test
    fun `converts array in request body to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [ "one", "two", "three" ]
  },

  "http-response": {
    "status": 200,
    "body": 100
  }
}
        """.trim()

        val mock = mockFromJSON(jsonStringToValueMap((mockText)))
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    When POST /square
    And request-body (string*)
    Then status 200
    And response-body (integer)""")
    }

    @Test
    fun `converts array of identically structured json objects in request body to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": "John Doe"
      },
      {
        "name": "John Doe"
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name |
    | John Doe |""")
    }

    @Test
    fun `converts array of json objects in the request body where the first contains a key not in the other to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": "John Doe",
        "address": "High Street"
      },
      {
        "name": "John Doe"
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string) |
      | address? | (string) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name | address |
    | John Doe | High Street |""")
    }

    @Test
    fun `converts array of json objects in the request body where the second contains a key not in the other to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": "John Doe"
      },
      {
        "name": "John Doe",
        "address": "High Street"
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string) |
      | address? | (string) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name |
    | John Doe |""")
    }

    @Test
    fun `converts array of 2 json objects in the request body where a value in the second is null to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": "John Doe",
        "address": "High Street"
      },
      {
        "name": null
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string?) |
      | address? | (string) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name | address |
    | John Doe | High Street |""")
    }

    @Test
    fun `converts array of 3 json objects in the request body where a value in the first is null to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": null
      },
      {
        "name": "John Doe",
        "address": null
      },
      {
        "name": "John Doe",
        "address": "High Street"
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string?) |
      | address? | (string?) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name |
    | (null) |""")
    }

    @Test
    fun `converts array of 3 json objects in the request body where a value in the first and second is null to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": null
      },
      {
        "name": null,
        "address": null
      },
      {
        "name": "John Doe",
        "address": "High Street"
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string?) |
      | address? | (string?) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name |
    | (null) |""")
    }
    @Test
    fun `converts array of 3 json objects in the request body where a value in the first and third is null to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": null
      },
      {
        "name": "John Doe"
      },
      {
        "name": null
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string?) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name |
    | (null) |""")
    }

    @Test
    fun `converts array of json objects in the request body where the keys are identical but a value in the second is to gherkin`() {
        val mockText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": [
      {
        "name": "John Doe"
      },
      {
        "name": null,
        "address": "High Street"
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
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    Given type RequestBody
      | name | (string?) |
      | address? | (string) |
    When POST /square
    And request-body (RequestBody*)
    Then status 200
    And response-body (integer)
  
    Examples:
    | name |
    | John Doe |""")
    }

    @Test
    fun `empty array in request body`() {
        val stubText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": []
  },

  "http-response": {
    "status": 200,
    "body": 100
  }
}
        """.trim()

        val mock = mockFromJSON(jsonStringToValueMap(stubText))
        validateStubAndSpec(mock.request, mock.response, """Feature: New Feature
  Scenario: New scenario
    When POST /square
    And request-body []
    Then status 200
    And response-body (integer)""")
    }

    @Test
    fun `null in request body throws an exception`() {
        val stubText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square",
    "body": null
  },

  "http-response": {
    "status": 200
  }
}
        """.trim()

        assertThatThrownBy { mockFromJSON(jsonStringToValueMap(stubText)) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `null in response body throws an exception`() {
        val stubText = """
{
  "http-request": {
    "method": "POST",
    "path": "/square"
  },

  "http-response": {
    "status": 200,
    "body": null
  }
}
        """.trim()

        assertThatThrownBy { mockFromJSON(jsonStringToValueMap(stubText)) }.isInstanceOf(ContractException::class.java)
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
            feature.matchingStub(request, response, ContractAndStubMismatchMessages)
        }.satisfies(Consumer {
            assertThat((it as NoMatchingScenario).report(request).trim().trimmedLinesList()).isEqualTo("""
                In scenario "hello world. Response: Says hello"
                API: GET /hello/(id:number) -> 200

                  >> REQUEST.PARAMETERS.HEADER.X-Value
                  
                     ${ContractAndStubMismatchMessages.mismatchMessage("number", """"data" """.trim())}
                """.trimIndent().trimmedLinesList())
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

    @Test
    fun `should be able to parse additional metadata like timeouts when specified inside a partial`() {
        val stubText = """
{
  "partial": {
    "delay-in-seconds": 10,
    "http-stub-id": "10",
    "http-request": {
      "bodyRegex": "\\w+",
      "method": "POST",
      "path": "/square"
    },
    "http-response": {
      "status": 200
    },
    "data": {
      "category": {
        "technology": {
          "city": "Gotham"
        }
      }
    }
  }
}
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.delayInMilliseconds).isEqualTo(10.seconds.inWholeMilliseconds)
        assertThat(scenarioStub.stubToken).isEqualTo("10")
        assertThat(scenarioStub.requestBodyRegex).isEqualTo("\\w+")
        assertThat(scenarioStub.data).isEqualTo(parsedJSONObject("""
{
    "delay-in-seconds": 10,
    "http-stub-id": "10",
    "data": {
      "category": {
        "technology": {
          "city": "Gotham"
        }
      }
    }
}
""".trimIndent()))
    }

    @Test
    fun `should be able to parse additional metadata like timeouts when specified outside and partial`() {
        val stubText = """
{
  "delay-in-seconds": 10,
  "http-stub-id": "10",
  "partial": {
    "http-request": {
      "bodyRegex": "\\w+",
      "method": "POST",
      "path": "/square"
    },
    "http-response": {
      "status": 200
    }
  },
  "data": {
    "category": {
      "technology": {
        "city": "Gotham"
      }
    }
  }
}
        """.trim()

        val scenarioStub = mockFromJSON(jsonStringToValueMap(stubText))
        assertThat(scenarioStub.delayInMilliseconds).isEqualTo(10.seconds.inWholeMilliseconds)
        assertThat(scenarioStub.stubToken).isEqualTo("10")
        assertThat(scenarioStub.requestBodyRegex).isEqualTo("\\w+")
        assertThat(scenarioStub.data).isEqualTo(parsedJSONObject("""
{
    "delay-in-seconds": 10,
    "http-stub-id": "10",
    "data": {
      "category": {
        "technology": {
          "city": "Gotham"
        }
      }
    }
}
""".trimIndent()))
    }

    companion object {
        @JvmStatic
        fun invalidExampleToMessageProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("""{
                    "supposed-to-be-http-request": { "path": "/add", "method": "POST" },
                    "http-response": { "status": 200 }
                    }""".trimIndent(),
                    "Stub does not contain http-request/mock-http-request as a top level key."
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "method": "POST" },
                    "supposed-to-be-http-response": { "status": 200 }
                    }""".trimIndent(),
                    "Stub does not contain http-response/mock-http-response as a top level key."
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "supposed-to-be-method": "POST" },
                    "http-response": { "status": 200 }
                    }""".trimIndent(),
                    "http-request must contain a key named method whose value is the method in the request"
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "method": "POST", body: null },
                    "http-response": { "status": 200 }
                    }""".trimIndent(),
                    "Either body should have a value or the key should be absent from http-request"
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "method": "POST" },
                    "http-response": { "supposed-to-be-status": 200 }
                    }""".trimIndent(),
                    "http-response must contain a key named status, whose value is the http status in the response"
                ),
                Arguments.of("""{
                    "http-request": { "path": "/add", "method": "POST" },
                    "http-response": { "status": 200,  body: null }
                    }""".trimIndent(),
                    "Either body should have a value or the key should be absent from http-response"
                )
            )
        }
    }
}

fun validateStubAndSpec(request: HttpRequest, response: HttpResponse, expectedGherkin: String? = null) {
    try {
        val cleanedUpResponse = dropContentAndCORSResponseHeaders(response)
        val gherkin = toGherkinFeature(NamedStub("New scenario", ScenarioStub(request, cleanedUpResponse))).also { println(it) }

        if(expectedGherkin != null) {
            assertThat(gherkin.trim()).isEqualTo(expectedGherkin.trim())
        } else {
            println(gherkin)
        }

        val behaviour = parseGherkinStringToFeature(gherkin)
        behaviour.matchingStub(request, cleanedUpResponse)
    } catch (e: Throwable) {
        println(e.localizedMessage)
        throw e
    }
}
