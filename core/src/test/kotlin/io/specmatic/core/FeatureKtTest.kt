package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.mock.mockFromJSON
import io.specmatic.osAgnosticPath
import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.utilities.OpenApiPath
import io.specmatic.stub.captureStandardOutput
import io.swagger.v3.core.util.Yaml
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

class FeatureKtTest {
    @Test
    fun `should lex type identically to pattern`() {
        val contractGherkin = """
            Feature: Math API
            
            Scenario: Addition
              Given type Numbers (number*)
              When POST /add
              And request-body (Numbers)
              Then status 200
              And response-body (number)
        """.trimIndent()

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        val request = HttpRequest().updateMethod("POST").updatePath("/add").updateBody("[1, 2]")

        val response = contractBehaviour.lookupResponse(request)

        assertEquals(200, response.status)

        try {
            response.body.displayableValue().toInt()
        } catch (e: Exception) { fail("${response.body} is not a number")}
    }

    @Test
    fun `should parse tabular pattern directly in request body`() {
        val contractGherkin = """
            Feature: Pet API

            Scenario: Get details
              When POST /pets
              And request-body
                | name        | (string) |
                | description | (string) |
              Then status 200
              And response-body (number)
        """.trimIndent()

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        val request = HttpRequest().updateMethod("POST").updatePath("/pets").updateBody("""{"name": "Benny", "description": "Fluffy and white"}""")
        val response = contractBehaviour.lookupResponse(request)

        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should parse tabular pattern directly in response body`() {
        val contractGherkin = """
            Feature: Pet API
            
            Scenario: Get details
              When GET /pets/(id:number)
              Then status 200
              And response-body
                | id   | (number) |
                | name | (string) |
        """.trimIndent()

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        val request = HttpRequest().updateMethod("GET").updatePath("/pets/10")
        val response = contractBehaviour.lookupResponse(request)

        response.body.let { body ->
            if(body !is JSONObjectValue) fail("Expected JSON object")

            assertTrue(body.jsonObject.getValue("id") is NumberValue)
            assertTrue(body.jsonObject.getValue("name") is StringValue)
        }
    }

    @Test
    fun `should lex http PATCH`() {
        val contractGherkin = """
            Feature: Pet Store
            
            Scenario: Update all pets
              When PATCH /pets
              And request-body {"health": "good"}
              Then status 202
        """.trimIndent()

        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

        val request = HttpRequest().updateMethod("PATCH").updatePath("/pets").updateBody("""{"health": "good"}""")

        val response = contractBehaviour.lookupResponse(request)

        assertEquals(202, response.status)
    }

    @Test
    fun `should parse multipart file spec`() {
        val feature = parseGherkinStringToFeature("""
            Feature: Customer Data API
            
            Scenario: Upload customer information
              When POST /data
              And request-part customer_info @customer_info.csv text/csv gzip
              Then status 200
        """.trimIndent())

        val pattern = feature.scenarios.single().httpRequestPattern.multiPartFormDataPattern.single() as MultiPartFilePattern
        assertThat(pattern.name).isEqualTo("customer_info")
        val filename = (pattern.filename as ExactValuePattern).pattern.toStringLiteral()
        assertThat(filename).endsWith(osAgnosticPath("/customer_info.csv"))
        assertThat(pattern.contentType).isEqualTo("text/csv")
        assertThat(pattern.contentEncoding).isEqualTo("gzip")
    }

    @Test
    fun `should parse multipart file spec without content encoding`() {
        val behaviour = parseGherkinStringToFeature("""
            Feature: Customer Data API
            
            Scenario: Upload customer information
              When POST /data
              And request-part customer_info @customer_info.csv text/csv
              Then status 200
        """.trimIndent())

        val pattern = behaviour.scenarios.single().httpRequestPattern.multiPartFormDataPattern.single() as MultiPartFilePattern
        assertThat(pattern.name).isEqualTo("customer_info")
        val filename = (pattern.filename as ExactValuePattern).pattern.toStringLiteral()
        assertThat(filename).endsWith(osAgnosticPath("/customer_info.csv"))
        assertThat(pattern.contentType).isEqualTo("text/csv")
        assertThat(pattern.contentEncoding).isEqualTo(null)
    }

    @Test
    fun `should parse multipart file spec without content type and content encoding`() {
        val behaviour = parseGherkinStringToFeature("""
            Feature: Customer Data API
            
            Scenario: Upload customer information
              When POST /data
              And request-part customer_info @customer_info.csv
              Then status 200
        """.trimIndent())

        val pattern = behaviour.scenarios.single().httpRequestPattern.multiPartFormDataPattern.single() as MultiPartFilePattern
        assertThat(pattern.name).isEqualTo("customer_info")
        val filename = (pattern.filename as ExactValuePattern).pattern.toStringLiteral()
        assertThat(filename).endsWith(osAgnosticPath("/customer_info.csv"))
        assertThat(pattern.contentType).isEqualTo(null)
        assertThat(pattern.contentEncoding).isEqualTo(null)
    }

    @Test
    fun `should parse multipart content spec`() {
        val behaviour = parseGherkinStringToFeature("""
            Feature: Customer Data API
            
            Scenario: Upload multipart info
              Given json Customer
                | customerId | (number) |
              And json Order
                | orderId | (number) |
              When POST /data
              And request-part customer_info (Customer)
              And request-part order_info (Order)
              Then status 200
        """.trimIndent())

        val patterns = behaviour.scenarios.single().httpRequestPattern.multiPartFormDataPattern.map { it as MultiPartContentPattern }

        val resolver = Resolver(newPatterns = behaviour.scenarios.single().patterns)

        assertThat(patterns[0].name).isEqualTo("customer_info")
        val pattern0 = deferredToJsonPatternData(patterns[0].content, resolver)
        val contentPattern0 = deferredToNumberPattern(pattern0.getValue("customerId"), resolver)
        assertThat(contentPattern0).isEqualTo(NumberPattern())

        assertThat(patterns[1].name).isEqualTo("order_info")
        val pattern1 = deferredToJsonPatternData(patterns[1].content, resolver)
        val contentPattern1 = deferredToNumberPattern(pattern1.getValue("orderId"), resolver)
        assertThat(contentPattern1).isEqualTo(NumberPattern())
    }

    @Test
    fun `should parse a row lookup pattern`() {
        val behaviour = parseGherkinStringToFeature("""
            Feature: Customer Data API

            Scenario: Upload multipart info
              Given json Data
                | id1 | (customerId:number) |
                | id2 | (orderId:number)    |
              When POST /data
              And request-body (Data)
              Then status 200

              Examples:
              | customerId | orderId |
              | 10         | 20      |
        """.trimIndent())

        val pattern = behaviour.scenarios.single().patterns.getValue("(Data)") as TabularPattern

        assertThat(pattern.pattern.getValue("id1")).isEqualTo(LookupRowPattern(NumberPattern(), "customerId"))
        assertThat(pattern.pattern.getValue("id2")).isEqualTo(LookupRowPattern(NumberPattern(), "orderId"))
    }

    @Test
    fun `should parse the WIP tag`() {
        val feature = parseGherkinStringToFeature("""
            Feature: Test feature
            
            @WIP
            Scenario: Test scenario
              When GET /
              Then status 200
        """.trimIndent())

        assertThat(feature.scenarios.single().ignoreFailure).isTrue()
    }

    @Test
    fun `a single scenario with 2 examples should be generated out of 2 stubs with the same structure`() {
        val stub1 = NamedStub("stub", ScenarioStub(
            HttpRequest("GET", "/", queryParametersMap = mapOf("hello" to "world")),
            HttpResponse.OK
        ))
        val stub2 = NamedStub("stub", ScenarioStub(
            HttpRequest("GET", "/", queryParametersMap = mapOf("hello" to "hello")),
            HttpResponse.OK
        ))

        val generatedGherkin = toGherkinFeature("new feature", listOf(stub1, stub2)).trim()

        val expectedGherkin = """Feature: new feature
  Scenario: stub
    When GET /?hello=(string)
    Then status 200
  
    Examples:
    | hello |
    | world |
    | hello |""".trim()

        assertThat(generatedGherkin).isEqualTo(expectedGherkin)
    }

    @Test
    fun `a single scenario with 2 examples of a multipart file should be generated out of 2 stubs with the same structure`() {
        val stub1 = NamedStub("stub", ScenarioStub(
            HttpRequest("GET", "/", multiPartFormData = listOf(MultiPartFileValue("employees", "employees1.csv", content=MultiPartContent("1,2,3")))),
            HttpResponse.OK
        ))
        val stub2 = NamedStub("stub", ScenarioStub(
            HttpRequest("GET", "/", multiPartFormData = listOf(MultiPartFileValue("employees", "employees2.csv", content=MultiPartContent("1,2,3")))),
            HttpResponse.OK
        ))

        val generatedGherkin = toGherkinFeature("new feature", listOf(stub1, stub2)).trim()

        val expectedGherkin = """Feature: new feature
  Scenario: stub
    When GET /
    And request-part employees @(string)
    Then status 200
  
    Examples:
    | employees_filename |
    | employees1.csv |
    | employees2.csv |""".trim()

        assertThat(generatedGherkin).isEqualTo(expectedGherkin)
    }

    @Test
    fun `an example should have the response Date headers value at the end as a comment`() {
        val stub = NamedStub("stub", ScenarioStub(
            HttpRequest("POST", "/", body = StringValue("hello world")),
            HttpResponse.OK.copy(headers = mapOf("Date" to "Tuesday 1st Jan 2020"))
        ))

        val generatedGherkin = toGherkinFeature("new feature", listOf(stub)).trim()

        val expectedGherkin = """Feature: new feature
  Scenario: stub
    When POST /
    And request-body (RequestBody: string)
    Then status 200
    And response-header Date (string)
  
    Examples:
    | RequestBody | __comment__ |
    | hello world | Tuesday 1st Jan 2020 |""".trim()

        assertThat(generatedGherkin).isEqualTo(expectedGherkin)
    }

    @Test
    fun `arrays should be converged when converting stubs into a specification`() {
        val requestBodies = listOf(
            parsedJSONObject("""{id: 10, addresses: [{"street": "Shaeffer Street"}, {"street": "Ransom Street"}]}"""),
            parsedJSONObject("""{id: 10, addresses: [{"street": "Gladstone Street"}, {"street": "Abacus Street"}]}"""),
            parsedJSONObject("""{id: 10, addresses: [{"street": "Maxwell Street"}, {"street": "Xander Street"}]}""")
        )

        val stubs = requestBodies.mapIndexed { index, requestBody ->
            NamedStub("stub$index", ScenarioStub(HttpRequest("POST", "/body", body = requestBody), HttpResponse.OK))
        }

        val gherkin = toGherkinFeature("New Feature", stubs)
        val openApi = parseGherkinStringToFeature(gherkin).toOpenApi()
        assertThat(Yaml.pretty(openApi).trim().replace("'", "").replace("\"", "")).isEqualTo("""
          openapi: 3.0.1
          info:
            title: New Feature
            version: 1
          paths:
            /body:
              post:
                summary: stub0
                parameters: []
                requestBody:
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: #/components/schemas/Body_POST_RequestBody
                  required: true
                responses:
                  200:
                    description: stub0
          components:
            schemas:
              Addresses:
                required:
                - street
                type: object
                properties:
                  street:
                    type: string
              Body_POST_RequestBody:
                required:
                - addresses
                - id
                type: object
                properties:
                  id:
                    type: integer
                  addresses:
                    type: array
                    items:
                      ${"$"}ref: #/components/schemas/Addresses
        """.trimIndent())
    }

    @Test
    fun `Scenario and description of a GET should not contain the query param section`() {
        val requestBody =
            parsedJSONObject("""{id: 10, addresses: [{"street": "Shaeffer Street"}, {"street": "Ransom Street"}]}""")

        val stubs = listOf(
            NamedStub("http://localhost?a=b", ScenarioStub(
                HttpRequest("GET", "/data", queryParametersMap = mapOf("id" to "10"), body = requestBody),
                HttpResponse.OK
            ))
        )

        val gherkin = toGherkinFeature("New Feature", stubs)
        val openApi = parseGherkinStringToFeature(gherkin).toOpenApi()
        assertThat(Yaml.pretty(openApi).trim().replace("'", "").replace("\"", "")).isEqualTo("""
              openapi: 3.0.1
              info:
                title: New Feature
                version: 1
              paths:
                /data:
                  get:
                    summary: http://localhost
                    parameters:
                    - name: id
                      in: query
                      schema:
                        type: integer
                    requestBody:
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: #/components/schemas/Data_GET_RequestBody
                      required: true
                    responses:
                      200:
                        description: http://localhost
              components:
                schemas:
                  Addresses:
                    required:
                    - street
                    type: object
                    properties:
                      street:
                        type: string
                  Data_GET_RequestBody:
                    required:
                    - addresses
                    - id
                    type: object
                    properties:
                      id:
                        type: integer
                      addresses:
                        type: array
                        items:
                          ${"$"}ref: #/components/schemas/Addresses
                """.trimIndent())
    }

    @Test
    fun `large numeric path segments should be treated as ids when comparing similar URLs once normalized`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Orders API

            Scenario: Order 1
              When GET /orders/5432154321
              Then status 200

            Scenario: Order 2
              When GET /orders/9876543210
              Then status 200
            """.trimIndent()
        )

        val (firstScenario, secondScenario) = feature.scenarios
        val firstScenarioNormalizedPath = OpenApiPath.from(firstScenario.path).normalize().toHttpPathPattern()
        val secondScenarioNormalizedPath = OpenApiPath.from(secondScenario.path).normalize().toHttpPathPattern()
        assertThat(similarURLPath(
            baseScenario = firstScenario.copy(httpRequestPattern = firstScenario.httpRequestPattern.copy(httpPathPattern = firstScenarioNormalizedPath)),
            newScenario = secondScenario.copy(httpRequestPattern = firstScenario.httpRequestPattern.copy(httpPathPattern = secondScenarioNormalizedPath)),
        )).isTrue
    }

    private fun deferredToJsonPatternData(pattern: Pattern, resolver: Resolver): Map<String, Pattern> =
            ((pattern as DeferredPattern).resolvePattern(resolver) as TabularPattern).pattern

    private fun deferredToNumberPattern(pattern: Pattern, resolver: Resolver): NumberPattern =
            (pattern as DeferredPattern).resolvePattern(resolver) as NumberPattern

    @Test
    fun `should handle multiple types in the response at different levels with the same key and hence the same name`() {
        val stubJSON = """
            {
            	"http-request": {
            		"method": "POST",
            		"path": "/data"
            	},
            	"http-response": {
            		"status": 200,
            		"body": {
            			"entries": [
            				{
            					"name": "James"
            				}
            			],
            			"data": {
            				"entries": [
            					{
            						"id": 10
            					}
            				]
            			}
            		}
            	}
            }
        """.trimIndent()

        val gherkinString = stubJSON.toFeatureString().trim()

        assertThat(gherkinString).isEqualTo("""Feature: New Feature
  Scenario: Test Feature
    Given type Entries
      | name | (string) |
    And type Entries_
      | id | (integer) |
    And type Data
      | entries | (Entries_*) |
    And type ResponseBody
      | entries | (Entries*) |
      | data | (Data) |
    When POST /data
    Then status 200
    And response-body (ResponseBody)""")
    }

    @Test
    fun `should handle multiple types in the request at different levels with the same key and hence the same name`() {
        val stubJSON = """
            {
                "http-request": {
                    "method": "POST",
                    "path": "/data",
                    "body": {
                        "entries": [
                            {
                                "name": "James"
                            }
                        ],
                        "data": {
                            "entries": [
                                {
                                    "id": 10
                                }
                            ]
                        }
                    }
                },
                "http-response": {
                    "status": 200,
                    "body": {
                        "operationId": 10
                    }
                }
            }
        """.trimIndent()

        val gherkinString = stubJSON.toFeatureString().trim()

        println(gherkinString)
        assertThat(gherkinString).isEqualTo("""Feature: New Feature
  Scenario: Test Feature
    Given type Entries
      | name | (string) |
    And type Entries_
      | id | (integer) |
    And type Data
      | entries | (Entries_*) |
    And type RequestBody
      | entries | (Entries*) |
      | data | (Data) |
    And type ResponseBody
      | operationId | (integer) |
    When POST /data
    And request-body (RequestBody)
    Then status 200
    And response-body (ResponseBody)
  
    Examples:
    | name | id |
    | James | 10 |""")
    }

    @Test
    fun `bindings should get generated when a feature contains the export statement`() {
        val contractGherkin = """
            Feature: Pet API
            
            Scenario: Get details
              When GET /pets/(id:number)
              Then status 200
              And response-header X-Data (string)
              And export data = response-header.X-Data
        """.trimIndent()

        val feature = parseGherkinStringToFeature(contractGherkin)

        feature.scenarios.first().let {
            assertThat(it.bindings).containsKey("data")
            assertThat(it.bindings["data"]).isEqualTo("response-header.X-Data")
        }
    }

    @Test
    fun `references should get generated when a feature contains the value statement`() {
        val contractGherkin = """
            Feature: Pet API
            
            Background:
              Given value data from data.$CONTRACT_EXTENSION
            
            Scenario: Get details
              When GET /pets/(id:number)
              Then status 200
              And response-header X-Data (string)
        """.trimIndent()

        val feature = parseGherkinStringToFeature(contractGherkin, "original.$CONTRACT_EXTENSION")

        feature.scenarios.first().let {
            assertThat(it.references).containsKey("data")
            assertThat(it.references["data"]).isInstanceOf(References::class.java)
            assertThat(it.references["data"]?.valueName).isEqualTo("data")
            assertThat(it.references["data"]?.contractFile?.path).isEqualTo("data.$CONTRACT_EXTENSION")
            assertThat(it.references["data"]?.contractFile?.relativeTo).isEqualTo(AnchorFile("original.$CONTRACT_EXTENSION"))
        }
    }

    @Test
    fun `invokes hook when it is passed`() {
        val hookMock = mockk<Hook>()

        every {
            hookMock.readContract(any())
        } returns """---
            openapi: "3.0.1"
            info:
              title: "Random API"
              version: "1"
            paths:
              /:
                get:
                  summary: "Random number"
                  parameters: []
                  responses:
                    "200":
                      description: "Random number"
                      content:
                        text/plain:
                          schema:
                            type: "number"
            """

        val feature = parseContractFileToFeature("test.yaml", hookMock)
        assertThat(feature.matches(HttpRequest("GET", "/"), HttpResponse.ok(NumberValue(10)))).isTrue
    }

    companion object {
        private const val OPENAPI_FILENAME = "openApiTest.yaml"
        private const val RESOURCES_ROOT = "src/test/resources/"
        private const val OPENAPI_RELATIVE_FILEPATH = "$RESOURCES_ROOT$OPENAPI_FILENAME"

        @BeforeAll
        @JvmStatic
        fun setup() {
            println(File(".").canonicalFile.path)
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
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        '404':
          description: Not Found
          content:
            application/json:
              schema:
                type: string
        '400':
          description: Bad Request
          content:
            application/json:
              schema:
                type: string
    """.trim()

            val openApiFile = File(OPENAPI_RELATIVE_FILEPATH)
            openApiFile.createNewFile()
            openApiFile.writeText(openAPI)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            File(OPENAPI_RELATIVE_FILEPATH).delete()
        }
    }

    @Nested
    inner class LoadOpenAPIFromGherkin {
        val feature = parseGherkinStringToFeature("""
                Feature: OpenAPI test
                    Background:
                        Given openapi $OPENAPI_FILENAME
                        And value auth from auth.spec
                        
                    Scenario: OpenAPI test
                        When GET /hello/10
                        Then status 200
                        And export data = response-body
            """.trimIndent(), File("${RESOURCES_ROOT}dummy.spec").canonicalPath)

        @Test
        fun `parsing OpenAPI spec should preserve the references declared in the gherkin spec`() {
            assertThat(feature.scenarios.first().references.contains("auth"))
        }

        @Test
        fun `parsing OpenAPI spec should preserve the bindings declared in the gherkin spec`() {
            assertThat(feature.scenarios.first().bindings.contains("data"))
        }
    }

    @Nested
    inner class FeatureToOpenAPI {
        @Test
        fun `should treat large numeric path segments as ids when generating OpenAPI`() {
            val feature = parseGherkinStringToFeature(
                """
                Feature: Orders API

                Scenario: Get order
                  When GET /orders/5432154321
                  Then status 200
                """.trimIndent()
            )

            val openAPI = feature.toOpenApi()
            val pathItem = openAPI.paths["/orders/{param}"]
            val getOperation = requireNotNull(pathItem?.get) { "Expected GET operation for /orders/{param}" }

            assertThat(pathItem).isNotNull
            assertThat(getOperation.parameters.map { it.name }).contains("param")
        }

        @Test
        fun `should provide unique names to body schemas under the same endpoint`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = HttpPathPattern.from("/orders/(id:string)"), method = "POST",
                headersPattern = HttpHeadersPattern(mapOf("headerKey" to StringPattern())),
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("queryKey" to StringPattern())),
                body = DeferredPattern("(RequestBody)"),
            )

            val orderPostOk = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(status = 201, body = DeferredPattern("(ResponseBody)")),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)"}""", "(RequestBody"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"id": "(uuid)"}""", "(ResponseBody)")
                ),
            ))

            val orderPostBadRequest = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(status = 400, body = DeferredPattern("(ResponseBody)")),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)"}""", "(RequestBody"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"error": "(string)"}""", "(ResponseBody)")
                ),
            ))

            val feature = Feature(name = "TEST", scenarios = listOf(orderPostOk, orderPostBadRequest))
            val openAPI = feature.toOpenApi()

            val parsedSpecification = OpenApiSpecification(parsedOpenApi = openAPI, openApiFilePath = "TEST")
            val parsedFeature = parsedSpecification.toFeature()
            val parsedOkScenario = parsedFeature.scenarios.first { it.isA2xxScenario() }
            val parsedBadRequestScenario = parsedFeature.scenarios.first { it.isA4xxScenario() }

            assertThat(parsedOkScenario.httpRequestPattern).isEqualTo(parsedBadRequestScenario.httpRequestPattern)
            assertThat(parsedOkScenario.httpResponsePattern).isNotEqualTo(parsedBadRequestScenario.httpResponsePattern)

            val okResponseBody = resolvedHop(parsedOkScenario.httpResponsePattern.body, parsedOkScenario.resolver) as JSONObjectPattern
            val badRequestResponseBody = resolvedHop(parsedBadRequestScenario.httpResponsePattern.body, parsedOkScenario.resolver) as JSONObjectPattern

            assertThat(okResponseBody == orderPostOk.patterns["(ResponseBody)"]).isTrue
            assertThat(badRequestResponseBody == orderPostBadRequest.patterns["(ResponseBody)"]).isTrue
        }

        @Test
        fun `should handle multiple JSON request content types for same endpoint`() {
            val jsonRequestPattern = HttpRequestPattern(
                httpPathPattern = HttpPathPattern.from("/orders/(id:string)"),
                method = "PATCH",
                headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                body = DeferredPattern("(RequestBody)"),
            )

            val jsonPatchRequestPattern = HttpRequestPattern(
                httpPathPattern = HttpPathPattern.from("/orders/(id:string)"),
                method = "PATCH",
                headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json-patch+json")))),
                body = DeferredPattern("(RequestBody)"),
            )

            val mergePatchRequestPattern = HttpRequestPattern(
                httpPathPattern = HttpPathPattern.from("/orders/(id:string)"),
                method = "PATCH",
                headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/merge-patch+json")))),
                body = DeferredPattern("(RequestBody)"),
            )

            val jsonScenario = Scenario(ScenarioInfo(
                httpRequestPattern = jsonRequestPattern,
                httpResponsePattern = HttpResponsePattern(status = 200, body = DeferredPattern("(ResponseBody)")),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)", "status": "(string)"}""", "(RequestBody)"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"updated": "(boolean)"}""", "(ResponseBody)"),
                ),
            ))

            val jsonPatchScenario = Scenario(ScenarioInfo(
                httpRequestPattern = jsonPatchRequestPattern,
                httpResponsePattern = HttpResponsePattern(status = 200, body = DeferredPattern("(ResponseBody)")),
                patterns = mapOf(
                    "(RequestBody)" to JSONArrayPattern("""[{"op": "(string)", "path": "(string)", "value": "(string)"}]""", "(RequestBody)"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"updated": "(boolean)"}""", "(ResponseBody)"),
                ),
            ))

            val mergePatchScenario = Scenario(ScenarioInfo(
                httpRequestPattern = mergePatchRequestPattern,
                httpResponsePattern = HttpResponsePattern(status = 200, body = DeferredPattern("(ResponseBody)")),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"status": "(string)"}""", "(RequestBody)"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"updated": "(boolean)"}""", "(ResponseBody)"),
                ),
            ))

            val feature = Feature(name = "TEST", scenarios = listOf(jsonScenario, jsonPatchScenario, mergePatchScenario))
            val openAPI = feature.toOpenApi()
            val parsedSpecification = OpenApiSpecification(parsedOpenApi = openAPI, openApiFilePath = "TEST")
            val parsedFeature = parsedSpecification.toFeature()

            assertThat(parsedFeature.scenarios).hasSize(3)

            val parsedScenarios = parsedFeature.scenarios
            val allPatterns = parsedScenarios.fold(emptyMap<String, Pattern>()) { acc, it -> acc + it.patterns }
            assertThat(parsedScenarios[0].httpRequestPattern.body).isNotEqualTo(parsedScenarios[1].httpRequestPattern.body)
            assertThat(parsedScenarios[1].httpRequestPattern.body).isNotEqualTo(parsedScenarios[2].httpRequestPattern.body)
            assertThat(parsedScenarios[0].httpRequestPattern.body).isNotEqualTo(parsedScenarios[2].httpRequestPattern.body)
            assertThat(allPatterns).hasSize(4)
        }

        @Test
        fun `should handle multiple response content types with different status codes`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = HttpPathPattern.from("/orders/(id:string)"),
                method = "GET"
            )

            val success200Json = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(ResponseBody)" to toJSONObjectPattern("""{"order": {"id": "(uuid)", "status": "(string)"}}""", "(ResponseBody)")
                )
            ))

            val success200HalJson = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/hal+json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(ResponseBody)" to toJSONObjectPattern("""{"id": "(uuid)", "_links": {"self": {"href": "(string)"}}}""", "(ResponseBody)")
                )
            ))

            val notFound404Json = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(
                    status = 404,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(ResponseBody)" to toJSONObjectPattern("""{"error": "(string)", "code": "(number)"}""", "(ResponseBody)")
                )
            ))

            val notFound404ProblemJson = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(
                    status = 404,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/problem+json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(ResponseBody)" to toJSONObjectPattern("""{"type": "(string)", "title": "(string)", "status": "(number)", "detail": "(string)"}""", "(ResponseBody)")
                )
            ))

            val feature = Feature(name = "TEST", scenarios = listOf(success200Json, success200HalJson, notFound404Json, notFound404ProblemJson))
            val openAPI = feature.toOpenApi()
            val parsedSpecification = OpenApiSpecification(parsedOpenApi = openAPI, openApiFilePath = "TEST")
            val parsedFeature = parsedSpecification.toFeature()

            val allPatterns = parsedFeature.scenarios.fold(emptyMap<String, Pattern>()) { acc, it -> acc + it.patterns }
            val parsed200Scenarios = parsedFeature.scenarios.filter { it.isA2xxScenario() }
            val parsed404Scenarios = parsedFeature.scenarios.filter { it.isA4xxScenario() }

            assertThat(parsed200Scenarios).hasSize(2)
            assertThat(parsed404Scenarios).hasSize(2)

            assertThat(allPatterns).hasSize(4)
            assertThat(parsed200Scenarios[0].httpResponsePattern.body).isNotEqualTo(parsed200Scenarios[1].httpResponsePattern.body)
            assertThat(parsed404Scenarios[0].httpResponsePattern.body).isNotEqualTo(parsed404Scenarios[1].httpResponsePattern.body)
        }

        @Test
        fun `should handle complex mix of multiple JSON content types across requests and responses`() {
            val path = "/products"

            val jsonToJson200 = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = HttpPathPattern.from(path),
                    method = "POST",
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                    body = DeferredPattern("(RequestBody)")
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)", "price": "(number)"}""", "(RequestBody)"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"productId": "(uuid)", "created": "(boolean)"}""", "(ResponseBody)")
                )
            ))

            val jsonToHal201 = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = HttpPathPattern.from(path),
                    method = "POST",
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                    body = DeferredPattern("(RequestBody)")
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/hal+json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)", "price": "(number)"}""", "(RequestBody)"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"productId": "(uuid)", "_links": {"self": {"href": "(string)"}}}""", "(ResponseBody)")
                )
            ))

            val jsonPatchTo200 = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = HttpPathPattern.from(path + "/(id:string)"),
                    method = "PATCH",
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json-patch+json")))),
                    body = DeferredPattern("(RequestBody)")
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(RequestBody)" to JSONArrayPattern("""[{"op": "(string)", "path": "(string)", "value": "(string)"}]""", "(RequestBody)"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"updated": "(boolean)", "productId": "(uuid)"}""", "(ResponseBody)")
                )
            ))

            val jsonToProblem400 = Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = HttpPathPattern.from(path),
                    method = "POST",
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json")))),
                    body = DeferredPattern("(RequestBody)")
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 400,
                    headersPattern = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/problem+json")))),
                    body = DeferredPattern("(ResponseBody)")
                ),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)", "price": "(number)"}""", "(RequestBody)"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"type": "(string)", "detail": "(string)"}""", "(ResponseBody)")
                )
            ))

            val feature = Feature(name = "TEST", scenarios = listOf(jsonToJson200, jsonToHal201, jsonPatchTo200, jsonToProblem400))
            val openAPI = feature.toOpenApi()
            val parsedSpecification = OpenApiSpecification(parsedOpenApi = openAPI, openApiFilePath = "TEST")
            val parsedFeature = parsedSpecification.toFeature()

            assertThat(parsedFeature.scenarios).hasSize(4)
            val parsed2xxScenarios = parsedFeature.scenarios.filter { it.isA2xxScenario() }
            val parsed4xxScenarios = parsedFeature.scenarios.filter { it.isA4xxScenario() }

            assertThat(parsed2xxScenarios).hasSize(3)
            assertThat(parsed4xxScenarios).hasSize(1)

            val requestBodies = parsedFeature.scenarios.map { it.httpRequestPattern.body }.distinct()
            assertThat(requestBodies).hasSize(2)

            val responseBodies = parsedFeature.scenarios.map { it.httpResponsePattern.body }.distinct()
            assertThat(responseBodies).hasSize(4)
        }

        @Test
        fun `should be able to handle ListSchema with a sub-object schema properly`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = HttpPathPattern.from("/orders/(id:string)"), method = "POST",
                headersPattern = HttpHeadersPattern(mapOf("headerKey" to StringPattern())),
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("queryKey" to StringPattern())),
                body = ListPattern(pattern = DeferredPattern("(RequestBody)")),
            )

            val orderPostOk = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(status = 201, body = ListPattern(pattern = DeferredPattern("(ResponseBody)"))),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)"}""", "(RequestBody"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"id": "(uuid)"}""", "(ResponseBody)"),
                ),
            ))

            val orderPostBadRequest = Scenario(ScenarioInfo(
                httpRequestPattern = requestPattern,
                httpResponsePattern = HttpResponsePattern(status = 400, body = ListPattern(pattern = DeferredPattern("(ResponseBody)"))),
                patterns = mapOf(
                    "(RequestBody)" to toJSONObjectPattern("""{"name": "(string)"}""", "(RequestBody"),
                    "(ResponseBody)" to toJSONObjectPattern("""{"error": "(string)"}""", "(ResponseBody)"),
                ),
            ))

            val feature = Feature(name = "TEST", scenarios = listOf(orderPostOk, orderPostBadRequest))
            val openAPI = feature.toOpenApi()

            val parsedSpecification = OpenApiSpecification(parsedOpenApi = openAPI, openApiFilePath = "TEST")
            val parsedFeature = parsedSpecification.toFeature()
            val parsedOkScenario = parsedFeature.scenarios.first { it.isA2xxScenario() }
            val parsedBadRequestScenario = parsedFeature.scenarios.first { it.isA4xxScenario() }

            assertThat(parsedOkScenario.httpRequestPattern).isEqualTo(parsedBadRequestScenario.httpRequestPattern)
            assertThat(parsedOkScenario.httpResponsePattern).isNotEqualTo(parsedBadRequestScenario.httpResponsePattern)

            val okResponseBody = resolvedHop(parsedOkScenario.httpResponsePattern.body, parsedOkScenario.resolver) as ListPattern
            val badRequestResponseBody = resolvedHop(parsedBadRequestScenario.httpResponsePattern.body, parsedOkScenario.resolver) as ListPattern

            assertThat(resolvedHop(okResponseBody.pattern, parsedOkScenario.resolver)).isEqualTo(orderPostOk.patterns["(ResponseBody)"])
            assertThat(resolvedHop(badRequestResponseBody.pattern, parsedOkScenario.resolver)).isEqualTo(orderPostBadRequest.patterns["(ResponseBody)"])
        }
    }

    @Test
    fun `should generate all required negative tests`() {
        val contract = OpenApiSpecification.fromYAML(
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
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                data1:
                  type: string
                data2:
                  type: string
              required:
                - data1
                - data2
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
        """.trimIndent(), ""
        ).toFeature()

        val withGenerativeTestsEnabled = contract.enableGenerativeTesting()

        val tests: List<Scenario> =
            withGenerativeTestsEnabled.generateContractTestScenarios(emptyList()).toList().map { it.second.value }

        val expectedRequestTypes: List<Pair<String, String>> = listOf(
            Pair("(string)", "(string)"),
            Pair("(string)", "(null)"),
            Pair("(string)", "(number)"),
            Pair("(string)", "(boolean)"),
            Pair("(null)", "(string)"),
            Pair("(number)", "(string)"),
            Pair("(boolean)", "(string)")
        )

        val actualRequestTypes: List<Pair<String, String>> = tests.map {
            val bodyType = it.httpRequestPattern.body as JSONObjectPattern
            bodyType.pattern["data2"].toString() to bodyType.pattern["data1"].toString()
        }

        actualRequestTypes.forEach { keyTypesInRequest ->
            assertThat(expectedRequestTypes).contains(keyTypesInRequest)
        }

        assertThat(actualRequestTypes.size).isEqualTo(expectedRequestTypes.size)

        tests.forEach {
            println(it.testDescription())
            println(it.httpRequestPattern.body.toString())
            println()
        }
    }

    @Test
    fun `should parse equivalent json and yaml representation of an API`() {
        val yamlSpec = parseContractFileToFeature("src/test/resources/openapi/jsonAndYamlEquivalence/openapi.yaml")
        val jsonSpec = parseContractFileToFeature("src/test/resources/openapi/jsonAndYamlEquivalence/openapi.json")
        val yamlToJson = testBackwardCompatibility(yamlSpec, jsonSpec)
        assertThat(yamlToJson.success()).withFailMessage(yamlToJson.report()).isTrue
        val jsonToYAml = testBackwardCompatibility(jsonSpec, yamlSpec)
        assertThat(jsonToYAml.success()).withFailMessage(jsonToYAml.report()).isTrue
    }

    private fun String.toFeatureString(): String {
        val parsedJSONValue = parsedJSON(this) as JSONObjectValue
        return toGherkinFeature(NamedStub("Test Feature", mockFromJSON(parsedJSONValue.jsonObject)))
    }

    @Test
    fun `should be able to parse a spec with no warning about missing config`() {
        val (stdout, _) = captureStandardOutput {
            parseContractFileWithNoMissingConfigWarning(File("src/test/resources/openapi/jsonAndYamlEquivalence/openapi.yaml"))
        }

        assertThat(stdout).doesNotContain("Could not find the Specmatic configuration at path")
    }

    @Test
    fun `should filter out scenario when 4xx definitions are missing from Feature but present in originalScenarios`() {
        val path = "/orders/(id:string)"
        val orderPostOk = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = "POST", body = DeferredPattern("(RequestBody)")),
            httpResponsePattern = HttpResponsePattern(status = 201),
            patterns = mapOf("(RequestBody)" to toJSONObjectPattern("""{"digit": "(number)"}""", "(RequestBody"))
        ))

        val orderPostBadRequest = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = "POST"),
            httpResponsePattern = HttpResponsePattern(status = 400)
        ))

        val allScenarios = listOf(orderPostOk, orderPostBadRequest)
        val featureSubset = Feature(name = "Orders Subset", scenarios = listOf(orderPostOk))

        val results = featureSubset.negativeTestScenarios(originalScenarios = allScenarios).toList()
        assertThat(results)
            .withFailMessage("Expected no negative scenarios because the required 4xx definition was filtered out of the feature")
            .isEmpty()
    }

    @Test
    fun `should NOT filter out scenario when 4xx definitions are present in the Feature`() {
        val path = "/orders/(id:string)"
        val orderPostOk = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = "POST", body = DeferredPattern("(RequestBody)")),
            httpResponsePattern = HttpResponsePattern(status = 201),
            patterns = mapOf("(RequestBody)" to toJSONObjectPattern("""{"digit": "(number)"}""", "(RequestBody"))
        ))

        val orderPostBadRequest = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = "POST"),
            httpResponsePattern = HttpResponsePattern(status = 400)
        ))

        val featureComplete = Feature(name = "Orders Complete", scenarios = listOf(orderPostOk, orderPostBadRequest))
        val results = featureComplete.negativeTestScenarios(originalScenarios = featureComplete.scenarios).toList()
        assertThat(results).hasSize(3)
    }

    @Test
    fun `should NOT filter out scenario when 4xx definitions are missing from the Feature as well as originalScenarios`() {
        val path = "/orders/(id:string)"
        val orderPostOk = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = "POST", body = DeferredPattern("(RequestBody)")),
            httpResponsePattern = HttpResponsePattern(status = 201),
            patterns = mapOf("(RequestBody)" to toJSONObjectPattern("""{"digit": "(number)"}""", "(RequestBody"))
        ))

        val featureComplete = Feature(name = "Orders Complete", scenarios = listOf(orderPostOk))
        val results = featureComplete.negativeTestScenarios(originalScenarios = featureComplete.scenarios).toList()
        assertThat(results).hasSize(3)
    }
}
