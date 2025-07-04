package io.specmatic.core

import io.mockk.every
import io.mockk.mockk
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.discriminator.DiscriminatorBasedItem
import io.specmatic.core.discriminator.DiscriminatorMetadata
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.*
import io.specmatic.stub.captureStandardOutput
import io.specmatic.stub.createStubFromContracts
import io.specmatic.test.ScenarioTestGenerationException
import io.specmatic.test.ScenarioTestGenerationFailure
import io.specmatic.test.TestExecutor
import io.specmatic.trimmedLinesList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.*
import java.util.function.Consumer
import java.util.stream.Stream

class FeatureTest {
    @Test
    fun `test descriptions with no tags should contain no tag separators`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - name
                - sku
              properties:
                name:
                  type: string
                sku:
                  type: string
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.generateContractTestScenarios(emptyList()).toList().map { it.second.value }

        assertThat(scenarios.map { it.testDescription() }).allSatisfy {
            assertThat(it).doesNotContain("|")
        }
    }

    @Test
    fun `test descriptions with generative tests on should contain the type`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
        content:
          application/json:
            examples:
              SUCCESS:
                value:
                  name: abc
                  sku: "123"
            schema:
              type: object
              required:
                - name
                - sku
              properties:
                name:
                  type: string
                sku:
                  type: string
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.enableGenerativeTesting().generateContractTestScenarios(emptyList()).toList()
                .map { it.second.value }

        assertThat(scenarios.map { it.testDescription() }).allSatisfy {
            assertThat(it).containsAnyOf("+ve", "-ve")
        }
    }

    @Test
    fun `test output should contain example name`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
        content:
          application/json:
            examples:
              SUCCESS:
                value:
                  name: abc
                  sku: "123"
            schema:
              type: object
              required:
                - name
                - sku
              properties:
                name:
                  type: string
                sku:
                  type: string
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
""".trimIndent(), ""
        ).toFeature()

        val scenario: Scenario =
            contract.generateContractTestScenarios(emptyList()).toList().map { it.second.value }.first()
        assertThat(scenario.testDescription()).contains("SUCCESS")
    }

    @Test
    fun `test output should contain example name and preserve WIP tag`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
        content:
          application/json:
            examples:
              "[WIP] SUCCESS":
                value:
                  name: abc
                  sku: "123"
            schema:
              type: object
              required:
                - name
                - sku
              properties:
                name:
                  type: string
                sku:
                  type: string
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                "[WIP] SUCCESS":
                  value:
                    id: 10
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
""".trimIndent(), ""
        ).toFeature()

        val scenario: Scenario =
            contract.generateContractTestScenarios(emptyList()).toList().map { it.second.value }.first()
        assertThat(scenario.testDescription()).contains("[WIP] SUCCESS")
    }
    @DisplayName("Single Feature Contract")
    @ParameterizedTest
    @MethodSource("singleFeatureContractSource")
    @Throws(Throwable::class)
    fun `should lookup a single feature contract`(gherkinData: String?, accountId: String?, expectedBody: String?) {
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/balance").updateQueryParam("account-id", accountId ?: "")
        val contractBehaviour = parseGherkinStringToFeature(gherkinData!!)
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(200)
        val responseBodyJSON = JSONObject(httpResponse.body.displayableValue())
        val expectedBodyJSON = JSONObject(expectedBody)
        assertThat(responseBodyJSON.getInt("calls_left")).isEqualTo(expectedBodyJSON.getInt("calls_left"))
        assertThat(responseBodyJSON.getInt("messages_left")).isEqualTo(expectedBodyJSON.getInt("messages_left"))
    }

    @Test
    @Throws(Throwable::class)
    fun `should return bad request on unmatched path in url`() {
        val contractGherkin = """
                Feature: Contract for /balance API
                  Scenario: Get account balance
                    When GET /balance?account-id=(number)
                    Then status 200
                    And response-body {calls_left: 10, messages_left: 30}
                """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/balance2").updateQueryParam("account-id", "10")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(400)
        assertThat(httpResponse.body.toStringLiteral()).isEqualTo("No matching REST stub or contract found for method GET and path /balance2")
    }

    @Test
    @Throws(Throwable::class)
    fun `should return bad request when request headers do not match`() {
        val contractGherkin = """
                Feature: Contract for /balance API
                  Scenario: Get balance info
                    When GET /balance
                    And request-header x-loginId (string)
                    Then status 200
                    And response-body {calls_left: 10, messages_left: 30}
                """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/balance").updateHeader("y-loginId", "abc123")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(400)
        assertThat(httpResponse.body.toStringLiteral().trimmedLinesList()).isEqualTo(
            """
            In scenario "Get balance info"
            API: GET /balance -> 200
            
              >> REQUEST.PARAMETERS.HEADER.x-loginId
              
                 Expected header named "x-loginId" was missing
              """.trimIndent().trimmedLinesList())
    }

    @Test
    @Throws(Throwable::class)
    fun `should generate response headers`() {
        val contractGherkin = """
                Feature: Contract for /balance API
                  Scenario: api call
                    When GET /balance
                    Then status 200
                    And response-header token test
                    And response-header length (number)
                    And response-body {calls_left: 10, messages_left: 30}
                """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/balance")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(200)
        assertThat(httpResponse.headers["token"]).isEqualTo("test")
        assertThat(httpResponse.headers["length"]).matches("[0-9]+")
    }

    @Test
    @Throws(Throwable::class)
    fun `should match integer token in query params`() {
        val contractGherkin = """
                Feature: Contract for /balance API
                  Scenario: api call
                    When GET /balance?account-id=(number)
                    Then status 200
                    And response-body {calls_left: 10, messages_left: 30}
                """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/balance").updateQueryParam("account-id", "10")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(200)
        val responseBodyJSON = JSONObject(httpResponse.body.displayableValue())
        assertThat(responseBodyJSON.getInt("calls_left")).isEqualTo(10)
        assertThat(responseBodyJSON.getInt("messages_left")).isEqualTo(30)
    }

    @Test
    @Throws(Throwable::class)
    fun `should match JSON array in request`() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    When POST /balance\n" +
                "    And request-body {calls_made: [3, 10, \"(number)\"]}\n" +
                "    Then status 200"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/balance").updateBody("{calls_made: [3, 10, 2]}")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertEquals(200, httpResponse.status)
    }

    @Test
    @Throws(Throwable::class)
    fun `should return bad request on unmatched JSON array in request`() {
        val contractGherkin = """
                Feature: Contract for /balance API
                  Scenario: Update balance
                    When POST /balance
                    And request-body {calls_made: [3, 10, 2]}
                    Then status 200
                """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/balance").updateBody("{calls_made: [3, 10]}")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(400)
        assertThat(httpResponse.body.toStringLiteral().trimmedLinesList()).isEqualTo(
            """
            In scenario "Update balance"
            API: POST /balance -> 200
            
              >> REQUEST.BODY.calls_made
              
                 Expected an array of length 3, actual length 2
            """.trimIndent().trimmedLinesList())
    }

    @Test
    @Throws(Throwable::class)
    fun `should return bad request on wrong type in JSON array in request`() {
        val contractGherkin = """
                Feature: Contract for /balance API
                  Scenario: Update balance
                    When POST /balance
                    And request-body {"calls_made": [3, 10, "(number)"]}
                    Then status 200
                """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/balance").updateBody("{calls_made: [3, 10, \"test\"]}")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(400)
        assertThat(httpResponse.body.toStringLiteral().trimmedLinesList()).isEqualTo(
            """
            In scenario "Update balance"
            API: POST /balance -> 200
            
              >> REQUEST.BODY.calls_made[2]
              
                 Expected number, actual was "test"
            """.trimIndent().trimmedLinesList())
    }

    @Test
    @Throws(Throwable::class)
    fun floatRecognisedInQueryParams() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    When GET /balance?account-id=(number)\n" +
                "    Then status 200\n" +
                "    And response-body {calls_left: 10, messages_left: 30}"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/balance").updateQueryParam("account-id", "10.1")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        val responseBodyJSON = JSONObject(httpResponse.body.displayableValue())
        assertEquals(10, responseBodyJSON.getInt("calls_left"))
        assertEquals(30, responseBodyJSON.getInt("messages_left"))
    }

    @Test
    @Throws(Throwable::class)
    fun `should return bad request when Integer token does not match in query params`() {
        val contractGherkin = """
                Feature: Contract for /balance API\
                  Scenario: Get account balance
                    When GET /balance?account-id=(number)
                    Then status 200
                    And response-body {calls_left: 10, messages_left: 30}
                """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updatePath("/balance").updateQueryParam("account-id", "abc").updateMethod("GET")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertThat(httpResponse.status).isEqualTo(400)
        assertThat(httpResponse.body.toStringLiteral().trimmedLinesList()).isEqualTo(
            """
            In scenario "Get account balance"
            API: GET /balance -> 200
            
              >> REQUEST.PARAMETERS.QUERY.account-id
              
                 Expected number, actual was "abc"
            """.trimIndent().trimmedLinesList())
    }

    @Test
    @Throws(Throwable::class)
    fun matchStringTokenInQueryParams() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    When GET /balance?account-id=(string)\n" +
                "    Then status 200\n" +
                "    And response-body {calls_left: 10, messages_left: 30}"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updatePath("/balance").updateQueryParam("account-id", "abc").updateMethod("GET")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        val responseBody = JSONObject(httpResponse.body.displayableValue())
        assertEquals(10, responseBody.getInt("calls_left"))
        assertEquals(30, responseBody.getInt("messages_left"))
    }

    @Test
    @Throws(Throwable::class)
    fun matchPOSTJSONBody() {
        val contractGherkin = """
Feature: Contract for /balance API

  Scenario: api call

    When POST /accounts
    And request-body {name: "(string)", address: "(string)"}
    Then status 200
    And response-body {calls_left: "(number)", messages_left: "(number)"}"""
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/accounts").updateBody("{name: \"Holmes\", address: \"221 Baker Street\"}")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertEquals(200, httpResponse.status)
        val actual = JSONObject(httpResponse.body.displayableValue())
        assertNotNull(httpResponse)
        assertTrue(actual["calls_left"] is Int)
        assertTrue(actual["messages_left"] is Int)
    }

    @Test
    @Throws(Throwable::class)
    fun matchMultipleScenarios() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    When GET /balance?id=(number)\n" +
                "    Then status 200\n" +
                "    And response-body {calls_left: \"(number)\", messages_left: \"(number)\"}\n\n" +
                "  Scenario: api call\n\n" +
                "    When POST /accounts\n" +
                "    And request-body {name: \"(string)\", address: \"(string)\"}\n" +
                "    Then status 200\n"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        var httpRequest: HttpRequest = HttpRequest().updateMethod("GET").updatePath("/balance").updateQueryParam("id", "100")
        var httpResponse: HttpResponse = contractBehaviour.lookupResponse(httpRequest)
        val jsonResponse = JSONObject(httpResponse.body.displayableValue())
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        assertTrue(jsonResponse["calls_left"] is Int)
        assertTrue(jsonResponse["messages_left"] is Int)
        httpRequest = HttpRequest().updateMethod("POST").updatePath("/accounts").updateBody("{name: \"Holmes\", address: \"221 Baker Street\"}")
        httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        assertTrue(httpResponse.body.toStringLiteral().isEmpty())
    }

    @Test
    @Throws(Throwable::class)
    fun scenarioMatchesWhenFactIsSetupWithoutFixtureData() {
        val contractGherkin = """Feature: Contract for /locations API
  Scenario Outline: api call
    * fixture city_list {"cities": [{"city": "Mumbai"}, {"city": "Bangalore"}]}
    * pattern City {"city": "(string)"}
    * pattern Cities {"cities": ["(City...)"]}
    Given fact cities_exist 
    When GET /locations
    Then status 200
    And response-body (Cities)
  Examples:
  | cities_exist | 
  | city_list | 
    """
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpResponse: HttpResponse
        val httpRequest: HttpRequest = HttpRequest().updateMethod("GET").updatePath("/locations")
        contractBehaviour.setServerState(object : HashMap<String, Value>() {
            init {
                put("cities_exist", True)
            }
        })
        httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        val responseBody = JSONObject(httpResponse.body.displayableValue())
        val cities = responseBody.getJSONArray("cities")
        for (i in 0 until cities.length()) {
            val city = cities.getJSONObject(i)
            assertTrue(city.getString("city").isNotEmpty())
        }
    }

    @Test
    @Throws(Throwable::class)
    fun scenarioOutlineRunsLikeAnEmptyScenario() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario Outline: api call\n\n" +
                "    When POST /accounts\n" +
                "    And request-body {name: \"(string)\", address: \"(string)\"}\n" +
                "    Then status 200\n" +
                "    And response-body {calls_left: \"(number)\", messages_left: \"(number)\"}"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/accounts").updateBody("{name: \"Holmes\", address: \"221 Baker Street\"}")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        val actual = JSONObject(httpResponse.body.displayableValue())
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        assertTrue(actual["calls_left"] is Int)
        assertTrue(actual["messages_left"] is Int)
    }

    @Test
    @Throws(Throwable::class)
    fun `Background reflects in multiple scenarios`() {
        val contractGherkin = """
            Feature: Contract for /balance API
            
              Background:
                * pattern Person {name: "(string)", address: "(string)"}
                * pattern Info {calls_left: "(number)", messages_left: "(number)"}
            
              Scenario: api call
                When POST /accounts1
                And request-body (Person)
                Then status 200
                And response-body (Info)
            
              Scenario: api call
                When POST /accounts2
                And request-body (Person)
                Then status 200
                And response-body (Info)
            """

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        val test = { httpRequest: HttpRequest ->
            val httpResponse = contractBehaviour.lookupResponse(httpRequest)
            assertEquals(200, httpResponse.status)
            val actual = JSONObject(httpResponse.body.displayableValue())
            assertTrue(actual["calls_left"] is Int)
            assertTrue(actual["messages_left"] is Int)
        }

        val baseRequest = HttpRequest().updateMethod("POST").updateBody("{name: \"Holmes\", address: \"221 Baker Street\"}")

        for (path in listOf("/accounts1", "/accounts2")) {
            test(baseRequest.updatePath(path))
        }
    }

    @Test
    fun `Contract Fake state can be setup using true without specifying a concrete value`() {
        val contractGherkin = """
            Feature: Contract for /balance API
            
              Scenario Outline: api call
            
                Given fact id 10
                When GET /accounts/(id:number)
                Then status 200
                And response-body {"name": "(string)"}"""

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        contractBehaviour.setServerState(mutableMapOf<String, Value>().apply {
            this["id"] = True
        })

        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/accounts/10")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        val actual = JSONObject(httpResponse.body.displayableValue())
        assertTrue(actual.has("name"))
        assertNotNull(actual.get("name"))
    }

    @Test
    fun `Contract Fake state can be setup using a concrete value`() {
        val contractGherkin = """
            Feature: Contract for /balance API
            
              Scenario: api call
                Given fact id 10
                When GET /accounts/(id:number)
                Then status 200
                And response-body {"name": "(string)"}"""

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        contractBehaviour.setServerState(mutableMapOf<String, Value>().apply {
            this["id"] = NumberValue(10)
        })

        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/accounts/10")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        val actual = JSONObject(httpResponse.body.displayableValue())
        assertTrue(actual.has("name"))
        assertNotNull(actual.get("name"))
    }

    @Test
    fun `Contract should be able to represent a number in the request and response body`() {
        val contractGherkin = """
            Feature: Contract for /account API
            
              Scenario: api call
                When POST /account
                  And request-body (number)
                Then status 200
                  And response-body (number)"""

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/account").updateBody("10")
        val httpResponse = contractBehaviour.lookupResponse(httpRequest)
        assertNotNull(httpResponse)
        assertEquals(200, httpResponse.status)
        assertTrue( NumberPattern().matches(NumberValue(httpResponse.body.toStringLiteral().toInt()), Resolver()) is Result.Success)
    }

    @Test
    fun `Contract should parse tabular data and match a json object against it`() {
        val contractGherkin = """
            Feature: Contract for /user API
            
            Background:
            Given pattern User
            | id   | (number) |
            | name | (string) |
            
            Scenario: update user info
            When POST /user
                And request-body (User)
            Then status 200
        """.trimIndent()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/user").updateBody("""{"id": 10, "name": "John Doe"}""")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `Contract should match an array of json objects specified in the request`() {
        val contractGherkin = """
            Feature: Contract for /user API
            
            Background:
            Given pattern User
            | id   | (number) |
            | name | (string) |
            
            Scenario: update info of multiple users
            When POST /user
                And request-body (User*)
            Then status 200
        """.trimIndent()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/user").updateBody("""[{"id": 10, "name": "John Doe"}, {"id": 20, "name": "Jane Doe"}]""")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `Contract should match an array of json objects specified as a pattern`() {
        val contractGherkin = """
            Feature: Contract for /user API
            
            Background:
            Given pattern User
            | id   | (number) |
            | name | (string) |
            And pattern Users (User*)
            
            Scenario: update info of multiple users
            When POST /user
                And request-body (Users)
            Then status 200
        """.trimIndent()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("POST").updatePath("/user").updateBody("""[{"id": 10, "name": "John Doe"}, {"id": 20, "name": "Jane Doe"}]""")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `Contract should generate a fake json response from a tabular pattern`() {
        val contractGherkin = """
            Feature: Contract for /user API
            
            Background:
            Given pattern User
            | id   | (number) |
            | name | (string) |
            
            Scenario: Get info about a user
            When GET /user/(id:number)
            Then status 200
                And response-body (User)
        """.trimIndent()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/user/10")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `Contract should generate a fake json list response from a tabular pattern`() {
        val contractGherkin = """
            Feature: Contract for /user API
            
            Background:
            Given pattern User
            | id   | (number) |
            | name | (string) |
            
            Scenario: Get info about a user
            When GET /user
            Then status 200
                And response-body (User*)
        """.trimIndent()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/user")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
        val array = httpResponse.body

        assertTrue(array is JSONArrayValue)

        if(array is JSONArrayValue) {
            for(value in array.list) {
                assertTrue(value is JSONObjectValue)

                if(value is JSONObjectValue) {
                    assertTrue(value.jsonObject.getValue("id") is NumberValue)
                    assertTrue(value.jsonObject.getValue("name") is StringValue)
                }
            }
        }
    }

    @Test
    fun `Contract should generate a fake json response for a list as part of a json object`() {
        val contractGherkin = """
            Feature: Contract for /user API
            
            Background:
            Given pattern UserData
            | ids   | (number*) |
            
            Scenario: Get info about a user
            When GET /userdata
            Then status 200
                And response-body (UserData)
        """.trimIndent()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/userdata")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
        val jsonObject = httpResponse.body

        assertTrue(jsonObject is JSONObjectValue)
        if(jsonObject is JSONObjectValue) {
            val ids = jsonObject.jsonObject.getValue("ids")
            assert(ids is JSONArrayValue)

            if(ids is JSONArrayValue) {
                for (value in ids.list) {
                    assertTrue(value is NumberValue)
                }
            }
        }
    }

    @Test
    fun `json keyword acts like pattern but reads tabular syntax`() {
        val contractGherkin = """
            Feature: Contract for /user API
            
            Background:
            Given json UserData
            | ids   | (number*) |
            
            Scenario: Get info about a user
            When GET /userdata
            Then status 200
                And response-body (UserData)
        """.trimIndent()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest().updateMethod("GET").updatePath("/userdata")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
        val jsonObject = httpResponse.body

        assertTrue(jsonObject is JSONObjectValue)
        assertTrue(if(jsonObject is JSONObjectValue) {
            val ids = jsonObject.jsonObject["ids"] ?: EmptyString
            assertTrue(ids is JSONArrayValue)

            if(ids is JSONArrayValue) {
                for (value in ids.list) {
                    assertTrue(value is NumberValue)
                }

                true
            } else false
        } else false)
    }

    @Test
    fun `successfully matches valid form fields`() {
        val requestPattern = HttpRequestPattern(HttpHeadersPattern(), formFieldsPattern = mapOf("Data" to NumberPattern()))
        val request = HttpRequest().copy(formFields = mapOf("Data" to "10"))
        assertTrue(requestPattern.matchFormFields(Triple(request, Resolver(), emptyList())) is MatchSuccess)
    }

    @Test
    fun `returns error for form fields`() {
        val requestPattern = HttpRequestPattern(HttpHeadersPattern(), formFieldsPattern =  mapOf("Data" to NumberPattern()))
        val request = HttpRequest().copy(formFields = mapOf("Data" to "hello"))
        val result: MatchingResult<Triple<HttpRequest, Resolver, List<Result.Failure>>> = requestPattern.matchFormFields(Triple(request, Resolver(), emptyList()))
        result as MatchSuccess<Triple<HttpRequest, Resolver, List<Result.Failure>>>
        val failures = result.value.third
        assertThat(failures).hasSize(1)
    }

    @Test
    fun `form fields pattern are recognised and matched`() {
        val contractGherkin = """
Feature: Math API

Scenario: Square a number
When POST /squareOf
    And form-field number (number)
Then status 200
    And response-body (number)
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="POST", path="/squareOf", formFields=mapOf("number" to "10"))
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
        assertTrue(httpResponse.body is NumberValue)
    }

    @Test
    fun `form fields pattern errors are recognised and results in a 400 http error`() {
        val contractGherkin = """
Feature: Math API

Scenario: Square a number
When POST /squareOf
    And form-field number (number)
Then status 200
    And response-body (number)
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="POST", path="/squareOf", formFields=mapOf("number" to "hello"))
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(400, httpResponse.status)
    }

    @Test
    fun `incorrectly formatted json array passed in a form field returns a 400 error`() {
        val contractGherkin = """
Feature: Math API

Scenario: Product of a number
Given json Numbers ["(number)", "(number)"]
When POST /product
    And form-field number (Numbers)
Then status 200
    And response-body (number)
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="POST", path="/product", formFields=mapOf("number" to "[1]"))
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(400, httpResponse.status)
    }

    @Test
    fun `json array is recognised when passed in a form field`() {
        val contractGherkin = """
Feature: Math API

Scenario: Product of a number
Given json Numbers ["(number)", "(number)"]
When POST /product
    And form-field number (Numbers)
Then status 200
    And response-body (number)
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="POST", path="/product", formFields=mapOf("number" to "[1, 2]"))
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `json object is recognised when passed in a form field`() {
        val contractGherkin = """
Feature: Math API

Scenario: Product of a number
Given json Numbers
| val1 | (number) |
| val2 | (number) |
When POST /product
    And form-field number (Numbers)
Then status 200
    And response-body (number)
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="POST", path="/product", formFields=mapOf("number" to """{"val1": 10, "val2": 20}"""))
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `incorrectly formatted json object passed in a form field returns a 400 error`() {
        val contractGherkin = """
Feature: Math API

Scenario: Product of a number
Given json Numbers
| val1 | (number) |
| val2 | (number) |
When POST /product
    And form-field number (Numbers)
Then status 200
    And response-body (number)
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="POST", path="/product", formFields=mapOf("number" to """{"val1": 10, "val2": 20}"""))
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `Contract fake should match a json object matching a dictionary pattern`() {
        val contractGherkin = """
Feature: Order API

Scenario: Order products
Given json Quantities (dictionary string number)
When POST /order
    And request-body (Quantities)
Then status 200
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="POST", path="/order", body = JSONObjectValue(mapOf("soap" to NumberValue(2), "toothpaste" to NumberValue(3))))
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
    }

    @Test
    fun `Contract fake should generate a json object matching a dictionary pattern`() {
        val contractGherkin = """
Feature: Order API

Scenario: Order products
Given json Quantities (dictionary string number)
When GET /order
Then status 200
    And response-body (Quantities)
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="GET", path="/order")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
        val json = httpResponse.body

        if(json !is JSONObjectValue) fail("Expected JSONObjectValue")

        for((key, value) in json.jsonObject) {
            assertThat(key.length).isPositive()
            assertThat(value).isInstanceOf(NumberValue::class.java)
        }
    }

    @Test
    fun `should generate number in string`() {
        val contractGherkin = """
Feature: Number API

Scenario: Random number
When GET /number
Then status 200
And response-body
| number | (number in string) |
""".trim()

        val behaviour = parseGherkinStringToFeature(contractGherkin)
        val httpRequest = HttpRequest(method="GET", path="/number")
        val httpResponse = behaviour.lookupResponse(httpRequest)

        assertEquals(200, httpResponse.status)
        val json = httpResponse.body

        if(json !is JSONObjectValue) fail("Expected json object")

        assertThat(json.jsonObject.getValue("number")).isInstanceOf(StringValue::class.java)
        assertDoesNotThrow {
            json.jsonObject.getValue("number").toStringLiteral().toInt()
        }
    }

    private val threeQuotes = "\"\"\""

    @Test
    fun `basic xml scenario`() {
        val contractGherkin = """
Feature: Basic XML API

Scenario: Random number
When POST /number
And request-body
$threeQuotes
<request>
<number>(number)</number>
</request>
$threeQuotes
Then status 200
""".trim()

        val feature = parseGherkinStringToFeature(contractGherkin)

        fun test(request: HttpRequest) {
            try {
                val response = feature.lookupResponse(request)
                assertThat(response.status).isEqualTo(200).withFailMessage(response.toLogString())
            } catch(e: Throwable) {
                println(e.stackTrace)
            }
        }

        test(HttpRequest("POST", path = "/number", body = toXMLNode("""<request><number>10</number></request>""")))
        test(HttpRequest("POST", path = "/number", body = toXMLNode("""<request>
        <number>10</number> </request>""")
        ))
    }

    @Test
    fun `only one test is generated when all fields exist in the example and the generative flag is off`() {
        val contract = parseGherkinStringToFeature(
            """
            Feature: Test
                Background:
                    Given openapi openapi/three_keys_one_mandatory.yaml
                    
                Scenario: Test
                    When POST /data
                    Then status 200
                    
                    Examples:
                    | id | name   |
                    | 10 | Justin |
        """.trimIndent(), "src/test/resources/test.spec"
        )

        val testScenarios: List<Scenario> =
            contract.generateContractTestScenarios(emptyList()).toList().map { it.second.value }
        assertThat(testScenarios).hasSize(1)
    }

    @Test
    fun `test generates no negative tests for a string header parameter`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    get:
      summary: Get Product
      description: Get Product
      parameters:
        - in: header
          name: X-Request-ID
          schema:
            type: string
          examples:
            SUCCESS:
              value: 'abc'
              
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
                    name: 'Product10'
              schema:
                type: object
                required:
                  - id
                  - name
                properties:
                  id:
                    type: integer
                  name:
                    type: string
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.enableGenerativeTesting().generateContractTestScenarios(emptyList()).toList()
                .map { it.second.value }
        assertThat(scenarios.count { it.testDescription().contains("-ve") }).isEqualTo(0)
    }

    @Test
    fun `test generates 1 negative test with string pattern for an integer header parameter`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    get:
      summary: Get Product
      description: Get Product
      parameters:
        - in: header
          name: X-Request-ID
          schema:
            type: integer
          examples:
            SUCCESS:
              value: 123
              
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
                    name: 'Product10'
              schema:
                type: object
                required:
                  - id
                  - name
                properties:
                  id:
                    type: integer
                  name:
                    type: string
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.enableGenerativeTesting().generateContractTestScenarios(emptyList()).toList()
                .map { it.second.value }
        val negativeTestScenarios = scenarios.filter { it.testDescription().contains("-ve") }
        assertThat(negativeTestScenarios.count()).isEqualTo(2)
        val headerPattern = negativeTestScenarios.first().httpRequestPattern.headersPattern.pattern
        assertThat(headerPattern.values.first() is StringPattern)
    }

    @Test
    fun `test generates no negative tests for a boolean header parameter`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    get:
      summary: Get Product
      description: Get Product
      parameters:
        - in: header
          name: X-Request-ID
          schema:
            type: boolean
          examples:
            SUCCESS:
              value: true
              
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
                    name: 'Product10'
              schema:
                type: object
                required:
                  - id
                  - name
                properties:
                  id:
                    type: integer
                  name:
                    type: string
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.enableGenerativeTesting().generateContractTestScenarios(emptyList()).toList()
                .map { it.second.value }
        val negativeTestScenarios = scenarios.filter { it.testDescription().contains("-ve") }
        assertThat(negativeTestScenarios.count()).isEqualTo(0)
    }

    @Test
    fun `test generates 8 negative tests for 2 integer header parameters and 2 body parameters`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      parameters:
        - in: header
          name: X-Request-ID
          schema:
            type: integer
          required: true
          examples:
            SUCCESS:
              value: 123
              
        - in: header
          name: X-Request-Code
          schema:
            type: integer
          required: true
          examples:
            SUCCESS:
              value: 456
              
      requestBody:
        content:
          application/json:
            examples:
              SUCCESS:
                value:
                  name: 'abc'
                  sku: 'sku'
            schema:
              type: object
              required:
                - name
                - sku
              properties:
                name:
                  type: string
                sku:
                  type: string
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
                    name : 'Product10'
              schema:
                type: object
                required:
                  - id
                  - name
                properties:
                  id:
                    type: integer
                  name:
                    type: string
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.enableGenerativeTesting().generateContractTestScenarios(emptyList()).toList()
                .map { it.second.value }
        val negativeTestScenarios = scenarios.filter { it.testDescription().contains("-ve") }
        assertThat(negativeTestScenarios.count()).isEqualTo(12)
    }

    @Test
    fun `negative tests should show a descriptor in the title`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
        content:
          application/json:
            examples:
              SUCCESS:
                value:
                  name: 'abc'
            schema:
              type: object
              required:
                - name
              properties:
                name:
                  type: string
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.enableGenerativeTesting().generateContractTestScenarios(emptyList()).toList()
                .map { it.second.value }
        val negativeTestScenarios = scenarios.filter { it.testDescription().contains("-ve") }
        assertThat(negativeTestScenarios.map { it.testDescription() }).allSatisfy {
            assertThat(it).contains("-> 4xx")
        }

        negativeTestScenarios.zip((1..negativeTestScenarios.size).toList()).forEach { (scenario, _) ->
            assertThat(scenario.testDescription()).contains("4xx [REQUEST.BODY.name string mutated to")
        }
    }

    @Test
    fun `negative tests should show the descriptorFromPlugin in the title if it exists in the scenario`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
        content:
          application/json:
            examples:
              SUCCESS:
                value:
                  name: 'abc'
            schema:
              type: object
              required:
                - name
              properties:
                name:
                  type: string
      responses:
        '200':
          description: Returns Product With Id
          content:
            application/json:
              examples:
                SUCCESS:
                  value:
                    id: 10
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
""".trimIndent(), ""
        ).toFeature()

        val scenarios: List<Scenario> =
            contract.enableGenerativeTesting().generateContractTestScenarios(emptyList()).toList()
                .map {
                    it.second.value
                }.map {
                    it.copy(descriptionFromPlugin = "scenario custom description")
                }
        val negativeTestScenarios = scenarios.filter { it.testDescription().contains("-ve") }
        assertThat(negativeTestScenarios.map { it.testDescription() }).allSatisfy {
            assertThat(it).contains("scenario custom description ")
        }
    }

    @Test
    fun `positive examples of 4xx should be able to have non-string non-spec-conformant examples`() {
        val specification = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - name
              properties:
                name:
                  type: string
            examples:
              SUCCESS:
                value:
                  name: 'abc'
              BAD_REQUEST_NUMBER:
                value:
                  name: 10
              BAD_REQUEST_NULL:
                value:
                  name: null
      responses:
        '200':
          description: Returns Id
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value: 10
        '422':
          description: Bad Request
          content:
            text/plain:
              schema:
                type: string
              examples:
                BAD_REQUEST_NUMBER:
                  value: "Bad request was received and could not be handled"
                BAD_REQUEST_NULL:
                  value: "Bad request was received and could not be handled"
""".trimIndent(), "").toFeature()

        val results = specification.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                print(request.toLogString())
                return when(val body = request.body) {
                    is JSONObjectValue -> {
                        if(body.jsonObject["name"] is StringValue) {
                            HttpResponse.ok("10")
                        } else {
                            HttpResponse(422, "Bad request was received and could not be handled")
                        }
                    }

                    else -> HttpResponse(422, "Bad request was received and could not be handled")
                }
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.success()).isTrue()
        assertThat(results.failureCount).isZero()
    }

    @Test
    fun `errors during test realisation should bubble up via results`() {
        val feature = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample Pet API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /pet:
    post:
      summary: Add Pet
      requestBody:
        content:
          application/json:
            schema:
              ${"$"}ref: '#/components/schemas/NewPet'
      responses:
        '200':
          description: Returns Id
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value: 10
components:
  schemas:
    NewPet:
      type: object
      required:
        - name
        - related
      properties:
        name:
          type: string
        related:
          ${"$"}ref: '#/components/schemas/NewPet'
""".trimIndent(), "").toFeature()

        val contractTests = feature.generateContractTests(emptyList()).toList()
        assertThat(contractTests).hasSize(1)

        val contractTest = contractTests.single()
        assertThat(contractTest).isInstanceOf(ScenarioTestGenerationException::class.java)

        val (result, httpResponse) = contractTest.runTest("", 0)
        assertThat(httpResponse).isNull()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }


    @Test
    fun `errors during test sequence generation should interrupt sequence generation and return a single error via results`() {
        val feature = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: Sample Pet API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /pet:
    post:
      summary: Add Pet
      requestBody:
        content:
          application/json:
            schema:
              ${"$"}ref: '#/components/schemas/NewPet'
            examples:
              SUCCESS:
                value:
                  name: 10
      responses:
        '200':
          description: Returns Id
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value: 10
components:
  schemas:
    NewPet:
      type: object
      required:
        - name
      properties:
        name:
          type: string
""".trimIndent(), "").toFeature()

        val contractTests = feature.generateContractTests(emptyList()).toList()
        assertThat(contractTests).hasSize(1)

        val contractTest = contractTests.single()
        assertThat(contractTest).isInstanceOf(ScenarioTestGenerationFailure::class.java)

        val (result, httpResponse) = contractTest.runTest("", 0)
        assertThat(httpResponse).isNull()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }


    @Test
    fun `invalid request body example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
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
            examples:
              200_OK:
                value:
                  data: "abc"
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.BODY.data")
        })
    }

    @Test
    fun `invalid mandatory request header example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    parameters:
      - in: header
        name: X-Test-Header
        schema:
          type: number
        required: true
        examples:
          200_OK:
            value: "abc"
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.HEADER.X-Test-Header")
        })
    }

    @Test
    fun `invalid optional request header example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    parameters:
      - in: header
        name: X-Test-Header
        schema:
          type: number
        examples:
          200_OK:
            value: "abc"
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.HEADER.X-Test-Header")
            assertThat(exceptionCauseMessage(exception)).doesNotContain("REQUEST.PARAMETERS.HEADER.X-Test-Header?")
        })
    }

    @Test
    fun `invalid mandatory query parameter example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    parameters:
      - in: query
        name: enabled
        schema:
          type: boolean
        required: true
        examples:
          200_OK:
            value: "abc"
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.QUERY.enabled")
        })
    }

    @Test
    fun `invalid optional query parameter example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    parameters:
      - in: query
        name: enabled
        schema:
          type: boolean
        examples:
          200_OK:
            value: "abc"
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.QUERY.enabled")
        })
    }

    @Test
    fun `invalid mandatory response header example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
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
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          headers:
            X-Value:
              schema:
                type: integer
              required: true
              examples:
                200_OK:
                  value:
                    "abc"
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("RESPONSE.HEADER.X-Value")
        })
    }

    @Test
    fun `invalid path parameter example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data/{id}:
    post:
      summary: hello world
      description: test
      parameters:
        - name: id
          in: path
          schema:
            type: integer
          examples:
            200_OK:
              value: "abc"
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
              examples:
                200_OK:
                  value: 10
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.PATH.id")
        })
    }

    @Test
    fun `invalid optional response header example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
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
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          headers:
            X-Value:
              schema:
                type: integer
              examples:
                200_OK:
                  value:
                    "abc"
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("RESPONSE.HEADER.X-Value")
            assertThat(exceptionCauseMessage(exception)).doesNotContain("RESPONSE.HEADER.X-Value?")
        })
    }

    @Test
    fun `all errors across request body and response headers and body should be caught and returned together` () {
        val feature = OpenApiSpecification.fromYAML(
            """
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
                info:
                  type: number
              required:
                - data
                - info
            examples:
              200_OK:
                value:
                  data: "abc"
                  info: "abc"
      responses:
        '200':
          description: Says hello
          headers:
            X-Value:
              schema:
                type: integer
              examples:
                200_OK:
                  value: "abc"
          content:
            text/plain:
              examples:
                200_OK:
                  value: "abc"
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains(">> REQUEST.BODY.data")
            assertThat(exceptionCauseMessage(exception)).contains(">> REQUEST.BODY.info")
            assertThat(exceptionCauseMessage(exception)).contains("RESPONSE.HEADER.X-Value")
            assertThat(exceptionCauseMessage(exception)).contains("RESPONSE.BODY")
        })
    }

    @Test
    fun `all errors across path and params and headers and response headers and body should be caught and returned together` () {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data/{id}:
    get:
      summary: hello world
      description: test
      security:
        - apiKey: []
      parameters:
        - name: id
          in: path
          schema:
            type: integer
          required: true
          examples:
            200_OK:
              value: "abc"
        - name: enabled
          in: query
          schema:
            type: boolean
          required: true
          examples:
            200_OK:
              value: "abc"
        - name: X-Token
          in: header
          schema:
            type: integer
          required: true
          examples:
            200_OK:
              value: "abc"
      responses:
        '200':
          description: Says hello
          headers:
            X-Value:
              schema:
                type: integer
              examples:
                200_OK:
                  value: "abc"
          content:
            text/plain:
              schema:
                type: number
              examples:
                200_OK:
                  value: "abc"
components:
  securitySchemes:
    apiKey:
      type: apiKey
      in: header
      name: X-API-Key
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.PATH.id")
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.QUERY.enabled")
            assertThat(exceptionCauseMessage(exception)).contains("REQUEST.PARAMETERS.HEADER.X-Token")
            assertThat(exceptionCauseMessage(exception)).contains("RESPONSE.HEADER.X-Value")
            assertThat(exceptionCauseMessage(exception)).contains("RESPONSE.BODY")
        })
    }

    @Test
    fun `invalid response body example should be caught by the validator` () {
        val feature = OpenApiSpecification.fromYAML(
            """
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
                - data
              properties:
                data:
                  type: number
            examples:
              200_OK:
                value:
                  data: 10
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: object
                required:
                  - data
                properties:
                  data:
                    type: number
              examples:
                200_OK:
                  value:
                    data: "abc"
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("RESPONSE.BODY.data")
        })
    }

    @Test
    fun `validation errors should contain the name of the test` () {
        val feature = OpenApiSpecification.fromYAML(
            """
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
                - data
              properties:
                data:
                  type: number
            examples:
              200_OK:
                value:
                  data: 10
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: object
                required:
                  - data
                properties:
                  data:
                    type: number
              examples:
                200_OK:
                  value:
                    data: "abc"
""".trimIndent(), ""
        ).toFeature()

        assertThatThrownBy { feature.validateExamplesOrException() }.satisfies(Consumer { exception ->
            assertThat(exceptionCauseMessage(exception)).contains("200_OK")
        })
    }

    @Test
    fun `show the reason why an example was ignored when loading it for test`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/has_irrelevant_externalized_test.yaml").toFeature()

        val (output, _) = captureStandardOutput {
            feature.loadExternalisedExamples()
        }

        assertThat(output)
            .contains("POST /order_action_figure -> 200 does not match any operation in the specification")
    }

    @Test
    fun `should throw an error if an example was ignored when loading it for test in strict mode`() {
        val feature = OpenApiSpecification.fromFile(
            "src/test/resources/openapi/has_irrelevant_externalized_test.yaml",
        ).toFeature().copy(strictMode = true)

        val error = assertThrows<ContractException> {
            feature.loadExternalisedExamples()
        }

        assertThat(error.message)
            .contains("POST /order_action_figure -> 200 does not match any operation in the specification")
    }

    @Test
    fun `should throw an error if a partial example was ignored when loading it for test in strict mode`() {
        val feature = OpenApiSpecification.fromFile(
            "src/test/resources/openapi/has_irrelevant_externalized_test.yaml",
        ).toFeature().copy(strictMode = true)

        val error = assertThrows<ContractException> {
            feature.loadExternalisedExamples()
        }

        assertThat(error.message)
            .contains("POST /order_partial -> 200 does not match any operation in the specification")
    }

    @Test
    fun `should throw an error if the example to be loaded is invalid, in strict mode`() {
        val feature = OpenApiSpecification.fromFile(
            "src/test/resources/openapi/hello_with_invalid_externalised_example.yaml",
        ).toFeature().copy(strictMode = true)

        val error = assertThrows<ContractException> {
            feature.loadExternalisedExamples()
        }

        assertThat(error.report()).isEqualToNormalizingWhitespace("""
        >> ${File("src/test/resources/openapi/hello_with_invalid_externalised_example_examples/invalid.json").canonicalPath}
        Error loading example due to invalid format. Please correct the format to proceed
        Example should contain http-response/mock-http-response as a top level key.
        """.trimIndent())
    }

    @Test
    fun `validate an invalid query param in the path of an externalised example`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_invalid_external_query_in_url.yaml").toFeature().loadExternalisedExamples()

        assertThatThrownBy {
            feature.validateExamplesOrException()
        }.hasMessageContaining("REQUEST.PARAMETERS.QUERY.enabled")
    }

    @Test
    fun `validate and use an query param in the path of an externalised example`() {
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_valid_external_query_in_url.yaml").toFeature().loadExternalisedExamples()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/data")
                assertThat(request.queryParams.getValues("enabled").single()).isEqualTo("true")
                return HttpResponse(200, parsedJSONObject("""{"id": 10}"""))
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    @Disabled
    fun `should be able to stub out enum with string type using substitution`() {
        createStubFromContracts(listOf("src/test/resources/openapi/spec_with_empoyee_enum.yaml"), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person", queryParametersMap = mapOf("type" to "employee"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue
            assertThat(responseBody.findFirstChildByPath("type")?.toStringLiteral()).isEqualTo("employee")
        }
    }

    @Test
    @Disabled
    fun `should be able to stub out enum with string type using data substitution`() {
        createStubFromContracts(listOf("src/test/resources/openapi/spec_with_empoyee_enum2.yaml"), timeoutMillis = 0).use { stub ->
            val request = HttpRequest("GET", "/person", queryParametersMap = mapOf("type" to "manager"))
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            val responseBody = response.body as JSONObjectValue
            assertThat(responseBody.findFirstChildByPath("type")?.toStringLiteral()).isEqualTo("manager")
            assertThat(responseBody.findFirstChildByPath("name")?.toStringLiteral()).isEqualTo("Justin")
        }
    }

    @Test
    fun `should be able to create a contract test based on an example`(@TempDir tempDir: File) {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample Product API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://localhost:8080
    description: Local
paths:
  /products:
    post:
      summary: Add Product
      description: Add Product
      requestBody:
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
          description: Returns Product With Id
          content:
            application/json:
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
""".trimIndent(), ""
        ).toFeature()

        val exampleFile = tempDir.resolve("example.json")
        exampleFile.writeText(
            """
            {
              "http-request": {
                "method": "POST",
                "path": "/products",
                "body": {
                  "name": "James"
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              },
              "http-response": {
                "status": 200,
                "body": {
                  "id": 10
                },
                "headers": {
                  "Content-Type": "application/json"
                }
              }
            }
            """.trimIndent()
        )

        val contractTest = feature.createContractTestFromExampleFile(exampleFile.path).value

        val results = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val jsonRequestBody = request.body as JSONObjectValue
                assertThat(jsonRequestBody.findFirstChildByPath("name")?.toStringLiteral()).isEqualTo("James")
                return HttpResponse.ok(parsedJSONObject("""{"id": 10}""")).also {
                    println(request.toLogString())
                    println()
                    println(it.toLogString())
                }
            }
        }).first

        assertThat(results.isSuccess()).isTrue()
    }

    @Test
    fun `should be able to create contract test from an partial example`(@TempDir tempDir: File) {
        val apiSpecification = """
        openapi: 3.0.0
        info:
          title: Simple API
          version: 1.0.0
        paths:
          /test:
            post:
              summary: Test Example
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${"$"}ref: '#/components/schemas/ExampleRequest'
              responses:
                '200':
                  description: Successful response
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/ExampleResponse'
        components:
          schemas:
            ExampleRequest:
              type: object
              required:
                - name
                - age
              properties:
                name:
                  type: string
                age:
                  type: integer
                isEligible:
                  type: boolean
            ExampleResponse:
              allOf:
                - ${"$"}ref: '#/components/schemas/ExampleRequest'
                - type: object
                  required:
                    - id
                  properties:
                    id:
                      type: string
        """.trimIndent()
        val example = """
        {
          "partial": {
            "http-request": {
              "method": "POST",
              "path": "/test",
              "body": {
                "name": "(string)",
                "isEligible": true
              }
            },
            "http-response": {
              "status": 200,
              "body": {
                "id": "(string)"
              }
            }
          }
        }
        """.trimIndent()
        val dictionary = """
        ExampleRequest:
          name: John Doe
          age: 999
          isEligible: false
        """.trimIndent()

        val apiSpecFile = tempDir.resolve("api.yaml").apply { writeText(apiSpecification) }
        val examplesDir = tempDir.resolve("api_examples").apply { mkdirs() }
        val exampleFile = examplesDir.resolve("example.json").apply { writeText(example) }
        tempDir.resolve("api_dictionary.yaml").apply { writeText(dictionary) }

        Flags.using(
            Flags.SPECMATIC_GENERATIVE_TESTS to "true"
        ) {
            val feature = parseContractFileToFeature(apiSpecFile)
            val contractTest = feature.createContractTestFromExampleFile(exampleFile.canonicalPath).value

            val expectedRequestBody = parsedJSONObject("""{"name" : "John Doe", "isEligible" : true, "age" : 999}""")
            val result = contractTest.runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.body).isEqualTo(expectedRequestBody)
                    return HttpResponse.ok(expectedRequestBody.addEntry("id", "10")).also {
                        println(request.toLogString())
                        println()
                        println(it.toLogString())
                    }
                }
            }).first

            assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
        }
    }

    @Test
    fun `should perform assertions on the response for test created from example file`(@TempDir tempDir: File) {
        val apiSpecification = """
        openapi: 3.0.0
        info:
          title: Simple API
          version: 1.0.0
        paths:
          /test:
            post:
              summary: Test Example
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${"$"}ref: '#/components/schemas/ExampleRequest'
              responses:
                '200':
                  description: Successful response
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/ExampleResponse'
        components:
          schemas:
            ExampleRequest:
              type: object
              required:
                - name
                - age
              properties:
                name:
                  type: string
                age:
                  type: integer
                isEligible:
                  type: boolean
            ExampleResponse:
              allOf:
                - ${"$"}ref: '#/components/schemas/ExampleRequest'
                - type: object
                  required:
                    - id
                  properties:
                    id:
                      type: string
        """.trimIndent()
        val example = """
        {
          "partial": {
            "http-request": {
              "method": "POST",
              "path": "/test",
              "body": {
                "name": "(string)",
                "isEligible": true
              }
            },
            "http-response": {
              "status": 200,
              "body": {
                "id": "(string)",
                "name": "${"$"}eq(REQUEST.BODY.name)",
                "age": "${"$"}eq(REQUEST.BODY.age)",
                "isEligible": "${"$"}eq(REQUEST.BODY.isEligible)"
              }
            }
          }
        }
        """.trimIndent()
        val dictionary = """
        ExampleRequest:
          name: John Doe
          age: 999
          isEligible: false
        """.trimIndent()

        val apiSpecFile = tempDir.resolve("api.yaml").apply { writeText(apiSpecification) }
        val examplesDir = tempDir.resolve("api_examples").apply { mkdirs() }
        val exampleFile = examplesDir.resolve("example.json").apply { writeText(example) }
        tempDir.resolve("api_dictionary.yaml").apply { writeText(dictionary) }

        val feature = parseContractFileToFeature(apiSpecFile)
        val contractTest = feature.createContractTestFromExampleFile(exampleFile.canonicalPath).value

        val expectedRequestBody = parsedJSONObject("""{"name" : "John Doe", "isEligible" : true, "age" : 999}""")
        val result = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.body).isEqualTo(expectedRequestBody)
                return HttpResponse.ok(
                    parsedJSONObject("""{"name" : "Jane Doe", "isEligible" : false, "age" : 123, "id": "10"}""")
                ).also {
                    println(request.toLogString())
                    println()
                    println(it.toLogString())
                }
            }
        }).first

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario "Test Example. Response: Successful response"
        API: POST /test -> 200
        >> RESPONSE.BODY.name
        Expected "Jane Doe" to equal "John Doe"
        >> RESPONSE.BODY.age
        Expected 123 to equal 999
        >> RESPONSE.BODY.isEligible
        Expected false to equal true
        """.trimIndent())
    }

    @Nested
    inner class GenerateDiscriminatorDetailsBasedRequestResponsePairsTest {
        private val feature = Feature(name = "feature")
        private val scenario = mockk<Scenario>()

        @Test
        fun `should generate request-response pairs correctly when requests exceed responses`() {
            val request1 = HttpRequest()
            val request2 = HttpRequest()
            val request3 = HttpRequest()
            val response1 = HttpResponse()
            val response2 = HttpResponse()

            val requestList = listOf(
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "discriminator1"),
                    value = request1
                ),
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "discriminator2"),
                    value = request2
                ),
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "discriminator3"),
                    value = request3
                )
            )

            val responseList = listOf(
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "discriminator1"),
                    value = response1
                ),
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "random"),
                    value = response2
                )
            )
            every { scenario.generateHttpRequestV2() } returns requestList

            every { scenario.generateHttpResponseV2(any()) } returns responseList

            val pairs = feature.generateDiscriminatorBasedRequestResponseList(
                HasValue(scenario)
            )

            assertEquals(3, pairs.size)
            assertTrue(pairs.any { it.request == request1 && it.response == response1 })
            assertTrue(pairs.any { it.request == request2 && it.response == response1 })
            assertTrue(pairs.any { it.request == request3 && it.response == response1 })
        }

        @Test
        fun `should generate request-response pairs correctly when responses exceed requests`() {
            val request1 = HttpRequest()
            val response1 = HttpResponse()
            val response2 = HttpResponse()
            val requestList = listOf(
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "discriminator1"),
                    value = request1
                )
            )

            val responseList = listOf(
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "discriminator1"),
                    value = response1
                ),
                DiscriminatorBasedItem(
                    discriminator = DiscriminatorMetadata("type", "discriminator2"),
                    value = response2
                )
            )

            every { scenario.generateHttpRequestV2() } returns requestList

            every { scenario.generateHttpResponseV2(any()) } returns responseList

            val pairs = feature.generateDiscriminatorBasedRequestResponseList(HasValue(scenario))

            assertEquals(2, pairs.size)
            assertTrue(pairs.any { it.request == request1 && it.response == response1 })
            assertTrue(pairs.any { it.request == request1 && it.response == response2 })
        }

        @Test
        fun `should return empty list when both requests and responses are empty`() {
            every { scenario.generateHttpRequestV2() } returns emptyList()
            every { scenario.generateHttpResponseV2(any()) } returns emptyList()

            val pairs = feature.generateDiscriminatorBasedRequestResponseList(HasValue(scenario))

            assertTrue(pairs.isEmpty())
        }
    }

    @Test
    fun `EmptyStringPattern in request should result in no request body`() {
        val httpRequestPattern = HttpRequestPattern(
            method = "POST",
            httpPathPattern = HttpPathPattern.from("/data"),
            body = EmptyStringPattern
        )

        val scenario = Scenario(
            "",
            httpRequestPattern,
            HttpResponsePattern(status = 200),
            exampleName = "example"
        )

        val feature = Feature(name = "", scenarios = listOf(scenario))

        val openAPI = feature.toOpenApi()
        assertThat(openAPI.paths["/data"]?.post?.requestBody).isNull()
    }

    companion object {
        @JvmStatic
        fun singleFeatureContractSource(): Stream<Arguments> {
            val featureData = arrayOf(
                    "Feature: Contract for /balance API\n\n" +
                            "  Scenario: api call\n\n" +
                            "    When GET /balance?account-id=10\n" +
                            "    Then status 200\n" +
                            "    And response-body {calls_left: 10, messages_left: 20}",
                    "Feature: Contract for /balance API\n\n" +
                            "  Scenario: api call\n\n" +
                            "    When GET /balance?account-id=20\n" +
                            "    Then status 200\n" +
                            "    And response-body {calls_left: 10, messages_left: 30}"
            )
            return Stream.of(
                    Arguments.of(featureData[0], "10", "{calls_left: 10, messages_left: 20}"),
                    Arguments.of(featureData[1], "20", "{calls_left: 10, messages_left: 30}")
            )
        }
    }

    @Nested
    inner class CalculatePathTests {
        @Test
        fun `calculatePath should return empty set when no scenarios exist`() {
            val feature = Feature(scenarios = emptyList(), name = "EmptyFeature")
            val httpRequest = HttpRequest(method = "GET", path = "/test", body = StringValue("test"))

            val paths = feature.calculatePath(httpRequest, 200)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should return empty set when no scenarios match`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/different"),
                    body = StringPattern()
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val feature = Feature(scenarios = listOf(scenario), name = "TestFeature")
            val httpRequest = HttpRequest(method = "GET", path = "/test", body = StringValue("test"))

            val paths = feature.calculatePath(httpRequest, 200)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should return paths from first matching scenario`() {
            val scenario1 = Scenario(
                name = "scenario1",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(
                        mapOf("field1" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())),
                        typeAlias = "(Request1)"
                    )
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val scenario2 = Scenario(
                name = "scenario2",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(
                        mapOf("field2" to AnyPattern(listOf(NumberPattern()), extensions = emptyMap())),
                        typeAlias = "(Request2)"
                    )
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val feature = Feature(scenarios = listOf(scenario1, scenario2), name = "TestFeature")
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf("field1" to StringValue("value")))
            )

            val paths = feature.calculatePath(httpRequest, 200)

            assertThat(paths).containsExactly("{Request1}.field1{string}")
        }

        @Test
        fun `calculatePath should handle 400 status code with different matching logic`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test/(id:number)"),
                    body = JSONObjectPattern(
                        mapOf("data" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())),
                        typeAlias = "(BadRequest)"
                    )
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 400,
                    body = StringPattern()
                )
            )
            val feature = Feature(scenarios = listOf(scenario), name = "TestFeature")

            val pathWithInvalidDatatype = "/test/abc123"
            val httpRequest = HttpRequest(
                method = "POST",
                path = pathWithInvalidDatatype,
                body = JSONObjectValue(mapOf("data" to StringValue("string")))
            )

            val paths = feature.calculatePath(httpRequest, 400)

            assertThat(paths).containsExactly("{BadRequest}.data{string}")
        }

        @Test
        fun `calculatePath should handle multiple scenarios with same path and method`() {
            val scenario1 = Scenario(
                name = "scenario1",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(mapOf("type" to ExactValuePattern(StringValue("type1"))))
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val scenario2 = Scenario(
                name = "scenario2",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(
                        mapOf(
                            "type" to ExactValuePattern(StringValue("type2")),
                            "data" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())
                        ),
                        typeAlias = "(Type2Request)"
                    )
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val feature = Feature(scenarios = listOf(scenario1, scenario2), name = "TestFeature")
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf("type" to StringValue("type2"), "data" to StringValue("test")))
            )

            val paths = feature.calculatePath(httpRequest, 200)

            // Should match second scenario since first one doesn't have the 'data' field
            assertThat(paths).containsExactly("{Type2Request}.data{string}")
        }

        @Test
        fun `calculatePath should handle scenario with no AnyPatterns`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(mapOf("field" to StringPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val feature = Feature(scenarios = listOf(scenario), name = "TestFeature")
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf("field" to StringValue("test")))
            )

            val paths = feature.calculatePath(httpRequest, 200)

            assertThat(paths).isEmpty()
        }
    }
}