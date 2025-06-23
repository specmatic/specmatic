package io.specmatic.stub

import io.mockk.InternalPlatformDsl.toStr
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.KeyData
import io.specmatic.core.QueryParameters
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.SPECMATIC_RESULT_HEADER
import io.specmatic.core.log.consoleLog
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.toXMLNode
import io.specmatic.mock.ScenarioStub
import io.specmatic.stubResponse
import io.specmatic.test.LegacyHttpClient
import io.specmatic.trimmedLinesList
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.fail
import java.security.KeyStore
import java.util.*
import java.util.function.Consumer

internal class HttpStubKtTest {

    @Test
    fun `flush transient stub`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    get:
      summary: hello world
      description: test
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
        """.trimIndent(), "").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/data"
                    },
                    "http-response": {
                        "status": 200,
                        "body": 10
                    },
                    "http-stub-id": "123"
                }
            """.trimIndent())

            stub.client.execute(HttpRequest("DELETE", "/_specmatic/http-stub/123"))

            val response = stub.client.execute(HttpRequest("GET", "/data"))
            assertThat(response.headers["X-Specmatic-Type"]).isEqualTo("random")
        }
    }

    @Test
    fun `transient stub`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    get:
      summary: hello world
      description: test
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
        """.trimIndent(), "").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/data"
                    },
                    "http-response": {
                        "status": 200,
                        "body": 10
                    },
                    "http-stub-id": "123"
                }
            """.trimIndent())

            val firstResponse = stub.client.execute(HttpRequest("GET", "/data"))
            assertThat(firstResponse.headers.toMap()).doesNotContainKey("X-Specmatic-Type")

            val secondResponse = stub.client.execute(HttpRequest("GET", "/data"))
            assertThat(secondResponse.headers["X-Specmatic-Type"]).isEqualTo("random")
        }
    }

    @Test
    fun `transient stub matches in reverse order`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - item
              properties:
                item:
                  type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent(), "").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "POST",
                        "path": "/data",
                        "body": {
                            "item": "123"
                        }
                    },
                    "http-response": {
                        "status": 200,
                        "body": "first"
                    },
                    "http-stub-id": "123"
                }
            """.trimIndent())

            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "POST",
                        "path": "/data",
                        "body": {
                            "item": "123"
                        }
                    },
                    "http-response": {
                        "status": 200,
                        "body": "second"
                    },
                    "http-stub-id": "123"
                }
            """.trimIndent())

            val request = HttpRequest("POST", "/data", body = parsedJSON("""{"item": "123"}"""))
            val firstResponse = stub.client.execute(request)
            assertThat(firstResponse.body.toStringLiteral()).isEqualTo("first")

            val secondResponse = stub.client.execute(request)
            assertThat(secondResponse.body.toStringLiteral()).isEqualTo("second")
        }
    }

    @Test
    fun `transient match precedes non-transient stub match`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - item
              properties:
                item:
                  type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent(), "").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "POST",
                        "path": "/data",
                        "body": {
                            "item": "123"
                        }
                    },
                    "http-response": {
                        "status": 200,
                        "body": "transient"
                    },
                    "http-stub-id": "123"
                }
            """.trimIndent())

            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "POST",
                        "path": "/data",
                        "body": {
                            "item": "123"
                        }
                    },
                    "http-response": {
                        "status": 200,
                        "body": "non-transient"
                    }
                }
            """.trimIndent())

            val request = HttpRequest("POST", "/data", body = parsedJSON("""{"item": "123"}"""))
            val firstResponse = stub.client.execute(request)
            assertThat(firstResponse.body.toStringLiteral()).isEqualTo("transient")

            val secondResponse = stub.client.execute(request)
            assertThat(secondResponse.body.toStringLiteral()).isEqualTo("non-transient")

            val thirdResponse = stub.client.execute(request)
            assertThat(thirdResponse.body.toStringLiteral()).isEqualTo("non-transient")
        }
    }

    @Test
    fun `SSE test`() {
        val gherkin = """
Feature: Test
  Scenario: Test
    When POST /
    And request-body (number)
    Then status 200
""".trim()

        val feature = parseGherkinStringToFeature(gherkin)

        HttpStub(feature).use {
            val body = """{
"event": "features",
"id": "332f8278",
"data": "[{\"id\":\"b5bf7f9e-9391-40a4-8808-61ad73f800e9\",\"key\":\"FT01\",\"l\":true,\"version\":1,\"type\":\"BOOLEAN\",\"value\":true},{\"id\":\"8b6002e8-e97a-4ebe-8cae-ac68fb99fc33\",\"key\":\"FT02\",\"l\":true,\"version\":1,\"type\":\"BOOLEAN\",\"value\":false}]"
}""".toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder().url(it.endPoint + "/_specmatic/sse-expectations").addHeader("Content-Type", "application/json").post(body).build()
            val call = OkHttpClient().newCall(request)
            val response = call.execute()

            println(response.toStr())
            println(response.body?.string())
            assertThat(response.code).isEqualTo(200)
        }
    }

    @Test
    fun `in strict mode the stub replies with an explanation of what broke instead of a randomly generated response`() {
        val gherkin = """
Feature: Test
  Scenario: Test
    When POST /
    And request-body (number)
    Then status 200
""".trim()

        val feature = parseGherkinStringToFeature(gherkin)

        val request = HttpRequest(method = "POST", path = "/", body = NumberValue(10))
        val scenarioStub = ScenarioStub(request, HttpResponse.OK, null)

        val stubData = feature.matchingStub(scenarioStub)
        val stubResponse = stubResponse(HttpRequest(method = "POST", path = "/", body = StringValue("Hello")), listOf(feature), listOf(stubData), true)

        assertResponseFailure(stubResponse, """STRICT MODE ON

>> REQUEST.BODY

   Expected number, actual was "Hello"""")
    }

    private fun assertResponseFailure(stubResponse: HttpStubResponse, errorMessage: String) {
        assertThat(stubResponse.response.status).isEqualTo(400)
        assertThat(stubResponse.response.headers).containsEntry(SPECMATIC_RESULT_HEADER, "failure")
        assertThat(stubResponse.response.body.toStringLiteral().trimmedLinesList()).isEqualTo(errorMessage.trimmedLinesList())
    }

    @Test
    fun `undeclared keys should not be accepted by stub without expectations`() {
        val request = HttpRequest(method = "POST", path = "/", body = parsedValue("""{"number": 10, "undeclared": 20}"""))

        val feature = parseGherkinStringToFeature("""
Feature: POST API
  Scenario: Test
    When POST /
    And request-body
      | number | (number) |
    Then status 200
""".trim())

        val stubResponse = stubResponse(request, listOf(feature), emptyList(), false)

        assertResponseFailure(stubResponse,
            """
            In scenario "Test"
            API: POST / -> 200
            
              >> REQUEST.BODY.undeclared
              
                 ${ContractAndRequestsMismatch.unexpectedKey("key", "undeclared")}
            """.trimIndent())
    }

    @Test
    fun `unexpected headers should be accepted by a stub without expectations`() {
        val request = HttpRequest(method = "GET", path = "/", headers = mapOf("X-Expected" to "data", "Accept" to "text"))

        val feature = parseGherkinStringToFeature("""
Feature: GET API
  Scenario: Test
    When GET /
    And request-header X-Expected (string)
    Then status 200
""".trim())

        val stubResponse = stubResponse(request, listOf(feature), emptyList(), false)

        assertThat(stubResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `generates a valid endpoint when a port is not given`() {
        assertThat(endPointFromHostAndPort("localhost", null, null)).isEqualTo("http://localhost")
    }

    @Test
    fun `generates a valid endpoint when a non 80 port is given`() {
        assertThat(endPointFromHostAndPort("localhost", 9000, null)).isEqualTo("http://localhost:9000")
    }

    @Test
    fun `generates a valid endpoint when port 80 is given`() {
        assertThat(endPointFromHostAndPort("localhost", 80, null)).isEqualTo("http://localhost")
    }

    @Test
    fun `generates an https endpoint when key store data is provided`() {
        assertThat(endPointFromHostAndPort("localhost", 80, KeyData(KeyStore.getInstance(KeyStore.getDefaultType()), ""))).isEqualTo("https://localhost")
    }

    @Test
    fun `should not match extra keys in the request`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API

Scenario: Square of a number
  When POST /number
  And request-body
  | number | (number) |
  | ...    |          |
  Then status 200
""".trim())

        val request = HttpRequest(method = "POST", path = "/number", body = parsedValue("""{"number": 10, "unexpected": "data"}"""))
        val response = stubResponse(request, listOf(feature), emptyList(), false)

        assertResponseFailure(response,
            """
            In scenario "Square of a number"
            API: POST /number -> 200
            
              >> REQUEST.BODY.unexpected
              
                 ${ContractAndRequestsMismatch.unexpectedKey("key", "unexpected")}
            """.trimIndent())
    }

    @Test
    fun `stub should not match a request in which all the query params are missing`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API

Scenario: Square of a number
  When GET /count?status=(string)
  Then status 200
  And response-body (string)
""".trim())

        val stubRequest = HttpRequest(method = "GET", path = "/count", queryParametersMap = mapOf("status" to "available"))
        val stubResponse = HttpResponse.ok("data")
        val stubData = HttpStubData(
            stubRequest.toPattern(),
            stubResponse,
            Resolver(),
            responsePattern = HttpResponsePattern()
        )

        val request = HttpRequest(method = "GET", path = "/count")
        val response = stubResponse(request, listOf(feature), listOf(stubData), false)
        assertThat(response.response.status).isEqualTo(200)

        val strictResponse = stubResponse(request, listOf(feature), listOf(stubData), true)
        assertResponseFailure(strictResponse, """STRICT MODE ON

>> REQUEST.PARAMETERS.QUERY.status

   ${StubAndRequestMismatchMessages.expectedKeyWasMissing("query param", "status")}""")
    }

    @Test
    fun `should not generate any key from the ellipsis in the response`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API

Scenario: Square of a number
  When GET /number
  Then status 200
  And response-body
  | number | (number) |
  | ...    |          |
""".trim())

        val request = HttpRequest(method = "GET", path = "/number")
        val response = stubResponse(request, listOf(feature), emptyList(), false)

        assertThat(response.response.status).isEqualTo(200)
        val bodyValue = response.response.body as JSONObjectValue
        assertThat(bodyValue.jsonObject).hasSize(1)
        assertThat(bodyValue.jsonObject.getValue("number")).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `invalid stub request`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API

Scenario: Square of a number
  Given json Information
    | id   | (number) |
    | data | (More)   |
  And json More
    | info | (number) |
  When POST /number
  And form-field Data (
  Then status 200
  And response-body
    | number | (number) |
""".trim())

        val stubSetupRequest = HttpRequest(method = "GET", path = "/number", formFields = mapOf("Data" to """{"id": 10, "data": {"info": 20} }"""))
        val actualRequest = HttpRequest(method = "GET", path = "/number", formFields = mapOf("NotData" to """{"id": 10, "data": {"info": 20} }"""))
        val response = stubResponse(actualRequest, listOf(feature), listOf(HttpStubData(
            stubSetupRequest.toPattern(),
            HttpResponse(status = 200, body = parsedJSON("""{"10": 10}""")),
            feature.scenarios.single().resolver,
            responsePattern = HttpResponsePattern()
        )), false)
        assertThat(response.response.status).isEqualTo(400)
    }

    @Test
    fun `stub should validate expectations and serve generated xml when the namespace prefix changes`() {
        val feature = parseGherkinStringToFeature("""
Feature: XML namespace prefix
  Scenario: Request has namespace prefixes
    When POST /
    And request-body <ns1:customer xmlns:ns1="http://example.com/customer"><name>(string)</name></ns1:customer>
    Then status 200
        """.trimIndent())

        val stubSetupRequest = HttpRequest().updateMethod("POST").updatePath("/")
        val actualRequest = HttpRequest(method = "POST", path = "/", body = toXMLNode("""<ns2:customer xmlns:ns2="http://example.com/customer"><name>John Doe</name></ns2:customer>"""))
        val response = stubResponse(actualRequest, listOf(feature), listOf(HttpStubData(
            stubSetupRequest.toPattern(),
            HttpResponse.OK,
            feature.scenarios.single().resolver,
            responsePattern = HttpResponsePattern()
        )), false)
        assertThat(response.response.status).isEqualTo(200)
    }

    @Test
    fun `multithreading test`() {
        val feature = parseGherkinStringToFeature("""
Feature: POST API
  Scenario: Test
    Given type Number
      | number | (number) |
    When POST /
    And form-field Data (Number)
    Then status 200
    And response-body (Number)
""".trim())

        val errors: Vector<String> = Vector()

        HttpStub(feature).use { stub ->
            usingMultipleThreads { stubNumber ->
                `set an expectation and exercise it`(stubNumber, stub)?.let {
                    errors.add(it)
                }
            }
        }

        if(errors.isNotEmpty()) {
            val errorMessages = errors.joinToString("\n\n")
            fail("Got errors\n$errorMessages")
        }
    }

    private fun usingMultipleThreads(fn: (Int) -> Unit) {
        val range = 0 until 20
        val threads = range.map { stubNumber ->
            Thread {
                val base = stubNumber * 10

                (0..2).forEach { increment ->
                    fn(base + increment)
                }
            }
        }

        start(threads)
        waitFor(threads)
    }

    private fun `set an expectation and exercise it`(stubNumber: Int, stub: HttpStub): String? {
        return try {
            val error = createExpectation(stubNumber, stub)
            if (error != null)
                return "Creating expectation $stubNumber:\n $error"

            val response = invokeStub(stubNumber, stub)
            println(response.body.toStringLiteral())

            val json = try {
                response.body as JSONObjectValue
            } catch (e: Throwable) {
                return "Got the following bad response:\n${response.toLogString()}"
            }

            val numberInResponse = try {

                json.jsonObject.getValue("number").toStringLiteral().toInt()
            } catch (e: Throwable) {
                return exceptionCauseMessage(e)
            }

            when (numberInResponse) {
                stubNumber -> null
                else -> "Expected response to contain $stubNumber, but instead got $numberInResponse"
            }
        } catch (e: Throwable) {
            exceptionCauseMessage(e)
        }
    }

    @Test
    fun `soft casting xml value to xml`() {
        val value = softCastValueToXML(StringValue("<xml>data</xml"))
        assertThat(value).isInstanceOf(XMLValue::class.java)
        assertThat(value.toStringLiteral()).isEqualTo("<xml>data</xml")
    }

    @Test
    fun `soft casting non xml value to xml`() {
        val value = softCastValueToXML(StringValue("not xml"))
        assertThat(value).isInstanceOf(StringValue::class.java)
        assertThat(value.toStringLiteral()).isEqualTo("not xml")
    }

    @Test
    fun `routing functions`() {
        assertThat(isFetchLogRequest(HttpRequest("GET", "/_$APPLICATION_NAME_LOWER_CASE/log"))).isTrue()
        assertThat(isFetchLoadLogRequest(HttpRequest("GET", "/_$APPLICATION_NAME_LOWER_CASE/load_log"))).isTrue()
        assertThat(isFetchContractsRequest(HttpRequest("GET", "/_$APPLICATION_NAME_LOWER_CASE/contracts"))).isTrue()
        assertThat(isExpectationCreation(HttpRequest("POST", "/_$APPLICATION_NAME_LOWER_CASE/expectations"))).isTrue()
        assertThat(isStateSetupRequest(HttpRequest("POST", "/_$APPLICATION_NAME_LOWER_CASE/state"))).isTrue()
    }

    private fun waitFor(threads: List<Thread>) = threads.forEach { it.join() }

    private fun start(threads: List<Thread>) = threads.forEach { it.start() }

    private fun invokeStub(i: Int, stub: HttpStub): HttpResponse {
        val request = HttpRequest(method = "POST", path = "/", formFields = mapOf("Data" to """{"number": $i}"""))
        val client = LegacyHttpClient(stub.endPoint)
        return client.execute(request)
    }

    private fun createExpectation(stubNumber: Int, stub: HttpStub): String? {
        val stubInfo = """
    {
      "http-request": {
        "method": "POST",
        "path": "/",
        "form-fields": {
          "Data": "{\"number\": $stubNumber}"
        }
      },
      "http-response": {
        "status": 200,
        "body": {"number": $stubNumber}
      }
    }
    """.trimIndent()

        val client = LegacyHttpClient(stub.endPoint, log = ::consoleLog)
        val request = HttpRequest("POST", path = "/_specmatic/expectations", body = parsedValue(stubInfo))
        val response = client.execute(request)

        return when(response.status) {
            200 -> null
            else -> {
                "Response for stub number $stubNumber:\n${response.toLogString()}"
            }
        }
    }

    @Test
    fun `request mismatch with contract triggers triggers custom errors`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: integer
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent(), "").toFeature()
        val response: HttpStubResponse = getHttpResponse(
            HttpRequest("POST", "/data", body = parsedJSON("""{"data": "abc123"}""")),
            listOf(contract),
            HttpExpectations(mutableListOf()), false
        ).response

        println(response.response.toLogString())

        assertThat(response.response.body.toStringLiteral()).contains("Contract expected")
        assertThat(response.response.body.toStringLiteral()).contains("request contained")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `response mismatch of externalized command response with contract triggers error`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          text/plain:
            schema:
              type: string
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: number
        """.trimIndent(), "").toFeature()
        val stub = HttpStubData(
            HttpRequest("POST", "/data", body = StringValue("Hello")).toPattern(),
            HttpResponse.ok(parsedJSONObject("""{"id": 10}""")).copy(externalisedResponseCommand = """echo {"status":200}"""),
            Resolver(),
            responsePattern = contract.scenarios.single().httpResponsePattern
        )

        assertThatThrownBy {
            getHttpResponse(HttpRequest("POST", "/data", body = StringValue("Hello")), listOf(contract), HttpExpectations(mutableListOf(stub)), false)
        }.satisfies(Consumer {
            it as ContractException

            val reportText = it.report()
            println(reportText)

            assertThat(reportText).contains("Contract expected")
            assertThat(reportText).contains("response from external command")
        })
    }

    @Test
    fun `stub request mismatch triggers custom errors`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          text/plain:
            schema:
              type: object
              properties:
                data: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
        """.trimIndent(), "").toFeature()
        val stub = HttpStubData(
            HttpRequest("POST", "/data", body = parsedJSON("""{"data": 10}""")).toPattern(),
            HttpResponse.ok("123"),
            Resolver(),
            responsePattern = contract.scenarios.single().httpResponsePattern
        )

        val response: HttpStubResponse = getHttpResponse(HttpRequest("POST", "/data", body = parsedJSON("""{"data": "abc"}""")), listOf(contract),
            HttpExpectations(mutableListOf(stub)),true).response
        val requestString = response.response.toLogString()

        println(requestString)

        assertThat(requestString).contains("Stub expected")
        assertThat(requestString).contains("request contained")
    }

    @Test
    fun `stub request mismatch should return custom error mismatch`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
        """.trimIndent(), "").toFeature()

        HttpStub(contract, emptyList()).use {
            val response = it.client.execute(HttpRequest("POST", "/_specmatic/expectations", body = StringValue("""
                {
                    "http-request": {
                        "method": "POST",
                        "path": "/data",
                        "body": {
                            "data": "abc"
                        }
                    },
                    "http-response": {
                        "status": 200,
                        "body": "10"
                    }
                }
            """.trimIndent())) )

            val responseString = response.toLogString()
            println(responseString)

            assertThat(responseString).contains("Contract expected number but stub contained \"abc\"")
        }
    }

    @Test
    fun `stub request mismatch should return custom error mismatch for payload level mismatch`() {
        val contract = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: array
              items:
                type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
        """.trimIndent(), "").toFeature()

        HttpStub(contract, emptyList()).use {
            val response = it.client.execute(HttpRequest("POST", "/data", body = StringValue("""hello world""".trimIndent())) )

            val responseString = response.toLogString()
            println(responseString)

            assertThat(responseString).contains("Contract expected")
            assertThat(responseString).contains("request contained")
        }
    }

    @Test
    fun `transient stubs can be loaded from the file system`() {
        createStubFromContracts(listOf("src/test/resources/openapi/contractWithTransientMock.yaml"), timeoutMillis = 0).use { stub ->
            with(stub.client.execute(HttpRequest("POST", "/test", body = parsedJSONObject("""{"item": "data"}""")))) {
                assertThat(this.body.toStringLiteral()).isEqualTo("success")
            }

            with(stub.client.execute(HttpRequest("POST", "/test", body = parsedJSONObject("""{"item": "data"}""")))) {
                assertThat(this.body.toStringLiteral()).isNotEqualTo("success")
            }
        }
    }

    @Test
    fun `multiple stubs for a non 200 with a value specified for a header will load and match incoming requests correctly`() {
        createStubFromContracts(listOf("src/test/resources/openapi/multiple400StubsWithHeader.yaml"), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/test", body = parsedJSONObject("""{"item": "data"}"""))

            with(stub.client.execute(request.copy(headers = mapOf("Authorization" to "valid")))) {
                assertThat(this.body.toStringLiteral()).isEqualTo("success")
            }

            with(stub.client.execute(request.copy(headers = mapOf("Authorization" to "invalid")))) {
                assertThat(this.body.toStringLiteral()).isEqualTo("failed")
            }
        }
    }

    @Test
    fun `stubs are loaded in order sorted by filename`() {
        createStubFromContracts(listOf("src/test/resources/openapi/contractWithOrderedStubs.yaml"), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/test", body = parsedJSONObject("""{"item": "data"}"""))

            with(stub.client.execute(request)) {
                assertThat(this.body.toStringLiteral()).isEqualTo("success 1")
            }

            with(stub.client.execute(request)) {
                assertThat(this.body.toStringLiteral()).isEqualTo("success 2")
            }
        }
    }

    @Test
    @Disabled("Sorting should happen on the files within the examples directory")
    fun `transient stubs are loaded in order sorted by filename across nested dirs where the first item in sorted order is the first item in the queue`() {
        createStubFromContracts(listOf("src/test/resources/openapi/contractWithOrderedStubsInNestedDirs.yaml"), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("POST", "/test", body = parsedJSONObject("""{"item": "data"}"""))

            (0..4).map { ctr ->
                with(stub.client.execute(request)) {
                    assertThat(this.body.toStringLiteral()).isEqualTo("success $ctr")
                }
            }
        }
    }

    @Test
    fun `test stub with scalar query parameter`() {
        val contract = OpenApiSpecification.fromYAML(
            """
    openapi: 3.0.0
    info:
      title: Sample API
      version: 0.1.9
    paths:
      /products:
        get:
          summary: get products
          description: Get products filtered by Brand Id
          parameters:
            - name: brand_id
              in: query
              required: true
              schema:
                type: number
          responses:
            '200':
              description: OK
              content:
                application/json:
                  schema:
                    type: string
""".trimIndent(), ""
        ).toFeature()

        HttpStub(contract, emptyList()).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("brand_id" to "1"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isNotEmpty
        }
    }

    @Test
    fun `should return a failure when a invalid baseUrls are provided`() {
        val specToBaseUrls = mapOf(
            // invalid scheme
            "spec1.yaml" to "httd://localhost:8080/api",
            "spec2.yaml" to "ftp://localhost:8080/api",
            // invalid port
            "spec3.yaml" to "http://localhost:99999/api",
            "spec4.yaml" to "http://localhost:not_a_port/api",
            // partials
            "spec5.yaml" to "localhost/api",
            "spec6.yaml" to "/api"
        )
        val result = validateBaseUrls(specToBaseUrls)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToIgnoringWhitespace("""
        >> Invalid baseURL "httd://localhost:8080/api" for spec1.yaml
        Please specify a valid URL in 'scheme://host[:port][path]' format
        >> Invalid baseURL "ftp://localhost:8080/api" for spec2.yaml
        Please specify a valid scheme / protocol (http or https)
        >> Invalid baseURL "http://localhost:99999/api" for spec3.yaml
        Please specify a valid port number
        >> Invalid baseURL "http://localhost:not_a_port/api" for spec4.yaml
        Please specify a valid URL in 'scheme://host[:port][path]' format
        >> Invalid baseURL "localhost/api" for spec5.yaml
        Please specify a valid URL in 'scheme://host[:port][path]' format
        >> Invalid baseURL "/api" for spec6.yaml
        Please specify a valid URL in 'scheme://host[:port][path]' format
        """.trimIndent())
    }

    @Test
    fun `should not complain when a valid baseUrls are provided`() {
        val specToBaseUrls = mapOf(
            "spec1.yaml" to "http://localhost:9000/api",
            "spec2.yaml" to "https://localhost:9000/api",
            "spec3.yaml" to "http://localhost/api",
            "spec4.yaml" to "https://localhost/api",
            "spec5.yaml" to "http://localhost",
            "spec6.yaml" to "https://localhost"
        )
        val result = validateBaseUrls(specToBaseUrls)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should extract host from url`() {
        extractHost("http://localhost:8080/api").let {
            assertThat(it).isEqualTo("localhost")
        }
    }

    @Test
    fun `should extract port from url`() {
        extractPort("http://localhost:8080/api").let {
            assertThat(it).isEqualTo(8080)
        }
    }

    @Test
    fun `should default to port 80 if the port is not found in the base url`() {
        extractPort("http://localhost/api").let {
            assertThat(it).isEqualTo(80)
        }
    }
}
