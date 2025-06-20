package io.specmatic.conversions

import integration_tests.testCount
import io.ktor.util.reflect.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.specmatic.core.*
import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.LogStrategy
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.stub.HttpStubData
import io.specmatic.stub.captureStandardOutput
import io.specmatic.stub.createStub
import io.specmatic.stub.createStubFromContracts
import io.specmatic.stub.stringToMockScenario
import io.specmatic.test.ScenarioAsTest
import io.specmatic.test.TestExecutor
import io.specmatic.trimmedLinesString
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.OpenAPI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.math.BigDecimal
import java.util.*
import java.util.function.Consumer
import java.util.stream.Stream

fun openAPIToString(openAPI: OpenAPI): String {
    return Yaml.pretty(openAPI)
}

internal class OpenApiSpecificationTest {
    private val smallInc = BigDecimal("1")

    companion object {
        const val OPENAPI_FILE_WITH_YAML_EXTENSION = "openApiTest.yaml"
        const val OPENAPI_FILE_WITH_YML_EXTENSION = "openApiTest.yml"
        const val OPENAPI_FILE_WITH_REFERENCE = "openApiWithRef.yaml"
        const val PET_OPENAPI_FILE = "Pet.yaml"
        const val SCHEMAS_DIRECTORY = "schemas"

        @JvmStatic
        fun listOfOpenApiFiles(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(OPENAPI_FILE_WITH_YAML_EXTENSION),
                Arguments.of(OPENAPI_FILE_WITH_YML_EXTENSION)
            )
        }
    }

    private fun portableComparisonAcrossBuildEnvironments(actual: String, expected: String) {
        assertThat(adjusted(actual)).isEqualTo(
            adjusted(expected.removePrefix("---"))
        )
    }

    private fun adjusted(actual: String) =
        actual.trimIndent().replace("\"", "").replace("'", "")

    @BeforeEach
    fun setup() {
        unmockkAll()
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
              examples:
                200_OKAY:
                  value: hello
                  summary: response example without any matching parameters with examples
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
  /nested/types/without/ref/to/parent:
    get:
      summary: Nested Types
      description: Optional extended description in CommonMark or HTML.
      responses:
        '200':
          description: Returns nested type
          content:
            application/json:
              schema:
                ${"$"}ref: '#/components/schemas/NestedTypeWithoutRef'
  /nested/types/with/ref/to/parent:
    get:
      summary: Nested Types
      description: Optional extended description in CommonMark or HTML.
      responses:
        '200':
          description: Returns nested type
          content:
            application/json:
              schema:
                ${"$"}ref: '#/components/schemas/NestedTypeWithRef'

components:
  schemas:
    NestedTypeWithoutRef:
      description: ''
      type: object
      properties:
        Parent:
          type: object
          properties:
            Child:
              type: object
              properties:
                Parent:
                  type: object
                  properties:
                    Child:
                      type: string
    NestedTypeWithRef:
      description: ''
      type: object
      properties:
        Parent:
          type: object
          properties:
            Child:
              ${"$"}ref: '#/components/schemas/NestedTypeWithRef'
    """.trim()

        val openApiWithRef = """
openapi: "3.0.0"
info:
  version: 1.0.0
  title: Swagger Petstore
  description: A sample API that uses a petstore as an example to demonstrate features in the OpenAPI 3.0 specification
  termsOfService: http://swagger.io/terms/
  contact:
    name: Swagger API Team
    email: apiteam@swagger.io
    url: http://swagger.io
  license:
    name: Apache 2.0
    url: https://www.apache.org/licenses/LICENSE-2.0.html
servers:
  - url: http://petstore.swagger.io/api
paths:
  /pets:
    get:
      description: get all pets
      operationId: getPets
      responses:
        200:
          description: pet response
          content:
            application/json:
              schema:
                ${"$"}ref: './${SCHEMAS_DIRECTORY}/${PET_OPENAPI_FILE}'
components:
  schemas:
        """.trim()

        val pet = """
Pet:
  type: object
  required:
    - id
    - name
  properties:
    name:
      type: string
    id:
      type: integer
      format: int64
        """.trim()

        listOf(OPENAPI_FILE_WITH_YAML_EXTENSION, OPENAPI_FILE_WITH_YML_EXTENSION).forEach {
            val openApiFile = File(it)
            openApiFile.createNewFile()
            openApiFile.writeText(openAPI)
        }

        val openApiFileWithRef = File(OPENAPI_FILE_WITH_REFERENCE)
        openApiFileWithRef.createNewFile()
        openApiFileWithRef.writeText(openApiWithRef)

        File("./${SCHEMAS_DIRECTORY}").mkdirs()
        val petFile = File("./${SCHEMAS_DIRECTORY}/$PET_OPENAPI_FILE")
        petFile.createNewFile()
        petFile.writeText(pet)
    }

    @AfterEach
    fun teardown() {
        listOf(OPENAPI_FILE_WITH_YAML_EXTENSION, OPENAPI_FILE_WITH_YML_EXTENSION).forEach {
            File(it).delete()
        }
        File(OPENAPI_FILE_WITH_REFERENCE).delete()
        File(SCHEMAS_DIRECTORY).deleteRecursively()
    }

    @Disabled
    @ParameterizedTest
    @MethodSource("listOfOpenApiFiles")
    fun `should generate 200 OK scenarioInfos from openAPI`(openApiFile: String) {
        val openApiSpecification = OpenApiSpecification.fromFile(openApiFile)
        val (scenarioInfos, _) = openApiSpecification.toScenarioInfos()
        assertThat(scenarioInfos.size).isEqualTo(3)
    }

    @Test
    fun `nullable ref`() {
        val gherkin = """
            Feature: Test
              Scenario: Test
                Given type Address
                | street | (string) |
                When POST /user
                And request-body
                | address | (Address?) |
                Then status 200
                And response-body (string)
        """.trimIndent()

        val gherkinToOpenAPI = parseGherkinStringToFeature(gherkin).toOpenApi()
        val yamlFromGherkin = openAPIToString(gherkinToOpenAPI)
        println(yamlFromGherkin)
        val spec = OpenApiSpecification.fromYAML(yamlFromGherkin, "")
        val feature = spec.toFeature()

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"address": {"street": "Baker Street"}}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"address": null}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")
    }

    @ParameterizedTest
    @MethodSource("listOfOpenApiFiles")
    fun `should not resolve non ref nested types to Deferred Pattern`(openApiFile: String) {
        val openApiSpecification = OpenApiSpecification.fromFile(openApiFile)
        val (scenarioInfoData, _) = openApiSpecification.toScenarioInfos()
        val nestedTypeWithoutRef = scenarioInfoData.first().patterns.getOrDefault("(NestedTypeWithoutRef)", NullPattern)
        assertThat(containsDeferredPattern(nestedTypeWithoutRef)).isFalse
    }

    @ParameterizedTest
    @MethodSource("listOfOpenApiFiles")
    fun `should resolve ref nested types to Deferred Pattern`(openApiFile: String) {
        val openApiSpecification = OpenApiSpecification.fromFile(openApiFile)
        val (scenarioInfos, _) = openApiSpecification.toScenarioInfos()
        val nestedTypeWithRef = scenarioInfos[4].patterns["(NestedTypeWithRef)"]
        assertThat(containsDeferredPattern(nestedTypeWithRef!!)).isTrue
    }

    private fun containsDeferredPattern(pattern: Pattern): Boolean {
        val innerPattern = pattern.pattern

        if (innerPattern !is Map<*, *>)
            return false

        val childPattern = (innerPattern).values.firstOrNull() ?: return false
        if (childPattern !is Pattern)
            return false

        return if (childPattern.instanceOf(DeferredPattern::class)) true
        else containsDeferredPattern(childPattern)
    }

    @ParameterizedTest
    @MethodSource("listOfOpenApiFiles")
    fun `none of the scenarios should expect the Content-Type header`(openApiFile: String) {
        val openApiSpecification = OpenApiSpecification.fromFile(openApiFile)
        val (scenarioInfos, _) = openApiSpecification.toScenarioInfos()

        for (scenarioInfo in scenarioInfos) {
            assertNotFoundInHeaders(scenarioInfo.httpRequestPattern.headersPattern)
            assertNotFoundInHeaders(scenarioInfo.httpResponsePattern.headersPattern)
        }
    }

    @Test
    fun `scenarios should have examples of type ResponseValueExample leading to response value validation when VALIDATE_RESPONSE_VALUE flag is true and response is not empty`() {
        val openApiFile = "src/test/resources/openapi/response_schema_validation_including_optional_spec.yaml"
        mockkObject(Flags)
        every { Flags.getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES) } returns false
        val specmaticConfig = mockk<SpecmaticConfig> {
            every { isResponseValueValidationEnabled() } returns true
            every { getIgnoreInlineExamples() } returns false
            every { getDictionary() } returns null
        }
        val openApiSpecification = OpenApiSpecification(
            openApiFilePath = openApiFile,
            parsedOpenApi = OpenApiSpecification.getParsedOpenApi(openApiFile),
            specmaticConfig = specmaticConfig
        )

        val (scenarioInfos, _) = openApiSpecification.toScenarioInfos()

        val examples = scenarioInfos.first().examples.flatMap {
            it.rows.map { row -> row.exactResponseExample }
        }
        examples.forEach {
            assertThat(it).isInstanceOf(ResponseValueExample::class.java)
        }
    }

    @Test
    fun `scenarios should have null examples leading to no response value validation when the example response is empty`() {
        val openApiSpecification = OpenApiSpecification.fromFile("src/test/resources/openapi/response_schema_validation_for_empty_response_example.yaml")

        val (scenarioInfos, _) = openApiSpecification.toScenarioInfos()

        val examples = scenarioInfos.first().examples.flatMap {
            it.rows.map { row -> row.exactResponseExample }
        }
        examples.forEach {
            assertThat(it).isNull()
        }
    }

    @Test
    fun `programmatically construct OpenAPI YAML for GET with request headers and path and query params`() {
        val feature = parseGherkinStringToFeature(
            """
Feature: Product API

Scenario: Get product by id
  When GET /product/(id:number)/variants/(variantId:number)?tag=(string)
  And request-header Authentication (string)
  And request-header OptionalHeader? (string)
  Then status 200
  And response-body (string)
""".trim()
        )
        val openAPI = feature.toOpenApi()
        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Product API"
              version: "1"
            paths:
              /product/{id}/variants/{variantId}:
                get:
                  summary: "Get product by id"
                  parameters:
                  - name: "id"
                    in: "path"
                    required: true
                    schema:
                      type: "number"
                  - name: "variantId"
                    in: "path"
                    required: true
                    schema:
                      type: "number"
                  - name: "tag"
                    in: "query"
                    schema:
                      type: "string"
                  - name: "Authentication"
                    in: "header"
                    required: true
                    schema:
                      type: "string"
                  - name: "OptionalHeader"
                    in: "header"
                    required: false
                    schema:
                      type: "string"
                  responses:
                    200:
                      description: "Get product by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get person by id
              When POST /person
              And request-body
              | id | (string) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()
        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      parameters: []
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - "id"
                              properties:
                                id:
                                  type: "string"
                        required: true
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body that includes external type definitions`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add person by id
              Given type Address
              | street | (string) |
              | locality | (string) |
              When POST /person
              And request-body
              | id | (string) |
              | address | (Address) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()
        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "address"
                          - "id"
                          properties:
                            id:
                              type: "string"
                            address:
                              ${"$"}ref: '#/components/schemas/Address'
                    required: true
                  responses:
                    200:
                      description: "Add person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "locality"
                  - "street"
                  properties:
                    street:
                      type: "string"
                    locality:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body containing array of objects`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get person by id
              Given type Address
              | street | (string) |
              | locality | (string) |
              When POST /person
              And request-body
              | id | (string) |
              | address | (Address*) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": "123", "address": [{"street": "baker street", "locality": "London"}]}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "address"
                          - "id"
                          properties:
                            id:
                              type: "string"
                            address:
                              type: "array"
                              items:
                                ${"$"}ref: '#/components/schemas/Address'
                    required: true
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "locality"
                  - "street"
                  properties:
                    street:
                      type: "string"
                    locality:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `OpenAPI contract where the request body refers to externalised type`() {
        val openAPIYaml = """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Address'
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "locality"
                  - "street"
                  properties:
                    street:
                      type: "string"
                    locality:
                      type: "string"
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPIYaml, "file.yaml").toFeature()

        assertThat(
            feature.matches(
                HttpRequest(
                    "POST",
                    "/person",
                    body = parsedJSON("""{"street": "baker street", "locality": "London"}""")
                ), HttpResponse.ok("success")
            )
        ).isTrue
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body containing a nullable object`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get person by id
              Given type Address
              | street | (string) |
              | locality | (string) |
              When POST /person
              And request-body
              | id | (string) |
              | address | (Address?) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": "123", "address": null}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "address"
                          - "id"
                          properties:
                            id:
                              type: "string"
                            address:
                              oneOf:
                              - properties: {}
                                nullable: true
                              - ${"$"}ref: '#/components/schemas/Address'
                    required: true
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "locality"
                  - "street"
                  properties:
                    street:
                      type: "string"
                    locality:
                      type: "string"
                      """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body containing a nullable primitive`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get person by id
              When POST /person
              And request-body
              | id | (string?) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": null}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "id"
                          properties:
                            id:
                              type: "string"
                              nullable: true
                    required: true
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
                      """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body containing a nullable primitive in string`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get person by id
              When POST /person
              And request-body
              | id | (number in string?) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": null}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": "10"}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "id"
                          properties:
                            id:
                              type: "string"
                              nullable: true
                    required: true
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
                      """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body containing a nullable array`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get person by id
              Given type Address
              | street | (string) |
              | locality | (string) |
              When POST /person
              And request-body
              | id | (string) |
              | address | (Address*?) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            with(
                this.scenarios.first().matchesMock(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": "123", "address": [{"street": "baker street", "locality": "London"}]}""")
                    ), HttpResponse.ok("success")
                )
            ) {
                assertThat(this).isInstanceOf(Result.Success::class.java)
            }

            with(
                this.scenarios.first().matchesMock(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": "123", "address": null}""")
                    ), HttpResponse.ok("success")
                )
            ) {

                assertThat(this).isInstanceOf(Result.Success::class.java)
            }
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "address"
                          - "id"
                          properties:
                            id:
                              type: "string"
                            address:
                              type: "array"
                              nullable: true
                              items:
                                ${"$"}ref: '#/components/schemas/Address'
                    required: true
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "locality"
                  - "street"
                  properties:
                    street:
                      type: "string"
                    locality:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with JSON request body containing an array of nullable values`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get person by id
              Given type Address
              | street | (string) |
              | locality | (string) |
              When POST /person
              And request-body
              | id | (string) |
              | address | (Address?*) |
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": "123", "address": [{"street": "baker street", "locality": "London"}, null]}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "address"
                          - "id"
                          properties:
                            id:
                              type: "string"
                            address:
                              type: "array"
                              items:
                                oneOf:
                                - properties: {}
                                  nullable: true
                                - ${"$"}ref: '#/components/schemas/Address'
                    required: true
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "locality"
                  - "street"
                  properties:
                    street:
                      type: "string"
                    locality:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with www-urlencoded request body containing a json field`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
                Scenario: Add Person
                  Given type Address
                  | street | (string) |
                  | locality | (string) |
                  And type Person
                  | id | (string) |
                  | address | (Address) |
                  When POST /person
                  And form-field person (Person)
                  Then status 200
                  And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person",
                    formFields = mapOf("person" to """{"id": "123", "address": {"street": "baker street", "locality": "London"}}""")
                ), HttpResponse.ok("success")
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          required:
                          - "person"
                          properties:
                            person:
                              ${"$"}ref: '#/components/schemas/Person'
                        encoding:
                          person:
                            contentType: "application/json"
                    required: true
                  responses:
                    200:
                      description: "Add Person"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "locality"
                  - "street"
                  properties:
                    street:
                      type: "string"
                    locality:
                      type: "string"
                Person:
                  required:
                  - "address"
                  - "id"
                  properties:
                    id:
                      type: "string"
                    address:
                      ${"$"}ref: '#/components/schemas/Address'
            """.trimIndent()
        )
    }

    @Test
    fun `merge a POST request with www-urlencoded request body containing a primitive form field`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
                Scenario: Add Data
                  When POST /data
                  And form-field data (string)
                  Then status 200

                Scenario: Add Data
                  When POST /data
                  And form-field data (string)
                  Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/data",
                    formFields = mapOf("data" to "hello world")
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Add Data"
                  parameters: []
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          required:
                          - "data"
                          properties:
                            data:
                              type: "string"
                    required: true
                  responses:
                    200:
                      description: "Add Data"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST and merge JSON request bodies structures with common names`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              Given type Person
              | address | (string) |
              When POST /person
              And request-body (Person)
              Then status 200
              And response-body (string)

            Scenario: Add Person Details
              Given type Person
              | id | (string) |
              | address | (string) |
              When POST /person
              And request-body (Person)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"id": "10", "address": "Baker street"}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Person'
                    required: true
                  responses:
                    200:
                      description: "Add Person"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Person:
                  required:
                  - "address"
                  properties:
                    address:
                      type: "string"
                    id:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for GET and merge JSON response bodies structures with common names`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              Given type Person
              | address | (string) |
              When GET /person1
              Then status 200
              And response-body (Person)

            Scenario: Add Person Details
              Given type Person
              | id | (string) |
              | address | (string) |
              When GET /person2
              Then status 200
              And response-body (Person)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/person1"
                    ), HttpResponse.ok(body = parsedJSON("""{"id": "10", "address": "Baker street"}"""))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/person2"
                    ), HttpResponse.ok(body = parsedJSON("""{"id": "10", "address": "Baker street"}"""))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/person1"
                    ), HttpResponse.ok(body = parsedJSON("""{"address": "Baker street"}"""))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/person2"
                    ), HttpResponse.ok(body = parsedJSON("""{"address": "Baker street"}"""))
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person1:
                get:
                  summary: "Add Person"
                  parameters: []
                  responses:
                    200:
                      description: "Add Person"
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Person'
              /person2:
                get:
                  summary: "Add Person Details"
                  parameters: []
                  responses:
                    200:
                      description: "Add Person Details"
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Person'
            components:
              schemas:
                Person:
                  required:
                  - "address"
                  properties:
                    address:
                      type: "string"
                    id:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with request type string`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When POST /person
              And request-body (string)
              Then status 200
            """
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person",
                    body = StringValue("test")
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      text/plain:
                        schema:
                          type: "string"
                    required: true
                  responses:
                    200:
                      description: "Add Person"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with request body with exact value`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When POST /person
              And request-body hello
              Then status 200
            """
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person",
                    body = StringValue("hello")
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      text/plain:
                        schema:
                          type: "string"
                          enum:
                          - "hello"
                    required: true
                  responses:
                    200:
                      description: "Add Person"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with header with exact value`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When POST /person
              And request-header X-Exact-Value hello
              Then status 200
            """
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person",
                    headers = mapOf("X-Exact-Value" to "hello")
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters:
                  - name: "X-Exact-Value"
                    in: "header"
                    required: true
                    schema:
                      type: "string"
                      enum:
                      - "hello"
                  responses:
                    200:
                      description: "Add Person"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with request type number`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When POST /person
              And request-body (number)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person",
                    body = NumberValue(10)
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      text/plain:
                        schema:
                          type: "number"
                    required: true
                  responses:
                    200:
                      description: "Add Person"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST with type number in string`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When POST /person
              And request-body
              | id | (number in string) |
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person",
                    body = parsedValue("""{"id": "10"}""")
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "id"
                          properties:
                            id:
                              type: "string"
                    required: true
                  responses:
                    200:
                      description: "Add Person"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for PUT`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When PUT /person
              And request-body
              | id | (number in string) |
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "PUT",
                    "/person",
                    body = parsedValue("""{"id": "10"}""")
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                put:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "id"
                          properties:
                            id:
                              type: "string"
                    required: true
                  responses:
                    200:
                      description: "Add Person"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST and merge JSON request bodies structures with request type string`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When POST /person
              And request-body (string)
              Then status 200

            Scenario: Add Person Details
              When POST /person
              And request-body (string)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person",
                    body = StringValue("test")
                ), HttpResponse.OK
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      text/plain:
                        schema:
                          type: "string"
                    required: true
                  responses:
                    200:
                      description: "Add Person"
            """.trimIndent()
        )
    }

    @Test
    fun `programmatically construct OpenAPI YAML for POST and merge JSON response bodies structures with response type number`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              When POST /person
              Then status 200
              And response-body (number)

            Scenario: Add Person Details
              When POST /person
              Then status 200
              And response-body (number)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            this.matchingStub(
                HttpRequest(
                    "POST",
                    "/person"
                ), HttpResponse.ok(NumberValue(10))
            )
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  responses:
                    200:
                      description: "Add Person"
                      content:
                        text/plain:
                          schema:
                            type: "number"
            """.trimIndent()
        )
    }

    @Test
    fun `response headers in gherkin are converted to response headers in OpenAIP`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get Person
              Given type Person
              | address | (string) |
              When GET /person
              Then status 200
              And response-header X-Hello-World (string)
              And response-body (Person)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/person"
                    ),
                    HttpResponse.ok(parsedJSON("""{"address": "Baker Street"}"""))
                        .copy(headers = mapOf("X-Hello-World" to "hello"))
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: "Get Person"
                  parameters: []
                  responses:
                    200:
                      description: "Get Person"
                      headers:
                        X-Hello-World:
                          required: true
                          schema:
                            type: "string"
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Person'
            components:
              schemas:
                Person:
                  required:
                  - "address"
                  properties:
                    address:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `JSON response in gherkin is converted to JSON response in OpenAIP`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Get Person
              Given type Person
              | address | (string) |
              When GET /person
              Then status 200
              And response-body (Person)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/person"
                    ), HttpResponse.ok(parsedJSON("""{"address": "Baker Street"}"""))
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: "Get Person"
                  parameters: []
                  responses:
                    200:
                      description: "Get Person"
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Person'
            components:
              schemas:
                Person:
                  required:
                  - "address"
                  properties:
                    address:
                      type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `defaults to nullable string for null type in gherkin`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              Given type Person
              | address | (null) |
              When POST /person
              And request-body (Person)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"address": null}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Person'
                    required: true
                  responses:
                    200:
                      description: "Add Person"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Person:
                  required:
                  - "address"
                  properties:
                    address:
                      nullable: true
            """.trimIndent()
        )
    }

    @Test
    fun `converts empty json array in gherkin to string array in openapi`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              Given type Person
              | address | [] |
              When POST /person
              And request-body (Person)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""{"address": []}""")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Person'
                    required: true
                  responses:
                    200:
                      description: "Add Person"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Person:
                  required:
                  - "address"
                  properties:
                    address:
                      type: "array"
                      items:
                        type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `payload is a list of nullables`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Person API
            
            Scenario: Add Person
              Given type Person
              | address | (string?*) |
              When POST /person
              And request-body (Person)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            val result: Result = this.scenarios.first().matchesMock(
                HttpRequest(
                    "POST",
                    "/person",
                    body = parsedJSON("""{"address": [null, "Baker Street"]}""")
                ), HttpResponse.ok("success")
            )

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Add Person"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Person'
                    required: true
                  responses:
                    200:
                      description: "Add Person"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Person:
                  required:
                  - "address"
                  properties:
                    address:
                      type: "array"
                      items:
                        oneOf:
                        - properties: {}
                          nullable: true
                        - type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `same request headers from multiple scenarios`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When GET /data
              And request-header X-Data (string)
              Then status 200
              And response-body (string)

            Scenario: Get details
              When GET /data
              And request-header X-Data (string)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        headers = mapOf("X-Data" to "data")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                get:
                  summary: "Get details"
                  parameters:
                  - name: "X-Data"
                    in: "header"
                    required: true
                    schema:
                      type: "string"
                  responses:
                    200:
                      description: "Get details"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `same response headers from multiple scenarios`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When GET /data
              Then status 200
              And response-header X-Data (string)
              And response-body (string)

            Scenario: Get details
              When GET /data
              Then status 200
              And response-header X-Data (string)
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data"
                    ), HttpResponse.ok("success").copy(headers = mapOf("X-Data" to "data"))
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                get:
                  summary: "Get details"
                  parameters: []
                  responses:
                    200:
                      description: "Get details"
                      headers:
                        X-Data:
                          required: true
                          schema:
                            type: "string"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `different response headers from multiple scenarios with the same method and path`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When GET /data
              Then status 200
              And response-header X-Data-One (string)
              And response-body (string)

            Scenario: Get details
              When GET /data
              Then status 200
              And response-header X-Data-Two (string)
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data"
                    ), HttpResponse.ok("success").copy(headers = mapOf("X-Data-One" to "data"))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data"
                    ), HttpResponse.ok("success").copy(headers = mapOf("X-Data-Two" to "data"))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data"
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                get:
                  summary: "Get details"
                  parameters: []
                  responses:
                    200:
                      description: "Get details"
                      headers:
                        X-Data-One:
                          required: false
                          schema:
                            type: "string"
                        X-Data-Two:
                          required: false
                          schema:
                            type: "string"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `different request headers from multiple scenarios with the same method and path`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When GET /data
              And request-header X-Data-One (string)
              Then status 200
              And response-body (string)

            Scenario: Get details
              When GET /data
              And request-header X-Data-Two (string)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        headers = mapOf("X-Data-One" to "data")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        headers = mapOf("X-Data-One" to "data")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        headers = mapOf("X-Data-One" to "data")
                    ), HttpResponse.ok("success")
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                get:
                  summary: "Get details"
                  parameters:
                  - name: "X-Data-One"
                    in: "header"
                    required: false
                    schema:
                      type: "string"
                  - name: "X-Data-Two"
                    in: "header"
                    required: false
                    schema:
                      type: "string"
                  responses:
                    200:
                      description: "Get details"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `multiple scenarios with the same query parameters`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When GET /data?param=(string)
              Then status 200
              And response-body (string)

            Scenario: Get details
              When GET /data?param=(string)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data"
                    ), HttpResponse.OK.copy(body = StringValue("success"))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        queryParametersMap = mapOf("param" to "data")
                    ), HttpResponse.OK.copy(body = StringValue("success"))
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                get:
                  summary: "Get details"
                  parameters:
                  - name: "param"
                    in: "query"
                    schema:
                      type: "string"
                  responses:
                    200:
                      description: "Get details"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `multiple scenarios with the different query parameters`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When GET /data?param1=(string)
              Then status 200
              And response-body (string)

            Scenario: Get details
              When GET /data?param2=(string)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data"
                    ), HttpResponse.OK.copy(body = StringValue("success"))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        queryParametersMap = mapOf("param1" to "data")
                    ), HttpResponse.OK.copy(body = StringValue("success"))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        queryParametersMap = mapOf("param2" to "data")
                    ), HttpResponse.OK.copy(body = StringValue("success"))
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data",
                        queryParametersMap = mapOf("param1" to "data", "param2" to "data")
                    ), HttpResponse.OK.copy(body = StringValue("success"))
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                get:
                  summary: "Get details"
                  parameters:
                  - name: "param1"
                    in: "query"
                    schema:
                      type: "string"
                  - name: "param2"
                    in: "query"
                    schema:
                      type: "string"
                  responses:
                    200:
                      description: "Get details"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()
        )
    }

    @Test
    fun `multiple scenarios with the same form fields containing JSON`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              Given type Record
              | id | (number) |
              When POST /data
              And form-field Data (Record)
              Then status 200
              And response-body (string)

            Scenario: Get details
              Given type Record
              | id | (number) |
              When POST /data
              And form-field Data (Record)
              Then status 200
              And response-body (string)
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        formFields = mapOf("Data" to """{"id": 10}""")
                    ), HttpResponse.OK.copy(body = StringValue("success"))
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Get details"
                  parameters: []
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          required:
                          - "Data"
                          properties:
                            Data:
                              ${"$"}ref: '#/components/schemas/Record'
                        encoding:
                          Data:
                            contentType: "application/json"
                    required: true
                  responses:
                    200:
                      description: "Get details"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Record:
                  required:
                  - "id"
                  properties:
                    id:
                      type: "number"
            """.trimIndent()
        )
    }

    @Test
    fun `merge multiple scenarios with the different status codes during gherkin-openapi conversion`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When POST /data
              Then status 200

            Scenario: Get details error
              When POST /data
              Then status 500
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data"
                    ), HttpResponse.OK
                )
            ).isTrue

            assertThat(
                matches(
                    HttpRequest(
                        "POST",
                        "/data"
                    ), HttpResponse(500)
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Get details"
                  parameters: []
                  responses:
                    200:
                      description: "Get details"
                    500:
                      description: "Get details error"
            """.trimIndent()
        )
    }

    @Test
    fun `empty request and response body should not result in string body when converting from gherkin to OpenAPI `() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: Get details
              When GET /data
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "GET",
                        "/data"
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: "3.0.1"
            info:
              title: "API"
              version: "1"
            paths:
              /data:
                get:
                  summary: "Get details"
                  parameters: []
                  responses:
                    200:
                      description: "Get details"
            """.trimIndent()
        )
    }

    @Test
    fun `payload type conversion when there are multiple request bodies named RequestBody should add prefixes to the type name and keep the request bodies separate`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API 1
              Given type RequestBody
              | hello | (string) |
              When POST /data1
              And request-body (RequestBody)
              Then status 200

            Scenario: API 2
              Given type RequestBody
              | world | (string) |
              When POST /data2
              And request-body (RequestBody)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data1",
                        body = parsedJSON("""{"hello": "Jill"}""")
                    ), HttpResponse.OK
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data2",
                        body = parsedJSON("""{"world": "Jack"}""")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data1:
                post:
                  summary: API 1
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Data1_RequestBody'
                    required: true
                  responses:
                    200:
                      description: API 1
              /data2:
                post:
                  summary: API 2
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Data2_RequestBody'
                    required: true
                  responses:
                    200:
                      description: API 2
            components:
              schemas:
                Data1_RequestBody:
                  required:
                  - hello
                  properties:
                    hello:
                      type: string
                Data2_RequestBody:
                  required:
                  - world
                  properties:
                    world:
                      type: string
              """.trimIndent()
        )
    }

    @Test
    fun `scenario 1 has string and scenario 2 has null and the paths are the same`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API 1
              Given type RequestBody
              | hello | (string) |
              When POST /data
              And request-body (RequestBody)
              Then status 200

            Scenario: API 2
              Given type RequestBody
              | hello | (null) |
              When POST /data
              And request-body (RequestBody)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = parsedJSON("""{"hello": "Jill"}""")
                    ), HttpResponse.OK
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = parsedJSON("""{"hello": null}""")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data:
                post:
                  summary: API 1
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Data_RequestBody'
                    required: true
                  responses:
                    200:
                      description: API 1
            components:
              schemas:
                Data_RequestBody:
                  required:
                  - hello
                  properties:
                    hello:
                      type: string
                      nullable: true
              """.trimIndent()
        )
    }

    @Test
    fun `scenario 1 has an object and scenario 2 has null but the paths are the same`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API 1
              Given type RequestBody
              | hello | (Hello) |
              And type Hello
              | world | (string) |
              When POST /data
              And request-body (RequestBody)
              Then status 200

            Scenario: API 2
              Given type RequestBody
              | hello | (null) |
              When POST /data
              And request-body (RequestBody)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = parsedJSON("""{"hello": {"world": "jill"}}""")
                    ), HttpResponse.OK
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = parsedJSON("""{"hello": null}""")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data:
                post:
                  summary: API 1
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Data_RequestBody'
                    required: true
                  responses:
                    200:
                      description: API 1
            components:
              schemas:
                Hello:
                  required:
                  - world
                  properties:
                    world:
                      type: string
                Data_RequestBody:
                  required:
                  - hello
                  properties:
                    hello:
                      oneOf:
                      - properties: {}
                        nullable: true
                      - ${"$"}ref: '#/components/schemas/Hello'
            """.trimIndent()
        )
    }

    @Test
    fun `scenario 1 has an object and scenario two has a nullable object of the same type but the paths are the same`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API 1
              Given type RequestBody
              | hello | (Hello) |
              And type Hello
              | world | (string) |
              When POST /data
              And request-body (RequestBody)
              Then status 200

            Scenario: API 2
              Given type RequestBody
              | hello | (Hello?) |
              When POST /data
              And request-body (RequestBody)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = parsedJSON("""{"hello": {"world": "jill"}}""")
                    ), HttpResponse.OK
                )
            ).isTrue
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = parsedJSON("""{"hello": null}""")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data:
                post:
                  summary: API 1
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Data_RequestBody'
                    required: true
                  responses:
                    200:
                      description: API 1
            components:
              schemas:
                Hello:
                  required:
                  - world
                  properties:
                    world:
                      type: string
                Data_RequestBody:
                  required:
                  - hello
                  properties:
                    hello:
                      oneOf:
                      - properties: {}
                        nullable: true
                      - ${"$"}ref: '#/components/schemas/Hello'
            """.trimIndent()
        )
    }

    @Test
    fun `lookup string value in gherkin should result in a string type in yaml`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API
              When POST /data
              And request-body (RequestBody: string)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = StringValue("Hello world")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data:
                post:
                  summary: API
                  parameters: []
                  requestBody:
                    content:
                      text/plain:
                        schema:
                          type: string
                    required: true
                  responses:
                    200:
                      description: API
            """.trimIndent()
        )
    }

    @Test
    fun `recursive array ref`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API
              Given type Data
              | id | (number) |
              | data? | (Data*) |
              When POST /data
              And request-body (Data)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data",
                        body = parsedJSON("""{"id": 10}""")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data:
                post:
                  summary: API
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Data'
                    required: true
                  responses:
                    200:
                      description: API
            components:
              schemas:
                Data:
                  required:
                  - id
                  properties:
                    id:
                      type: number
                    data:
                      type: array
                      items:
                        ${"$"}ref: '#/components/schemas/Data'
            """.trimIndent()
        )
    }

    @Test
    fun `handle object inside array inside object correctly`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              ${"$"}ref: '#/components/schemas/Data'
      responses:
        200:
          description: API
components:
  schemas:
    Data:
      properties:
        data:
          type: array
          items:
            type: object
            properties:
              id:
                type: integer
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest("POST", "/data", body = parsedValue("""{"data": [{"id": 10}]}"""))
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("POST")

    }

    @Test
    fun `support path parameter as enum inline`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /permissions/state/{state}:
    get:
      parameters:
      - name: state
        in: path
        schema:
          type: string
          enum:
          - ALLOW
          - DENY
      responses:
        200:
          description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest("GET", "/permissions/state/ALLOW")
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("GET")
        assertThat(stub.response.status).isEqualTo(200)
    }

    @Test
    fun `support path parameter as enum reference`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /permissions/state/{state}:
    get:
      parameters:
      - ${'$'}ref: '#/components/parameters/stateParam'
      responses:
        200:
          description: API
components:
  parameters:
    stateParam:
      name: state
      in: path
      schema:
        type: string
        enum:
        - ALLOW
        - DENY
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest("GET", "/permissions/state/ALLOW")
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("GET")
        assertThat(stub.response.status).isEqualTo(200)
    }

    @Test
    fun `support dictionary object type in request body with data structure as reference`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              additionalProperties:
                ${"$"}ref: Data
      responses:
        200:
          description: API
components:
  schemas:
    Data:
      type: object
      properties:
        name:
          type: string
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request =
            HttpRequest("POST", "/data", body = parsedValue("""{"10": {"name": "Jill"}, "20": {"name": "Jack"}}"""))
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("POST")
        assertThat(stub.response.status).isEqualTo(200)
    }

    @Test
    fun `support dictionary object type in request body with inline data structure`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              additionalProperties:
                type: object
                properties:
                  name:
                    type: string
      responses:
        200:
          description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request =
            HttpRequest("POST", "/data", body = parsedValue("""{"10": {"name": "Jill"}, "20": {"name": "Jack"}}"""))
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("POST")
        assertThat(stub.response.status).isEqualTo(200)
    }

    @Test
    fun `support dictionary object type as JSON value`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                Data:
                  type: object
                  additionalProperties:
                    ${"$"}ref: Person
      responses:
        200:
          description: API
components:
  schemas:
    Person:
      type: object
      properties:
        name:
          type: string
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest(
            "POST",
            "/data",
            body = parsedValue("""{"Data": {"10": {"name": "Jill"}, "20": {"name": "Jack"}}}""")
        )
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("POST")
        assertThat(stub.response.status).isEqualTo(200)

    }

    @Test
    fun `support dictionary object type with composed oneOf value`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                myValues:
                  type: object
                  additionalProperties:
                    oneOf:
                    - ${"$"}ref: Person
                    - ${"$"}ref: Alien
      responses:
        200:
          description: API
components:
  schemas:
    Person:
      type: object
      properties:
        name:
          type: string
    Alien:
      type: object
      properties:
        moniker:
          type: string
        homePlanet:
          type: string
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest(
            "POST",
            "/data",
            body = parsedValue("""{"myValues": {"10": {"name": "Jill"}, "20": {"moniker": "Vin", "homePlanet": "Scadrial"}}}""")
        )
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("POST")
        assertThat(stub.response.status).isEqualTo(200)

    }

    @Test
    fun `support dictionary object type in request body with inline fixed keys`() {
        val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              additionalProperties:
                type: string
      responses:
        200:
          description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request =
            HttpRequest("POST", "/data", body = parsedValue("""{"10": "Jill", "20": "Jack"}"""))
        val response = HttpResponse.OK

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("POST")
        assertThat(stub.response.status).isEqualTo(200)
    }

    @Nested
    inner class WhenAdditionalPropertiesIsFalse {
        private val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                id:
                  type: integer
              additionalProperties: false
      responses:
        200:
          description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        @Test
        fun `an object with no additional properties should match the specification`() {
            val request =
                HttpRequest("POST", "/data", body = parsedValue("""{"id": 10}"""))
            val response = HttpResponse.OK

            val stub: HttpStubData = feature.matchingStub(request, response)
            assertThat(stub.requestType.matches(request, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `an object with additional properties should not match the specification`() {
            val invalidRequest =
                HttpRequest("POST", "/data", body = parsedValue("""{"id": 10, "name": "Jill"}"""))
            val response = HttpResponse.OK

            assertThatThrownBy { feature.matchingStub(invalidRequest, response) }
                .isInstanceOf(NoMatchingScenario::class.java)
                .hasMessageContaining("REQUEST.BODY.name")
        }
    }


    @Nested
    inner class WhenAdditionalPropertiesIsTrue {
        private val openAPI =
            """
---
openapi: 3.0.1
info:
  title: API
  version: 1
paths:
  /data:
    post:
      summary: API
      parameters: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: true
      responses:
        200:
          description: API
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        @Test
        fun `an object with string keys with values of any type should meet the specification`() {
            val request =
                HttpRequest(
                    "POST",
                    "/data",
                    body = parsedValue("""{"id": 10, "address": {"street": "Link Road", "city": "Mumbai", "country": "India"}}""")
                )
            val response = HttpResponse.OK

            val stub: HttpStubData = feature.matchingStub(request, response)
            assertThat(stub.requestType.matches(request, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `an object with string keys with some values set as null should match the specification`() {
            val request =
                HttpRequest("POST", "/data", body = parsedValue("""{"id": 10, "address": null}"""))
            val response = HttpResponse.OK

            val stub: HttpStubData = feature.matchingStub(request, response)
            assertThat(stub.requestType.matches(request, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `a string value should not match the specification`() {
            val invalidRequest =
                HttpRequest("POST", "/data", body = StringValue("some data"))
            val response = HttpResponse.OK

            assertThatThrownBy { feature.matchingStub(invalidRequest, response) }
                .isInstanceOf(NoMatchingScenario::class.java)
                .hasMessageContaining("Expected json object, actual was some data")
        }
    }

    @Test
    fun `conversion supports dictionary type`() {
        val gherkin = """
            Feature: Test
              Scenario: Test
                Given type Data
                | name | (string) |
                When GET /
                Then status 200
                And response-header Content-Type application/json
                And response-body (dictionary string Data)
        """.trimIndent()

        val feature = parseGherkinStringToFeature(gherkin)
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            val request = HttpRequest(
                "GET",
                "/"
            )
            val response = HttpResponse.ok(
                body = parsedJSON("""{"10": {"name": "Jane"}}""")
            )

            val matchResult =
                this.matchResult(
                    request,
                    response
                )

            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }

    }

    @Test
    fun `should resolve ref to another file`() {
        val openApiSpecification = OpenApiSpecification.fromFile(OPENAPI_FILE_WITH_REFERENCE)
        val (scenarioInfos, _) = openApiSpecification.toScenarioInfos()
        assertThat((scenarioInfos).size).isEqualTo(1)
        assertThat(openApiSpecification.patterns["(Pet)"]).isNotNull
    }

    private fun assertNotFoundInHeaders(headersPattern: HttpHeadersPattern) {
        assertThat(headersPattern.pattern.keys.map { it.lowercase() }).doesNotContain(CONTENT_TYPE.lowercase())
    }

    @Nested
    inner class XML {
        @Test
        fun `basic xml contract`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/users':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: user
                          properties:
                            id:
                              type: integer
                          required:
                            - id
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<user><id>10</id></user>"""

            assertMatchesSnippet(xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with attributes`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/users':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: user
                          properties:
                            id:
                              type: integer
                            name:
                              type: string
                              xml:
                                attribute: true
                          required:
                            - id
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<user name="John Doe"><id>10</id></user>"""

            assertMatchesSnippet(xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract shows error when compulsory node is not available`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/users':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: user
                          properties:
                            id:
                              type: integer
                            name:
                              type: string
                          required:
                            - id
                            - name
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()
            val xmlSnippet = """<user><id>10</id></user>"""
            val request = HttpRequest("POST", "/users", body = parsedValue(xmlSnippet))
            assertThatThrownBy {
                xmlFeature.matchingStub(
                    request,
                    HttpResponse.OK
                )
            }.isInstanceOf(NoMatchingScenario::class.java)
        }

        @Test
        fun `xml contract with optional node`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/users':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: user
                          properties:
                            id:
                              type: integer
                            name:
                              type: string
                          required:
                            - id
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<user><id>10</id></user>"""

            assertMatchesSnippet(xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with attributes in child node`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/users':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: user
                          properties:
                            id:
                              type: integer
                            address:
                              type: object
                              properties:
                                street:
                                  type: string
                                pincode:
                                  type: string
                                  xml:
                                    attribute: true
                              required:
                                - street
                                - pincode
                          required:
                            - id
                            - address
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet =
                """<user><id>10</id><address pincode="101010"><street>Baker street</street></address></user>"""

            assertMatchesSnippet(xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with unwrapped xml node array marked required`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: products
                          properties:
                            id:
                              type: array
                              items:
                                type: number
                          required:
                            - id
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><id>10</id><id>10</id></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with unwrapped xml node array which is not marked required`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: products
                          properties:
                            id:
                              type: array
                              items:
                                type: number
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><id>10</id><id>10</id></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with unwrapped xml node array that specifies it's own name`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: products
                          properties:
                            id:
                              type: array
                              items:
                                type: number
                                xml:
                                  name: productid
                          required:
                            - id
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><productid>10</productid><productid>10</productid></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with wrapped xml node array that specifies wrapper name`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: array
                          items:
                            type: number
                          xml:
                            wrapped: true
                            name: products
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><products>10</products><products>10</products></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with wrapped xml node array that does not specify wrapper name`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/products'
            components:
              schemas:
                products:
                  type: array
                  items:
                    type: number
                    xml:
                      name: id
                  xml:
                    wrapped: true
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><id>10</id><id>10</id></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with wrapped xml node array that changes wrapper name but not member element name`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/product'
            components:
              schemas:
                product:
                  type: array
                  items:
                    type: number
                  xml:
                    wrapped: true
                    name: products
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><products>10</products><products>10</products></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with wrapped xml node array that sets the array name without setting wrapper to true`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/product'
            components:
              schemas:
                product:
                  type: object
                  properties:
                    productdata:
                      type: array
                      items:
                        type: number
                      xml:
                        name: products
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<product><productdata>10</productdata><productdata>10</productdata></product>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with wrapped xml node array defined in an object that sets the array name and sets wrapper to true`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/productdata'
            components:
              schemas:
                productdata:
                  type: object
                  properties:
                    productinner:
                      type: array
                      items:
                        type: number
                      xml:
                        name: products
                        wrapped: true
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet =
                """<productdata><products><products>10</products><products>10</products></products></productdata>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with body defined by ref having xml name`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/RequestBody'
            components:
              schemas:
                RequestBody:
                  type: object
                  xml:
                    name: products
                  properties:
                    id:
                      type: number
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><id>10</id></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with body defined by ref having no xml name`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/products'
            components:
              schemas:
                products:
                  type: object
                  properties:
                    id:
                      type: number
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><id>10</id></products>"""

            assertMatchesSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with xml response body`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/cart':
                get:
                  responses:
                    '200':
                      description: OK
                      content:
                        application/xml:
                          schema:
                            ${'$'}ref: '#/components/schemas/products'
            components:
                  schemas:
                    products:
                      type: object
                      properties:
                        id:
                          type: number
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<products><id>10</id></products>"""

            assertMatchesResponseSnippet("/cart", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with xml ref within an xml payload`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                get:
                  responses:
                    '200':
                      description: OK
                      content:
                        application/xml:
                          schema:
                            type: object
                            xml:
                              name: user
                            properties:
                              id:
                                type: number
                              company:
                                type: object
                                properties:
                                  id:
                                    type: number
                                  address:
                                    ${"$"}ref: '#/components/schemas/AddressData' 
            components:
                  schemas:
                    AddressData:
                      type: object
                      properties:
                        flat:
                          type: string
                        street:
                          type: string
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet =
                """<user><id>10</id><company><id>100</id><address><flat>221B</flat><street>Baker Street</street></address></company></user>"""

            assertMatchesResponseSnippet("/user", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with xml ref within an xml payload nested at the second level`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                get:
                  responses:
                    '200':
                      description: OK
                      content:
                        application/xml:
                          schema:
                            type: object
                            xml:
                              name: user
                            properties:
                              id:
                                type: number
                              address:
                                ${"$"}ref: '#/components/schemas/AddressData' 
            components:
                  schemas:
                    AddressData:
                      type: object
                      properties:
                        flat:
                          type: string
                        street:
                          type: string
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet =
                """<user><id>10</id><address><flat>221B</flat><street>Baker Street</street></address></user>"""

            assertMatchesResponseSnippet("/user", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with array items specified as ref`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: users
                          properties:
                            productid:
                              type: array
                              xml:
                                name: user
                              items:
                                ${"$"}ref: '#/components/schemas/UserData'
            components:
              schemas:
                UserData:
                  type: object
                  properties:
                    id:
                      type: number
                    name:
                      type: string
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet =
                """<users><user><id>10</id><name>John Doe</name></user><user><id>20</id><name>Jane Doe</name></user></users>"""

            assertMatchesSnippet("/user", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with value specified as ref pointing to an array type`() {
            val xmlContract1 = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: users
                          properties:
                            user:
                              ${'$'}ref: '#/components/schemas/UserArray'
                              type: array
                              items:
                                ${"$"}ref: '#/components/schemas/UserData'
            components:
              schemas:
                UserData:
                  type: object
                  properties:
                    id:
                      type: number
                    name:
                      type: string
                UserArray:
                  type: array
                  items:
                    ${"$"}ref: '#/components/schemas/UserData'
                  xml:
                    name: user
        """.trimIndent()

            val xmlContract2 = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: users
                          properties:
                            user:
                              ${'$'}ref: '#/components/schemas/UserArray'
                              type: array
                              xml:
                                name: user
                              items:
                                ${"$"}ref: '#/components/schemas/UserData'
            components:
              schemas:
                UserData:
                  type: object
                  properties:
                    id:
                      type: number
                    name:
                      type: string
                UserArray:
                  type: array
                  items:
                    ${"$"}ref: '#/components/schemas/UserData'
        """.trimIndent()

            for (xmlContract in listOf(xmlContract1, xmlContract2)) {
                val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

                val xmlSnippet =
                    """<users><user><id>10</id><name>John Doe</name></user><user><id>20</id><name>Jane Doe</name></user></users>"""

                assertMatchesSnippet("/user", xmlSnippet, xmlFeature)
            }
        }

        @Test
        fun `xml contract with recursive type definition`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/user'
            components:
              schemas:
                user:
                  type: object
                  properties:
                    id:
                      type: number
                    name:
                      type: string
                    next:
                      ${'$'}ref: '#/components/schemas/user'
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet =
                """<user><id>10</id><name>John Doe</name><next><id>20</id><name>Jane Doe</name></next></user>"""

            assertMatchesSnippet("/user", xmlSnippet, xmlFeature)
        }

        @Test
        fun `xml contract with prefix`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: user
                            prefix: test
                          properties:
                            id:
                              type: number
                            name:
                              type: string
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet = """<test:user><id>10</id><name>John Doe</name></test:user>"""

            assertMatchesSnippet("/user", xmlSnippet, xmlFeature)

            val body = xmlFeature.scenarios.first().httpRequestPattern.body as XMLPattern
            assertThat(body.pattern.realName).isEqualTo("test:user")
        }

        @Test
        fun `xml contract with prefix and namespace`() {
            val xmlContract = """
            openapi: 3.0.3
            info:
              title: test-xml
              version: '1.0'
            paths:
              '/user':
                post:
                  responses:
                    '200':
                      description: OK
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          type: object
                          xml:
                            name: user
                            prefix: test
                            namespace: 'http://helloworld.com'
                          properties:
                            id:
                              type: number
                            name:
                              type: string
        """.trimIndent()

            val xmlFeature = OpenApiSpecification.fromYAML(xmlContract, "").toFeature()

            val xmlSnippet =
                """<test:user xmlns:test="http://helloworld.com"><id>10</id><name>John Doe</name></test:user>"""

            assertMatchesSnippet("/user", xmlSnippet, xmlFeature)

            val body = xmlFeature.scenarios.first().httpRequestPattern.body as XMLPattern
            assertThat(body.pattern.realName).isEqualTo("test:user")
            val testNamespaceAttribute = body.pattern.attributes.getValue("xmlns:test") as ExactValuePattern
            assertThat(testNamespaceAttribute.pattern.toStringLiteral()).isEqualTo("http://helloworld.com")
        }

        private fun assertMatchesSnippet(xmlSnippet: String, xmlFeature: Feature) {
            assertMatchesSnippet("/users", xmlSnippet, xmlFeature)
        }

        private fun assertMatchesSnippet(path: String, xmlSnippet: String, xmlFeature: Feature) {
            val request = HttpRequest("POST", path, body = parsedValue(xmlSnippet))
            val stubData = xmlFeature.matchingStub(request, HttpResponse.OK)

            val stubMatchResult =
                stubData.requestType.body.matches(parsedValue(xmlSnippet), xmlFeature.scenarios.first().resolver)

            assertThat(stubMatchResult).isInstanceOf(Result.Success::class.java)
        }

        private fun assertMatchesResponseSnippet(path: String, xmlSnippet: String, xmlFeature: Feature) {
            val request = HttpRequest("GET", path)
            val stubData = xmlFeature.matchingStub(
                request,
                HttpResponse(200, headers = mapOf(CONTENT_TYPE to "application/xml"), body = parsedValue(xmlSnippet))
            )

            val stubMatchResult =
                stubData.responsePattern.body.matches(parsedValue(xmlSnippet), xmlFeature.scenarios.first().resolver)

            assertThat(stubMatchResult).isInstanceOf(Result.Success::class.java)
        }

    }

    @Test
    fun `support for exporting values from a wrapper spec file`(@TempDir(cleanup = CleanupMode.ALWAYS) tempDir: File) {
        val openAPI = """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          required:
                          - "id"
                          properties:
                            id:
                              type: "string"
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: integer
            """.trimIndent()

        val openAPIFile = tempDir.resolve("person.yaml")
        openAPIFile.writeText(openAPI)

        val spec = """
            Feature: Person API
            
              Background:
                Given openapi ./person.yaml
                
              Scenario Outline: Person API
                When POST /person
                Then status 200
                And export id = response-body.id
                
                Examples:
                | id |
                | 10 |
        """.trimIndent()

        val specFile = tempDir.resolve("person.spec")
        specFile.writeText(spec)

        val feature = parseContractFileToFeature(specFile)

        val testScenario = feature.generateContractTestScenarios(emptyList()).toList().map { it.second.value }.single()

        assertThat(testScenario.bindings).containsEntry("id", "response-body.id")
    }

    @Test
    fun `support for multipart form data tests`(@TempDir(cleanup = CleanupMode.ALWAYS) tempDir: File) {
        val openAPI = """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data_csv:
                post:
                  summary: "Save data"
                  parameters: []
                  requestBody:
                    ${'$'}ref: '#/components/requestBodies/SaveData'
                  responses:
                    200:
                      description: "Get product by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              requestBodies:
                SaveData:
                  content:
                      multipart/form-data:
                        encoding:
                          csv:
                            contentType: text/csv
                        schema:
                          ${'$'}ref: '#/components/schemas/CsvContent'
              schemas:
                CsvContent:
                  type: object
                  required:
                    - "csv"
                  properties:
                    csv:
                      type: "string"
            """.trimIndent()

        val openAPIFile = tempDir.resolve("data.yaml")
        openAPIFile.writeText(openAPI)

        val csvFile = tempDir.resolve("data.csv")
        val csvFileContent = "1,2,3"
        csvFile.writeText(csvFileContent)

        val spec = """
            Feature: Data API
            
              Background:
                Given openapi data.yaml
                
              Scenario Outline: Save data
                When POST /data_csv
                Then status 200
                
                Examples:
                | csv         |
                | (@${csvFile.canonicalPath}) |
        """.trimIndent()

        val specFile = tempDir.resolve("data.spec")
        specFile.writeText(spec)

        val feature = parseContractFileToFeature(specFile)

        val testScenario = feature.generateContractTestScenarios(emptyList()).toList().map { it.second.value }.single()

        val requestPattern = testScenario.httpRequestPattern
        assertThat(requestPattern.multiPartFormDataPattern.single().name).isEqualTo("csv")
        assertThat(requestPattern.multiPartFormDataPattern.single().contentType).isEqualTo("text/csv")

        val generatedValue: MultiPartContentValue =
            requestPattern.multiPartFormDataPattern.single().generate(Resolver()) as MultiPartContentValue
        assertThat(generatedValue.content.toStringLiteral()).isEqualTo(csvFileContent)
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    inner class MultiPartRequestBody {
        private val openAPI = """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data_csv:
                post:
                  summary: "Save data"
                  parameters: []
                  requestBody:
                    content:
                      multipart/form-data:
                        encoding:
                          csv:
                            contentType: text/csv
                        schema:
                          required:
                          -  csv
                          properties:
                            csv:
                              type: "string"
                  responses:
                    200:
                      description: "Get product by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()

        @Test
        fun `should make multipart type optional or non-optional as per the schema`() {
            val openAPINonOptional = """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data_csv:
                post:
                  summary: "Save data"
                  parameters: []
                  requestBody:
                    content:
                      multipart/form-data:
                        encoding:
                          csv:
                            contentType: text/csv
                        schema:
                          required:
                          -  csv
                          properties:
                            csv:
                              type: "string"
                  responses:
                    200:
                      description: "Get product by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()

            OpenApiSpecification.fromYAML(openAPINonOptional, "").toFeature().let {
                assertThat(it.scenarios.single().httpRequestPattern.multiPartFormDataPattern.single().name).doesNotEndWith(
                    "?"
                )
            }

            val openAPIOptional = """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data_csv:
                post:
                  summary: "Save data"
                  parameters: []
                  requestBody:
                    content:
                      multipart/form-data:
                        encoding:
                          csv:
                            contentType: text/csv
                        schema:
                          properties:
                            csv:
                              type: "string"
                  responses:
                    200:
                      description: "Get product by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent()

            OpenApiSpecification.fromYAML(openAPIOptional, "").toFeature().let {
                assertThat(it.scenarios.single().httpRequestPattern.multiPartFormDataPattern.single().name).endsWith("?")
            }
        }

        @Test
        fun `support for multipart form data file stub`(@TempDir(cleanup = CleanupMode.ALWAYS) tempDir: File) {
            val openAPIFile = tempDir.resolve("data.yaml")
            openAPIFile.writeText(openAPI)

            val csvFile = tempDir.resolve("data.csv")
            val csvFileContent = "1,2,3"
            csvFile.writeText(csvFileContent)

            val stubContent = """
            {
              "http-request": {
                "method": "POST",
                "path": "/data_csv",
                "multipart-formdata": [{
                  "name": "csv",
                  "content": "(string)",
                  "contentType": "text/csv"
                }]
              },
              "http-response": {
                "status": 200,
                "body": "success"
              }
            }
        """.trimIndent()

            val stubDir = tempDir.resolve("data_examples")
            stubDir.mkdirs()
            val stubFile = stubDir.resolve("stub.json")
            stubFile.writeText(stubContent)

            var testStatus: String

            createStubFromContracts(listOf(openAPIFile.canonicalPath), "localhost", 9000, timeoutMillis = 0).use {
                testStatus = "test ran"

                val request = HttpRequest(
                    method = "POST",
                    path = "/data_csv",
                    multiPartFormData = listOf(
                        MultiPartFileValue("csv", csvFile.canonicalPath, "text/csv")
                    )
                )

                val response = it.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                assertThat(response.body.toStringLiteral()).isEqualTo("success")
            }

            assertThat(testStatus).isEqualTo("test ran")
        }

        @Test
        fun `support for multipart form data file stub and validate content`(@TempDir(cleanup = CleanupMode.ALWAYS) tempDir: File) {
            val openAPIFile = tempDir.resolve("data.yaml")
            openAPIFile.writeText(openAPI)

            val csvFile = tempDir.resolve("data.csv")
            val csvFileContent = "1,2,3"
            csvFile.writeText(csvFileContent)

            val stubContent = """
            {
              "http-request": {
                "method": "POST",
                "path": "/data_csv",
                "multipart-formdata": [
                  {
                    "name": "csv",
                    "content": "1,2,3",
                    "contentType": "text/csv"
                  }
                ]
              },
              "http-response": {
                "status": 200,
                "body": "success"
              }
            }
        """.trimIndent()

            val stubDir = tempDir.resolve("data_examples")
            stubDir.mkdirs()
            val stubFile = stubDir.resolve("stub.json")
            stubFile.writeText(stubContent)

            var testStatus: String

            createStubFromContracts(listOf(openAPIFile.canonicalPath), "localhost", 9000, timeoutMillis = 0).use {
                testStatus = "test ran"

                val request = HttpRequest(
                    method = "POST",
                    path = "/data_csv",
                    multiPartFormData = listOf(
                        MultiPartFileValue("csv", csvFile.canonicalPath, "text/csv")
                    )
                )

                val response = it.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                println(response.body.toStringLiteral())
            }

            assertThat(testStatus).isEqualTo("test ran")
        }

        @Test
        fun `support for multipart form data non-file stub and validate content`(@TempDir(cleanup = CleanupMode.ALWAYS) tempDir: File) {
            val openAPIFile = tempDir.resolve("data.yaml")
            openAPIFile.writeText(openAPI)

            val csvFile = tempDir.resolve("data.csv")
            val csvFileContent = "1,2,3"
            csvFile.writeText(csvFileContent)

            val stubContent = """
            {
              "http-request": {
                "method": "POST",
                "path": "/data_csv",
                "multipart-formdata": [
                  {
                    "name": "csv",
                    "content": "1,2,3",
                    "contentType": "text/csv"
                  }
                ]
              },
              "http-response": {
                "status": 200,
                "body": "success"
              }
            }
        """.trimIndent()

            val stubDir = tempDir.resolve("data_examples")
            stubDir.mkdirs()
            val stubFile = stubDir.resolve("stub.json")
            stubFile.writeText(stubContent)

            var testStatus: String

            createStubFromContracts(listOf(openAPIFile.canonicalPath), "localhost", 9000, timeoutMillis = 0).use {
                testStatus = "test ran"

                val request = HttpRequest(
                    method = "POST",
                    path = "/data_csv",
                    multiPartFormData = listOf(
                        MultiPartContentValue("csv", StringValue("1,2,3"), specifiedContentType = "text/csv")
                    )
                )

                val response = it.client.execute(request)

                assertThat(response.status).isEqualTo(200)
                println(response.body.toStringLiteral())
            }

            assertThat(testStatus).isEqualTo("test ran")
        }
    }

    @Test
    fun `recognizes null value`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      responses:
                        '200':
                          description: OK
                      requestBody:
                        content:
                          application/json:
                            schema:
                              ${'$'}ref: '#/components/schemas/user'
                components:
                  schemas:
                    user:
                      type: object
                      properties:
                        id:
                          nullable: true
                      required:
                        - id
            """.trimIndent()

        val contract: Feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()

        val scenario = contract.scenarios.single()
        val resolver = scenario.resolver

        val requestPattern = scenario.httpRequestPattern

        val matchingRequest = HttpRequest("POST", "/users", body = parsedJSON("""{"id": null}"""))
        assertThat(requestPattern.matches(matchingRequest, resolver)).isInstanceOf(Result.Success::class.java)

        val nonMatchingRequest = HttpRequest("POST", "/users", body = parsedJSON("""{"id": 10}"""))
        assertThat(requestPattern.matches(nonMatchingRequest, resolver)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should generate tests for inline payload definitions with examples`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                200_OK:
                                  value:
                      requestBody:
                        content:
                          application/json:
                            examples:
                              200_OK:
                                value:
                                    id: abc123
                            schema:
                              type: object
                              properties:
                                id:
                                  type: string
                              required:
                                - id
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()

        val results: List<Result> =
            feature.generateContractTests(emptyList()).toList().map {
                it.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.body).isInstanceOf(JSONObjectValue::class.java)

                        val body = request.body as JSONObjectValue
                        assertThat(body.jsonObject).hasSize(1)
                        assertThat(body.jsonObject).containsEntry("id", StringValue("abc123"))
                        return HttpResponse.OK
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }).first
            }

        assertThat(results).hasSize(1)

        assertThat(results[0]).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should generate tests for form fields with examples`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                200_OK:
                                  value:
                      requestBody:
                        content:
                          application/x-www-form-urlencoded:
                             examples:
                               200_OK:
                                 value:
                                     Data:
                                       id: abc123
                             encoding:
                               Data:
                                 contentType: application/json
                             schema:
                               type: object
                               properties:
                                 Data:
                                   type: object
                                   properties:
                                     id:
                                       type: string
                                   required:
                                     - id
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()

        val results: List<Result> =
            feature.generateContractTests(emptyList()).toList().map {
                it.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.formFields).containsKey("Data")

                        var parsedValue: Value = JSONObjectValue()
                        assertThatCode {
                            parsedValue = parsedJSON(request.formFields["Data"]!!)
                        }.doesNotThrowAnyException()

                        assertThat((parsedValue as JSONObjectValue).jsonObject).containsEntry(
                            "id",
                            StringValue("abc123")
                        )
                        assertThat(request.formFields).hasSize(1)
                        return HttpResponse.OK
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }).first
            }

        assertThat(results).hasSize(1)
        println(results.single().reportString())

        assertThat(results.single()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should generate tests for form fields with examples when the fields are in a ref`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                200_OK:
                                  value:
                      requestBody:
                        content:
                          application/x-www-form-urlencoded:
                             examples:
                               200_OK:
                                 value:
                                     Data: abc123
                             schema:
                               ${"$"}ref: '#/components/schemas/Data'
                components:
                  schemas:
                    Data:
                      type: object
                      properties:
                        Data:
                          type: string
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()

        val results: List<Result> =
            feature.generateContractTests(emptyList()).toList().map {
                it.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.formFields).containsKey("Data")
                        assertThat(request.formFields["Data"]).isEqualTo("abc123")
                        assertThat(request.formFields).hasSize(1)

                        return HttpResponse.OK
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }).first
            }

        assertThat(results).hasSize(1)
        println(results.single().reportString())

        assertThat(results.single()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should generate tests for multipart fields with examples`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                200_OK:
                                  value:
                      requestBody:
                        content:
                          multipart/form-data:
                             examples:
                               200_OK:
                                 value:
                                   Data: abc123
                             schema:
                               type: object
                               properties:
                                 Data:
                                   type: string
                               required:
                                 - Data
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()

        val results: List<Result> =
            feature.generateContractTests(emptyList()).toList().map {
                it.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.multiPartFormData.first().name).isEqualTo("Data")

                        val content = request.multiPartFormData.first() as MultiPartContentValue
                        assertThat(content.content.toStringLiteral()).isEqualTo("abc123")

                        assertThat(request.multiPartFormData).hasSize(1)
                        return HttpResponse.OK
                    }

                    override fun setServerState(serverState: Map<String, Value>) {
                    }
                }).first
            }

        assertThat(results).hasSize(1)
        println(results.single().reportString())

        assertThat(results.single()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `nullable oneOf ref in yaml`() {
        val openAPIText = """
            ---
            openapi: "3.0.1"
            info:
              title: "Test"
              version: "1"
            paths:
              /user:
                post:
                  summary: "Test"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "location"
                          properties:
                            location:
                              oneOf:
                              - nullable: true
                              - ${'$'}ref: '#/components/schemas/Address'
                              - ${'$'}ref: '#/components/schemas/LatLong'
                  responses:
                    "200":
                      description: "Test"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "street"
                  properties:
                    street:
                      type: "string"
                LatLong:
                  required:
                  - "latitude"
                  - "longitude"
                  properties:
                    latitude:
                      type: "number"
                    longitude:
                      type: "number"
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPIText, "").toFeature()

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"location": {"street": "Baker Street"}}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"location": {"latitude": 51.523160, "longitude": -0.158070}}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"location": null}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")
    }

    @Test
    fun `non-nullable oneOf ref in yaml`() {
        val openAPIText = """
            ---
            openapi: "3.0.1"
            info:
              title: "Test"
              version: "1"
            paths:
              /user:
                post:
                  summary: "Test"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - "location"
                          properties:
                            location:
                              oneOf:
                              - ${'$'}ref: '#/components/schemas/Address'
                              - ${'$'}ref: '#/components/schemas/LatLong'
                  responses:
                    "200":
                      description: "Test"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Address:
                  required:
                  - "street"
                  properties:
                    street:
                      type: "string"
                LatLong:
                  required:
                  - "latitude"
                  - "longitude"
                  properties:
                    latitude:
                      type: "number"
                    longitude:
                      type: "number"
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPIText, "").toFeature()

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"location": {"street": "Baker Street"}}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"location": {"latitude": 51.523160, "longitude": -0.158070}}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThatThrownBy {
            feature.matchingStub(
                HttpRequest(
                    "POST",
                    "/user",
                    body = parsedJSON("""{"location": null}""")
                ), HttpResponse.ok("success")
            )
        }.satisfies(Consumer { it.instanceOf(NoMatchingScenario::class) })
    }

    // See https://swagger.io/docs/specification/data-models/oneof-anyof-allof-not/#allof
    @Test
    fun `oneOf with discriminator in yaml`() {
        val openAPIText = """
            ---
            openapi: "3.0.1"
            info:
              title: "Test"
              version: "1"
            paths:
              /pets:
                patch:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: "#/components/schemas/Pet_Polymorphic"
                  responses:
                    "200":
                      description: "updated"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas:
                Pet:
                  type: object
                  required:
                  - pet_type
                  properties:
                    pet_type:
                      type: string
                  discriminator:
                    propertyName: pet_type
                Pet_Polymorphic:
                  oneOf:
                    - ${'$'}ref: '#/components/schemas/Cat'
                    - ${'$'}ref: '#/components/schemas/Dog'
                Dog:
                  allOf:
                  - ${'$'}ref: '#/components/schemas/Pet'
                  - type: object
                    properties:
                      bark:
                        type: boolean
                      breed:
                        type: string
                        enum: [Dingo, Husky]
                Cat:
                  allOf:
                  - ${'$'}ref: '#/components/schemas/Pet'
                  - type: object
                    properties:
                      hunts:
                        type: boolean
                      age:
                        type: integer
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(openAPIText, "").toFeature()

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "PATCH",
                    "/pets",
                    body = parsedJSON("""{"pet_type": "Cat", "age": 3}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "PATCH",
                    "/pets",
                    body = parsedJSON("""{"pet_type": "Dog", "bark": true}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThat(
            feature.matchingStub(
                HttpRequest(
                    "PATCH",
                    "/pets",
                    body = parsedJSON("""{"pet_type": "Dog", "bark": false, "breed": "Dingo"}""")
                ), HttpResponse.ok("success")
            ).response.headers["X-Specmatic-Result"]
        ).isEqualTo("success")

        assertThatThrownBy {
            feature.matchingStub(
                HttpRequest(
                    "PATCH",
                    "/pets",
                    body = parsedJSON("""{"age": 3}""")
                ), HttpResponse.ok("success")
            )
        }.isInstanceOf(NoMatchingScenario::class.java)

        assertThatThrownBy {
            feature.matchingStub(
                HttpRequest(
                    "PATCH",
                    "/pets",
                    body = parsedJSON("""{"pet_type": "Cat", "bark": true}""")
                ), HttpResponse.ok("success")
            )
        }.isInstanceOf(NoMatchingScenario::class.java)
    }

    @Test
    fun `should read WIP tag in OpenAPI paths`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      tags:
                        - WIP
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                id:
                                  type: string
                              required:
                                - id
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()

        assertThat(feature.scenarios.first().ignoreFailure).isTrue()
    }

    @Test
    fun `should not break when there are no tags in OpenAPI paths`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                id:
                                  type: string
                              required:
                                - id
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()

        assertThat(feature.scenarios.first().ignoreFailure).isFalse()
    }

    @Test
    fun `should be able to accept expectations when examples are provided and should not hardcode the request to the specific examples`() {
        val contractString = """
                openapi: 3.0.3
                info:
                  title: test
                  version: '1.0'
                paths:
                  '/users':
                    post:
                      responses:
                        '200':
                          description: OK
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                200_OK:
                                  value: success
                      requestBody:
                        content:
                          application/json:
                            examples:
                                200_OK:
                                  value:
                                    id: "abc123"
                            schema:
                              type: object
                              properties:
                                id:
                                  type: string
                              required:
                                - id
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(contractString, "").toFeature()
        val match: List<Pair<Scenario, Result>> = feature.compatibilityLookup(
            HttpRequest(
                "POST",
                "/users",
                body = parsedJSONObject("""{"id": "xyz789"}""")
            )
        )

        val result = match.single().second
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `two APIs with IDs in the URL should be merged into one API`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API 1
              Given type RequestBody
              | hello | (string) |
              When POST /data/10
              And request-body (RequestBody)
              Then status 200

            Scenario: API 2
              Given type RequestBody
              | hello | (string) |
              When POST /data/20
              And request-body (RequestBody)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data/30",
                        body = parsedJSON("""{"hello": "Jill"}""")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data/{id}:
                post:
                  summary: API 1
                  parameters:
                  - name: id
                    in: path
                    required: true
                    schema:
                      type: number
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/1_RequestBody'
                    required: true
                  responses:
                    200:
                      description: API 1
            components:
              schemas:
                1_RequestBody:
                  required:
                  - hello
                  properties:
                    hello:
                      type: string
              """.trimIndent()
        )
    }

    @Test
    fun `a single API with an ID in the URL should be turn into a URL matcher with an id path param`() {
        val feature = parseGherkinStringToFeature(
            """
            Feature: API
            
            Scenario: API 1
              Given type RequestBody
              | hello | (string) |
              When POST /data/10
              And request-body (RequestBody)
              Then status 200
            """.trimIndent()
        )
        val openAPI = feature.toOpenApi()

        with(OpenApiSpecification("/file.yaml", openAPI).toFeature()) {
            assertThat(
                this.matches(
                    HttpRequest(
                        "POST",
                        "/data/30",
                        body = parsedJSON("""{"hello": "Jill"}""")
                    ), HttpResponse.OK
                )
            ).isTrue
        }

        val openAPIYaml = openAPIToString(openAPI)
        portableComparisonAcrossBuildEnvironments(
            openAPIYaml,
            """
            ---
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /data/{id}:
                post:
                  summary: API 1
                  parameters:
                  - name: id
                    in: path
                    required: true
                    schema:
                      type: number
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/1_RequestBody'
                    required: true
                  responses:
                    200:
                      description: API 1
            components:
              schemas:
                1_RequestBody:
                  required:
                  - hello
                  properties:
                    hello:
                      type: string
              """.trimIndent()
        )
    }

    @Test
    fun `should log messages from the parser when parsing fails`() {
        val contractHasBadDescriptionInResponseSchema = """
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
            text/plain:
              schema:
              description: Says hello
                type: string
        """.trimIndent()

        val testLogger = object : LogStrategy {
            val messages = mutableListOf<String>()
            override val printer: CompositePrinter
                get() = TODO("Not yet implemented")
            override var infoLoggingEnabled: Boolean
                get() = true
                set(value) {}

            override fun keepReady(msg: LogMessage) {
                TODO("Not yet implemented")
            }

            override fun exceptionString(e: Throwable, msg: String?): String {
                TODO("Not yet implemented")
            }

            override fun ofTheException(e: Throwable, msg: String?): LogMessage {
                TODO("Not yet implemented")
            }

            override fun log(e: Throwable, msg: String?) {
                TODO("Not yet implemented")
            }

            override fun log(msg: String) {
                messages.add(msg)
            }

            override fun log(msg: LogMessage) {
                TODO("Not yet implemented")
            }

            override fun logError(e: Throwable) {
                TODO("Not yet implemented")
            }

            override fun newLine() {
                TODO("Not yet implemented")
            }

            override fun debug(msg: String): String {
                TODO("Not yet implemented")
            }

            override fun debug(msg: LogMessage) {
                TODO("Not yet implemented")
            }

            override fun debug(e: Throwable, msg: String?) {
                TODO("Not yet implemented")
            }

            override fun <T> withIndentation(count: Int, block: () -> T): T {
                return block()
            }

            override fun boundary() {
                TODO("Not yet implemented")
            }
        }

        ignoreButLogException {
            OpenApiSpecification.fromYAML(contractHasBadDescriptionInResponseSchema, "", testLogger)
        }

        assertThat(testLogger.messages).isNotEmpty
    }

    @Test
    fun `should log messages from the parser when parsing does not fail`() {
        val contractHasBadDescriptionInResponseSchema = """
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
  /data:
    post:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      requestBody:
        content:
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent()

        val testLogger = object : LogStrategy {
            val messages = mutableListOf<String>()
            override val printer: CompositePrinter
                get() = TODO("Not yet implemented")
            override var infoLoggingEnabled: Boolean
                get() = true
                set(value) {}

            override fun keepReady(msg: LogMessage) {
                TODO("Not yet implemented")
            }

            override fun exceptionString(e: Throwable, msg: String?): String {
                TODO("Not yet implemented")
            }

            override fun ofTheException(e: Throwable, msg: String?): LogMessage {
                TODO("Not yet implemented")
            }

            override fun log(e: Throwable, msg: String?) {
                TODO("Not yet implemented")
            }

            override fun log(msg: String) {
                println(msg)
                messages.add(msg)
            }

            override fun log(msg: LogMessage) {
                TODO("Not yet implemented")
            }

            override fun logError(e: Throwable) {
                TODO("Not yet implemented")
            }

            override fun newLine() {
                TODO("Not yet implemented")
            }

            override fun debug(msg: String): String {
                TODO("Not yet implemented")
            }

            override fun debug(msg: LogMessage) {
                TODO("Not yet implemented")
            }

            override fun debug(e: Throwable, msg: String?) {
                TODO("Not yet implemented")
            }

            override fun <T> withIndentation(count: Int, block: () -> T): T {
                return block()
            }
        }

        ignoreButLogException {
            OpenApiSpecification.fromYAML(contractHasBadDescriptionInResponseSchema, "", testLogger)
        }

        assertThat(testLogger.messages).isNotEmpty
    }

    @Test
    fun `nullable empty object should translate to null when found in oneOf`() {
        val contract = OpenApiSpecification.fromYAML(
            """
---
openapi: "3.0.1"
info:
  title: "Person API"
  version: "1"
paths:
  /person:
    post:
      summary: "Add person by id"
      parameters: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              required:
              - "address"
              - "id"
              properties:
                id:
                  type: "string"
                address:
                  oneOf:
                    - properties: {}
                      nullable: true
                    - type: string
      responses:
        200:
          description: "Add person by id"
          content:
            text/plain:
              schema:
                type: "string"
""".trimIndent(), ""
        ).toFeature()

        val requestBodyType = contract.scenarios.first().httpRequestPattern.body as JSONObjectPattern
        val addressType = requestBodyType.pattern["address"] as AnyPattern

        assertThat(addressType.pattern).hasSize(2)
        assertThat(NullPattern).isIn(addressType.pattern)
        assertThat(StringPattern()).isIn(addressType.pattern)
    }

    @Test
    fun `should be possible to have two stubs of authorization header with different values`() {
        val specification = """
            openapi: 3.0.1
            info:
              title: New Feature
              version: "1"
            paths:
              /test:
                post:
                  summary: auth
                  parameters:
                    - in: header
                      name: Authorization
                      schema:
                        type: string
                      required: true
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Request'
                    required: true
                  responses:
                    "200":
                      description: New scenario
                      content:
                        text/plain:
                          schema:
                            type: string
                    "400":
                      description: New scenario
                      content:
                        text/plain:
                          schema:
                            type: string
            components:
              schemas:
                Request:
                  type: object
                  required:
                    - item
                  properties:
                    item:
                      type: string

        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "/file.yaml").toFeature()

        val validAuthStub = ScenarioStub(
            HttpRequest(
                "POST",
                "/test",
                mapOf("Authorization" to "valid"),
                body = parsedJSONObject("""{"item": "data"}""")
            ),
            HttpResponse.ok("success")
        )

        val invalidAuthStub = ScenarioStub(
            HttpRequest(
                "POST",
                "/test",
                mapOf("Authorization" to "invalid"),
                body = parsedJSONObject("""{"item": "data"}""")
            ),
            HttpResponse(400, "failed")
        )

        HttpStub(feature, listOf(invalidAuthStub, validAuthStub)).use { stub ->
            val request = HttpRequest(
                "POST",
                "/test",
                body = parsedJSONObject("""{"item": "data"}""")
            )

            with(stub.client.execute(request.copy(headers = mapOf("Authorization" to "valid")))) {
                assertThat(this.body.toStringLiteral()).isEqualTo("success")
                assertThat(this.status).isEqualTo(200)
            }

            with(stub.client.execute(request.copy(headers = mapOf("Authorization" to "invalid")))) {
                assertThat(this.body.toStringLiteral()).isEqualTo("failed")
                assertThat(this.status).isEqualTo(400)
            }
        }
    }

    @Test
    fun `validate the second element in a list`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: array
                          items:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: string
                              name:
                                type: string
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas: {}
        """.trimIndent(), ""
        ).toFeature()

        with(feature) {
            val result =
                this.scenarios.first().matchesMock(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""[{"id": "123", "name": "Jack Sprat"}, {"id": "456"}]""")
                    ), HttpResponse.ok("success")
                )

            assertThat(result.reportString().trimmedLinesString()).isEqualTo(
                """
                >> REQUEST.BODY[1].name

                   Expected key named "name" was missing
            """.trimIndent().trimmedLinesString()
            )
        }
    }

    @Test
    fun `validate a nullable array`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: array
                          items:
                            type: object
                            required:
                              - id
                              - names
                            properties:
                              id:
                                type: string
                              names:
                                type: array
                                nullable: true
                                items:
                                  type: string
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
            components:
              schemas: {}
        """.trimIndent(), ""
        ).toFeature()

        with(feature) {
            val result =
                this.scenarios.first().matchesMock(
                    HttpRequest(
                        "POST",
                        "/person",
                        body = parsedJSON("""[{"id": "123", "names": ["Jack", "Sprat"]}, {"id": "456", "names": null}]""")
                    ), HttpResponse.ok("success")
                )

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `randomized response when stubbing out API with byte array request body`() {
        val specification = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Add data"
                  requestBody:
                    content:
                      application/octet-stream:
                        schema:
                          type: string
                          format: byte
                  responses:
                    200:
                      description: "Result"
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent(), ""
        ).toFeature()

        HttpStub(specification).use { stub ->
            val base64EncodedRequestBody = Base64.getEncoder().encodeToString("hello world".encodeToByteArray())

            val response = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/data",
                    headers = mapOf(CONTENT_TYPE to "application/octet-stream"),
                    body = StringValue(base64EncodedRequestBody)
                )
            )

            assertThat(response.status).isEqualTo(200)
        }
    }

    @Test
    fun `stubbed response when stubbing out API with byte array request body`() {
        val specification = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Add data"
                  requestBody:
                    content:
                      application/octet-stream:
                        schema:
                          type: string
                          format: byte
                  responses:
                    200:
                      description: "Result"
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent(), ""
        ).toFeature()

        HttpStub(specification).use { stub ->
            val base64EncodedRequestBody = Base64.getEncoder().encodeToString("hello world".encodeToByteArray())

            val stubbedRequest = HttpRequest(
                method = "POST",
                path = "/data",
                headers = mapOf(
                    "Content-Type" to "application/octet-stream"
                ),
                body = StringValue(base64EncodedRequestBody)
            )

            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/_specmatic/expectations",
                    body = StringValue(
                        """
                        {
                            "http-request": {
                                "method": "POST",
                                "path": "/data",
                                "body": "$base64EncodedRequestBody"
                            },
                            "http-response": {
                                "status": 200,
                                "body": "success"
                            }
                        }
                    """.trimIndent()
                    )
                )
            ).also { response ->
                assertThat(response.status).isEqualTo(200)
            }

            val response = stub.client.execute(stubbedRequest)
            assertThat(response.status).withFailMessage("Got a non-200 status which means that the stub did not respond to the request")
                .isEqualTo(200)
            assertThat(response.body.toStringLiteral()).withFailMessage("Did not get success, most likely got a random response, which means that the stubbed response was not returned")
                .isEqualTo("success")
        }
    }

    @Test
    fun `cannot stub out non-base64 request for a byte array request body`() {
        val specification = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Add data"
                  requestBody:
                    content:
                      application/octet-stream:
                        schema:
                          type: string
                          format: byte
                  responses:
                    200:
                      description: "Result"
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent(), ""
        ).toFeature()

        HttpStub(specification).use { stub ->
            val vanillaNonBase64Request = "]"

            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/_specmatic/expectations",
                    body = StringValue(
                        """
                        {
                            "http-request": {
                                "method": "POST",
                                "path": "/data",
                                "body": "$vanillaNonBase64Request"
                            },
                            "http-response": {
                                "status": 200,
                                "body": "success"
                            }
                        }
                    """.trimIndent()
                    )
                )
            ).also { response ->
                assertThat(response.status).isEqualTo(400)
            }
        }
    }

    @Test
    fun `test request of type byte array request body sends a random base64 request value`() {
        val specification = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Add data"
                  requestBody:
                    content:
                      application/octet-stream:
                        schema:
                          type: string
                          format: byte
                  responses:
                    200:
                      description: "Result"
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent(), ""
        ).toFeature()

        val results = specification.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.body.toStringLiteral()).isBase64()
                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `test request of type byte array request body loads sends a base64 example`() {
        val base64EncodedRequestBody = Base64.getEncoder().encodeToString("hello world".encodeToByteArray())

        val specification = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Data API"
              version: "1"
            paths:
              /data:
                post:
                  summary: "Add data"
                  requestBody:
                    content:
                      application/octet-stream:
                        schema:
                          type: string
                          format: byte
                        examples:
                          SUCCESS:
                            value: $base64EncodedRequestBody
                  responses:
                    200:
                      description: "Result"
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SUCCESS:
                              value: success
        """.trimIndent(), ""
        ).toFeature()

        val results = specification.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val expectedRequestBody = "hello world".toByteArray()
                val actualRequestBody = request.body.toStringLiteral()

                assertThat(actualRequestBody).asBase64Decoded().isEqualTo(expectedRequestBody)

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `support for multipart form data part array with a return type byte array string`() {
        val openAPI = """
                openapi: 3.0.3
                info:
                  title: Return type of multipart-form-data with string format byte
                  description: Service to add a test case to a Specmatic feature
                  version: 1.0.0
                tags:
                  - name: UploadFile
                paths:
                  "/file":
                    post:
                      tags:
                        - UploadFile
                      operationId: sendMessage
                      requestBody:
                        content:
                          multipart/form-data:
                            schema:
                              type: object
                              properties:
                                filesPart:
                                  type: string
                                  format: binary
                              required:
                                - filesPart
                      responses:
                        "200":
                          description: "Send Message Response"
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  filename:
                                    type: string
                                    format: byte
            """.trimIndent()

        val specifications = OpenApiSpecification.fromYAML(openAPI, "").toScenarioInfos()
        assertTrue(specifications.first.isNotEmpty())
        with(OpenApiSpecification.fromYAML(openAPI, "").toFeature()) {
            val result =
                this.scenarios.first().matchesMock(
                    HttpRequest(
                        "POST",
                        "/file",
                        multiPartFormData = listOf(
                            MultiPartFileValue(
                                "filesPart",
                                "test.pdf",
                                "application/pdf",
                                "UTF-8"
                            )
                        )
                    ), HttpResponse(200, parsedJSONObject("{\"filename\": \"ThIsi5ByT3sD4tA\"}"))
                )
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `show clear error for non-numerical response codes`() {
        try {
            OpenApiSpecification.fromYAML(
                """
            openapi: 3.0.1
            info:
              title: Random
              version: "1"
            paths:
              /random1:
                post:
                  summary: With examples
                  parameters: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - id
                          properties:
                            id:
                              type: number
                  responses:
                    "2xx":
                      description: Random
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent(), ""
            ).toFeature()
        } catch (e: Throwable) {
            assertThat(exceptionCauseMessage(e)).contains("2xx")
        }
    }

    @Test
    fun `should indicate the xml schema if no properties are found`() {
        assertThatThrownBy {
            OpenApiSpecification.fromYAML(
                """
openapi: "3.0.3"
info:
  description: This documentation contains REST API contract details
  version: "1.0.0"
  title: REST API
paths:
  /xyz:
    post:
      tags:
        - V1
      summary: Post an entry
      description: Post an entry
      operationId: postEntry
      requestBody:
        content:
          application/xml:
            schema:
              ${"$"}ref: '#/components/schemas/PostEntryXmlRequest'
      responses:
        '202':
          description: Accepted
components:
  schemas:
    PostEntryXmlRequest:
      title: PostEntryXmlRequest
      type: object
      xml:
        name: Document
        """.trimIndent(), "").toFeature()
        }.satisfies(
            {
                assertThat(exceptionCauseMessage(it)).contains("Document")
            }
        )
    }

    @Test
    fun `should indicate the xml schema by property name if no properties are found`() {
        assertThatThrownBy {
            OpenApiSpecification.fromYAML(
                """
openapi: "3.0.3"
info:
  description: This documentation contains REST API contract details
  version: "1.0.0"
  title: REST API
paths:
  /xyz:
    post:
      tags:
        - V1
      summary: Post an entry
      description: Post an entry
      operationId: postEntry
      requestBody:
        content:
          application/xml:
            schema:
              ${"$"}ref: '#/components/schemas/PostEntryXmlRequest'
      responses:
        '202':
          description: Accepted
components:
  schemas:
    Id:
      type: object
    PostEntryXmlRequest:
      title: PostEntryXmlRequest
      type: object
      properties:
        id:
          ${"$"}ref: '#/components/schemas/Id'
      xml:
        name: Document
        """.trimIndent(), "").toFeature()
        }.satisfies(
            {
                assertThat(exceptionCauseMessage(it)).withFailMessage(exceptionCauseMessage(it)).contains("Id")
            }
        )
    }

    @Test
    fun `should load externalized test data with name in file`() {
        val specFilePath = "core/src/test/resources/openapi/spec_with_externalized_test_data.yaml"
        val spec = OpenApiSpecification.fromFile(specFilePath, "").toFeature().loadExternalisedExamples()

        val results = spec.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/resource/10")
                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.successCount).isOne()
    }

    @Test
    fun `should load externalized test data using filename when name is not in file`() {
        val specFilePath = "core/src/test/resources/openapi/spec_with_unnamed_externalized_test_data.yaml"
        val spec = OpenApiSpecification.fromFile(specFilePath, "")
            .toFeature()
            .loadExternalisedExamples()

        val tests = spec.generateContractTestScenarios(emptyList()).toList().map { it.second.value }
        assertThat(tests.single().testDescription()).contains("file_name_as_test_label")
    }

    @Test
    fun `should contain the positive test names with details around the enum value picked up as a request header`() {
        val specFilePath = "core/src/test/resources/openapi/spec_with_request_header_as_enum.yaml"
        val spec = OpenApiSpecification.fromFile(specFilePath, "")
            .toFeature()

        val tests = spec.generateContractTestScenarios(emptyList()).toList().map { it.second.value }

        val testDescriptionList = tests.map { it.testDescription() }
        assertThat(testDescriptionList).containsExactlyInAnyOrder(
            " Scenario: GET /items -> 200 [REQUEST.PARAMETERS.HEADER.X-region selected FIRST from enum]",
            " Scenario: GET /items -> 200 [REQUEST.PARAMETERS.HEADER.X-region selected SECOND from enum]",
            " Scenario: GET /items -> 200 [REQUEST.PARAMETERS.HEADER.X-region selected THIRD from enum]"
        )
    }

    @Test
    @Disabled
    fun `should contain the positive test names with details around the enum value picked up as a request query param`() {
        val specFilePath = "core/src/test/resources/openapi/spec_with_request_query_param_as_enum.yaml"
        val spec = OpenApiSpecification.fromFile(specFilePath, "")
            .toFeature()

        val tests = spec.generateContractTestScenarios(emptyList()).toList().map { it.second.value }

        val testDescriptionList = tests.map { it.testDescription() }
        assertThat(testDescriptionList).containsExactlyInAnyOrder(
            " Scenario: GET /items -> 200 [REQUEST.QUERY-PARAMS.region selected FIRST from enum]",
            " Scenario: GET /items -> 200 [REQUEST.QUERY-PARAMS.region selected SECOND from enum]",
            " Scenario: GET /items -> 200 [REQUEST.QUERY-PARAMS.region selected THIRD from enum]"
        )
    }

    @Test
    fun `show an error when examples with no mediaType is found in the request`() {
        assertThatThrownBy {
            OpenApiSpecification.fromYAML(
                """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /api/nocontent:
    post:
      requestBody:
        content:
          application/json:
            example: test data
      responses:
        "204":
          description: No response
""".trimIndent(), ""
            ).toFeature()
        }.satisfies(
            {
                println(exceptionCauseMessage(it))
                assertThat(exceptionCauseMessage(it)).contains("""Request body definition is missing""")
            }
        )
    }

    @Test
    fun `show an error when examples with no mediaType is found in the response`() {
        assertThatThrownBy {
            OpenApiSpecification.fromYAML(
                """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /api/nocontent:
    post:
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                name:
                  description: The name of the entity
      responses:
        "200":
          description: Random
          content:
            text/plain:
              example: sample response
            """.trimIndent(), ""
            ).toFeature()
        }.satisfies(
            {
                println(exceptionCauseMessage(it))
                assertThat(exceptionCauseMessage(it)).contains("""Response body definition is missing""")
            }
        )
    }

    @Test
    fun `show an error when parameter name is not provided`() {
        assertThatThrownBy {
            OpenApiSpecification.fromYAML(
                """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /api/nocontent:
    get:
      parameters:
      - in: query
        schema:
          type: string
      responses:
        204:
          description: No content
            """.trimIndent(), ""
            ).toFeature()
        }.satisfies(
            {
                println(exceptionCauseMessage(it))
                assertThat(exceptionCauseMessage(it)).contains("""A parameter does not have a nam""")
            }
        )
    }

    @Test
    fun `show an error when parameter schema is not provided`() {
        assertThatThrownBy {
            OpenApiSpecification.fromYAML(
                """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /api/nocontent:
    get:
      parameters:
      - in: query
        name: id
      responses:
        204:
          description: No content
            """.trimIndent(), ""
            ).toFeature()
        }.satisfies(
            {
                println(exceptionCauseMessage(it))
                assertThat(exceptionCauseMessage(it)).contains("""A parameter does not have a schema""")
            }
        )
    }

    @Test
    fun `show an error when parameter schema is array and items is not provided`() {
        assertThatThrownBy {
            OpenApiSpecification.fromYAML(
                """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /api/nocontent:
    get:
      parameters:
      - in: query
        name: id
        schema:
          type: array
      responses:
        204:
          description: No content
            """.trimIndent(), ""
            ).toFeature()
        }.satisfies(
            {
                println(exceptionCauseMessage(it))
                assertThat(exceptionCauseMessage(it)).contains("""A parameter of type "array" has not defined "items"""")
            }
        )
    }

    @Test
    fun `should recognize a mandatory query param`() {
        val feature = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /api/nocontent:
    get:
      parameters:
      - in: query
        name: id
        schema:
          type: string
        required: true
      responses:
        200:
          description: Success message
          content:
            text/plain:
              schema:
                type: string
            """.trimIndent(), ""
        ).toFeature()

        val request = HttpRequest("GET", "/api/nocontent", queryParams = QueryParameters(mapOf("id" to "123")))
        feature.matchResult(request, HttpResponse.OK).let { result ->
            assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `backward compatibility should fail when a new mandatory query parameter is added`() {
        val oldSpec = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /data:
    get:
      responses:
        204:
          description: No content
            """.trimIndent(), ""
        ).toFeature()


        val newSpec = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.3
info:
  title: My service
  description: My service
  version: 1.0.0
servers:
  - url: 'https://localhost:8080'
paths:
  /data:
    get:
      parameters:
      - in: query
        name: id
        schema:
          type: integer
        required: true
      responses:
        204:
          description: No content
            """.trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(oldSpec, newSpec)

        assertThat(result.success()).isFalse()

        assertThat(result.report()).contains("expects query param named \"id\"")
    }

    @Test
    fun `requestBody is required by default`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                """.trimIndent(), ""
        ).toFeature()

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "abc123"}""")),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }

        feature.matchResult(
            HttpRequest("POST", "/person", body = NoBodyValue),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun `requestBody can be made required explicitly`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                """.trimIndent(), ""
        ).toFeature()

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "abc123"}""")),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }

        feature.matchResult(
            HttpRequest("POST", "/person", body = NoBodyValue),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun `requestBody can be made optional`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        required: false
                        content:
                          application/json:
                            schema:
                              required:
                              - id
                              properties:
                                id:
                                  type: string
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                """.trimIndent(), ""
        ).toFeature()

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "abc123"}""")),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }

        feature.matchResult(
            HttpRequest("POST", "/person", body = NoBodyValue),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `a JSON key with no type can hold a value of any valid JSON type`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - id
                              properties:
                                id:
                                  description: id of the person
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                """.trimIndent(), ""
        ).toFeature()

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "abc123"}""")),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": 10}""")),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }

        HttpStub(feature).use { stub ->
            val expectedRequest = HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": true}"""))
            val expectedResponse = HttpResponse.ok("succeeded!")

            val expectation = ScenarioStub(expectedRequest, expectedResponse)
            stub.setExpectation(expectation)

            val response = stub.client.execute(expectedRequest)
            assertThat(response.body).isEqualTo(expectedResponse.body)
        }
    }

    @Test
    fun `a JSON key with no type cannot hold a null`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - id
                              properties:
                                id:
                                  description: id of the person
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                """.trimIndent(), ""
        ).toFeature()

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": null}""")),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun `a JSON key with a nullable value of any type can hold a null`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - id
                              properties:
                                id:
                                  description: id of the person
                                  nullable: true
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                """.trimIndent(), ""
        ).toFeature()

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": null}""")),
            HttpResponse.OK
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `a response body with empty content should be considered an empty response`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - id
                              properties:
                                id:
                                  description: id of the person
                                  type: string
                      responses:
                        204:
                          description: "Get person by id"
                          content: {}
                """.trimIndent(), ""
        ).toFeature()

        feature.matchResult(
            HttpRequest("POST", "/person", body = parsedJSONObject("""{"id": "abc123"}""")),
            HttpResponse(204, EmptyString)
        ).let { matchResult ->
            assertThat(matchResult).withFailMessage(matchResult.reportString()).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `minimum maximum and exclusivity keywords in Number and Integer types get wired up`() {
        val minAge = BigDecimal(18.0)
        val maxAge = BigDecimal(120.0)
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - age
                              properties:
                                age:
                                  description: age of the person
                                  type: number
                                  minimum: $minAge
                                  maximum: $maxAge
                                  exclusiveMinimum: true
                                  exclusiveMaximum: true
                      responses:
                        204:
                          description: "Get person by id"
                          content: {}
                        400:
                          description: "Invalid age"
                          content: {}
                """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val actualAges = mutableListOf<BigDecimal>()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val jsonRequestBody = request.body as JSONObjectValue
                return when (val age = jsonRequestBody.jsonObject["age"]) {
                    is NumberValue -> {
                        val ageValue = BigDecimal(age.number.toString())
                        actualAges.add(ageValue)
                        if (minAge < ageValue && ageValue < maxAge)
                            HttpResponse(204, EmptyString)
                        else
                            HttpResponse(400, EmptyString)
                    }

                    else -> HttpResponse(400, EmptyString)
                }
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        val exclusiveMin = minAge + smallInc
        val exclusiveMax = maxAge - smallInc

        val minOutsideBounds = exclusiveMin - smallInc
        val maxOutsideBounds = exclusiveMax + smallInc

        assertThat(actualAges).contains(
            exclusiveMin,
            exclusiveMax,
            minOutsideBounds,
            maxOutsideBounds
        )

        assertThat(results.results.size).isEqualTo(8)
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `400 status named response examples with no corresponding named request example should be ignored`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Data API"
                  version: "1"
                paths:
                  /data:
                    get:
                      summary: "Get data"
                      responses:
                        200:
                          description: "The data"
                          content:
                            text/plain:
                              schema:
                                type: integer
                        400:
                          description: "Could not get the data"
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                FAILED:
                                  value: "failed"
                    """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.ok(NumberValue(10))
            }
        })

        assertThat(results.testCount).isEqualTo(1)
        assertThat(results.success()).isTrue()
    }

    @Test
    fun `references in an XML structure which contains a ref should get dereferenced`() {
        val xmlSpec = """
            openapi: 3.0.0
            info:
              title: Testing API
              version: 1.0.0
              description: |
                Testing XML
            paths:
              /ReqListKeys:
                post:
                  summary: Request list of keys
                  operationId: reqListKeys
                  requestBody:
                    required: true
                    content:
                      application/xml:
                        schema:
                          ${"$"}ref: '#/components/schemas/ReqListKeys'
                  responses:
                    '200':
                      description: Successful response
                      content:
                        application/xml:
                          schema:
                            ${"$"}ref: '#/components/schemas/RespListKeys'
            components:
              schemas:
                ReqListKeys:
                  type: object
                  xml:
                    name: "ReqListKeys"
                    namespace: "http://xyz.org/upi/schema//"
                    prefix: "upi"
                  properties:
                    Head:
                      ${"$"}ref: '#/components/schemas/Head'
                Head:
                  type: object
                  xml:
                    name: "Head"
                  properties:
                    msgId:
                      type: string
                      xml:
                        name: "msgId"
                        attribute: true
                    orgId:
                      type: string
                      xml:
                        name: "orgId"
                        attribute: true
                    prodType:
                      type: string
                      xml:
                        name: "prodType"
                        attribute: true
                    ts:
                      type: string
                      xml:
                        name: "ts"
                        attribute: true
                    ver:
                      type: string
                      enum: [1.0, 2.0]
                      xml:
                        name: "ver"
                        attribute: true
                RespListKeys:
                  type: object
                  xml:
                    name: "RespListKeys"
                    namespace: "http://xyz.org/upi/schema/"
                    prefix: "ns2"
                  properties:
                    Head:
                      ${"$"}ref: '#/components/schemas/Head'

        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(xmlSpec, "").toFeature()

        val rawStub = """
            {
                "http-request": {
                    "path": "/ReqListKeys",
                    "method": "POST",
                    "headers": {
                        "Content-Type": "application/xml"
                    },
                    "body": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<upi:ReqListKeys xmlns:upi=\"http://xyz.org/upi/schema/\">\n    <Head msgId=\"abcde\" orgId=\"157776\" prodType=\"UPI\" ts=\"1970-01-01T05:30:00+05:30\" ver=\"2.0\"/>\n</upi:ReqListKeys>"
                },
                "http-response": {
                    "status": 200,
                    "body": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ns2:RespListKeys xmlns:ns2=\"http://xyz.org/upi/schema/\">\n    <Head msgId=\"syz\" orgId=\"org\" ts=\"2024-05-27T15:35:12+05:30\" ver=\"2.0\"/>\n</ns2:RespListKeys>\n",
                    "status-text": "OK",
                    "headers": {
                        "Content-Type": "application/xml"
                    }
                }
            }
        """.trimIndent()

        val expectation: ScenarioStub = stringToMockScenario(StringValue(rawStub))

        HttpStub(feature, listOf(expectation)).use { stub ->
            val request = expectation.request

            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).contains("msgId")
        }
    }

    @Test
    fun `check that a console warning is printed when a named response example for 4xx has no corresponding named request example`() {
        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(
                """
                    ---
                    openapi: "3.0.1"
                    info:
                      title: "Person API"
                      version: "1"
                    paths:
                      /person:
                        post:
                          summary: "Get person by id"
                          requestBody:
                            content:
                              application/json:
                                schema:
                                  required:
                                  - age
                                  properties:
                                    age:
                                      description: age of the person
                                      type: number
                          responses:
                            400:
                              description: "Get person by id"
                              content:
                                text/plain:
                                  schema:
                                    type: string
                                  examples:
                                    SUCCESSFUL_API_CALL:
                                      value: added
                    """.trimIndent(), ""
            ).toFeature()
        }

        println(stdout)

        val exampleName = "SUCCESSFUL_API_CALL"
        assertThat(stdout)
            .contains("Ignoring response example named $exampleName for test or stub data, because no associated request example named $exampleName was found.")
    }

    @Test
    fun `check that a console warning is printed when a named request example has no corresponding named responsee example`() {
        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(
                """
                    ---
                    openapi: "3.0.1"
                    info:
                      title: "Person API"
                      version: "1"
                    paths:
                      /person:
                        post:
                          summary: "Get person by id"
                          requestBody:
                            content:
                              application/json:
                                schema:
                                  required:
                                  - age
                                  properties:
                                    age:
                                      description: age of the person
                                      type: number
                                examples:
                                  SUCCESSFUL_API_CALL:
                                    value:
                                      age: 10
                          responses:
                            200:
                              description: "Get person by id"
                              content:
                                text/plain:
                                  schema:
                                    type: string
                    """.trimIndent(), ""
            ).toFeature()
        }

        println(stdout)

        val exampleName = "SUCCESSFUL_API_CALL"

        assertThat(stdout)
            .contains("WARNING: Ignoring request example named $exampleName for test or stub data, because no associated response example named $exampleName was found.")
    }

    @Test
    fun `when a header is missing in an expectation the header from the spec should be used`() {
        val defaultSpecmaticConfig = Configuration.configFilePath

        try {
            Configuration.configFilePath = "src/test/resources/openapi/response_expectation_missing_content_type/specmatic.yaml"

            createStub("localhost", 9000, 1000, false, "src/test/resources/openapi/response_expectation_missing_content_type/specmatic.yaml").use { stub ->
                val response = stub.client.execute(
                    HttpRequest("POST", "/person", body = StringValue("hello"))
                )

                val responseContentType = response.headers["Content-Type"]

                assertThat(responseContentType).isEqualTo("text/something_else")

                assertThat(response.body.toStringLiteral()).isEqualTo("world")
            }
        } finally {
            Configuration.configFilePath = defaultSpecmaticConfig
        }
    }

    @Test
    fun `when a workflow exists id of a created entity should be passed on to subsequent API calls`() {
        val spec = """
openapi: 3.0.0
info:
  title: Simple Order API
  version: 1.0.0
  description: A simple API for creating and fetching orders
paths:
  /orders:
    post:
      summary: Create a new order
      requestBody:
        required: true
        content:
          application/json:
            schema:
              ${"$"}ref: '#/components/schemas/Order'
            examples:
              SUCCESSFUL_ORDER:
                value:
                  productid: "abc123"
                  quantity: 10
      responses:
        '201':
          description: Order created successfully
          content:
            application/json:
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: string
              examples:
                SUCCESSFUL_ORDER:
                  value:
                    id: "pqr123"
  /orders/{orderId}:
    get:
      summary: Get order details
      parameters:
        - in: path
          name: orderId
          required: true
          schema:
            type: string
          examples:
            GET_ORDER:
              value: "pqr123"
      responses:
        '200':
          description: Successful response
          content:
            application/json:
              schema:
                ${"$"}ref: '#/components/schemas/Order'
              examples:
                GET_ORDER:
                  value:
                    productid: "abc123"
                    quantity: 10

components:
  schemas:
    Order:
      type: object
      required:
        - productId
        - quantity
      properties:
        productid:
          type: string
        quantity:
          type: integer
          minimum: 1
        """.trimIndent()

        val specmaticConfig = SpecmaticConfig(
            workflow = WorkflowConfiguration(
                mapOf(
                    "POST /orders -> 201" to WorkflowIDOperation(extract = "BODY.id"),
                    "GET /orders/(orderId:string) -> 200" to WorkflowIDOperation(use = "PATH.orderId")
                )
            )
        )
        val feature = OpenApiSpecification.fromYAML(spec, "", specmaticConfig = specmaticConfig).toFeature()

        lateinit var path: String

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.method == "POST")
                    return HttpResponse(201, body = parsedJSONObject("""{"id": "xyzabc"}"""))
                else {
                    path = request.path!!
                    return HttpResponse(200, parsedJSONObject("""{"productid": "pqr123", "quantity": 10}"""))
                }
            }
        })

        assertThat(path).endsWith("/xyzabc")
        assertThat(results.success()).isTrue()
    }

    @Test
    fun `when the content types in header and response code match exactly then map them one to one in scenarios rather than exploding`() {
        val feature = OpenApiSpecification.fromYAML("""
            openapi: 3.0.3
            info:
              title: Product API
              version: '1.0.0'
              description: |
                This API allows you to create a new product. The POST /products endpoint 
                accepts a product name in both plain text and JSON formats, and responds 
                with the same formats.
              contact:
                name: API Support
                url: https://api.example.com/support
                email: support@example.com

            servers:
              - url: https://api.example.com/v1
                description: Production server
              - url: https://staging-api.example.com/v1
                description: Staging server
            tags:
              - name: create
            paths:
              /products:
                post:
                  summary: Create a new product
                  description: "temp"
                  tags:
                    - create
                  operationId: create-product
                  requestBody:
                    required: true
                    content:
                      text/plain:
                        schema:
                          type: string
                      application/json:
                        schema:
                          type: object
                          required:
                            - product_name
                          properties:
                            product_name:
                              type: string
                  responses:
                    '200':
                      description: Successfully created product
                      content:
                        text/plain:
                          schema:
                            type: string
                        application/json:
                          schema:
                            type: object
                            required:
                              - product_name
                            properties:
                              product_name:
                                type: string
                    '202':
                      description: Successfully created product
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
        """.trimIndent(), "").toFeature()

        assertThat(feature.scenarios.size).isEqualTo(4)

        val requestResponsePairs = feature.scenarios.map {
            it.httpRequestPattern.headersPattern.contentType to it.httpResponsePattern.headersPattern.contentType
        }

        val expected = listOf(
            "text/plain" to "text/plain",
            "application/json" to "application/json",
            "text/plain" to "application/json",
            "application/json" to "application/json",
        )

        assertThat(requestResponsePairs).isEqualTo(expected)

    }

    @Test
    fun `should ignore inline examples when ignoreInlineExamples flag is set in specmatic config`() {
        val feature = OpenApiSpecification.fromYAML(
            """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /person:
                    post:
                      summary: "Get person by id"
                      requestBody:
                        content:
                          application/json:
                            schema:
                              required:
                              - age
                              properties:
                                age:
                                  description: age of the person
                                  type: number
                            examples:
                              SUCCESSFUL_API_CALL:
                                value:
                                  age: 10
                      responses:
                        200:
                          description: "Get person by id"
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                SUCCESSFUL_API_CALL:
                                  value:
                                    age: "person data"
                """.trimIndent(), "",
            specmaticConfig = SpecmaticConfig(ignoreInlineExamples = true)
        ).toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val jsonRequestBody = request.body as JSONObjectValue

                assertThat(jsonRequestBody.findFirstChildByPath("age")?.toStringLiteral()).isNotEqualTo("10")
                return HttpResponse(200, "person data")
            }
        })

        assertThat(results.testCount).isPositive()
        assertThat(results.success()).isTrue()
    }

    @Test
    fun `missing request example should still pick up valid 2xx response example`() {
        val spec = """
        ---
        openapi: "3.0.1"
        info:
          title: "Person API"
          version: "1"
        paths:
          /persons:
            get:
              summary: "Get all persons"
              responses:
                200:
                  description: "all persons"
                  content:
                    text/plain:
                      schema:
                        type: "string"
                      examples:
                        SUCCESSFUL_API_CALL:
                          value: "all persons"
        """.trimIndent()
        val name =
            OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().examples.single().rows.single().name
        assertThat(name).isEqualTo("SUCCESSFUL_API_CALL")
    }

    @Test
    fun `missing request example where request has one query param should generate only one test`() {
        val spec = """
        ---
        openapi: "3.0.1"
        info:
          title: "Person API"
          version: "1"
        paths:
          /persons:
            get:
              summary: "Get all persons"
              parameters:
                - in: query
                  name: id
                  schema:
                    type: string
              responses:
                200:
                  description: "all persons"
                  content:
                    text/plain:
                      schema:
                        type: "string"
                      examples:
                        SUCCESSFUL_API_CALL:
                          value: "all persons"
        """.trimIndent()
        val testCount =
            OpenApiSpecification.fromYAML(spec, "").toFeature().generateContractTests(emptyList()).toList().size
        assertThat(testCount).isEqualTo(1)
    }

    @Test
    fun `missing request example should generate still pick up the first valid 2xx response example`() {
        val spec = """
        ---
        openapi: "3.0.1"
        info:
          title: "Person API"
          version: "1"
        paths:
          /persons:
            get:
              summary: "Get all persons"
              parameters:
                - in: query
                  name: id
                  schema:
                    type: string
              responses:
                200:
                  description: "all persons"
                  content:
                    text/plain:
                      schema:
                        type: "string"
                      examples:
                        SUCCESSFUL_API_CALL:
                          value: "all persons"
                201:
                  description: "all persons"
                  content:
                    text/plain:
                      schema:
                        type: "string"
                      examples:
                        201_SUCCESSFUL_API_CALL:
                          value: "all persons"
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val name = feature.scenarios.find { it.httpResponsePattern.status == 200 }?.examples?.single()?.rows?.single()?.name
        assertThat(name).isEqualTo("SUCCESSFUL_API_CALL")
        val testCount = feature.scenarios.find { it.httpResponsePattern.status == 201 }?.examples?.size
        assertThat(testCount).isEqualTo(0)
    }

    @Test
    fun `should use the path parameter example for a no body response`() {
        val openAPI =
            """
---
openapi: 3.0.3
info:
  title: Example API
  description: An API with operations that have no response bodies or headers.
  version: 1.0.0
  contact:
    name: Jack
servers:
  - url: http://prod
tags:
  - name: mod
  - name: read
paths:
  /items/{itemId}:
    delete:
      summary: Delete an item
      operationId: deleteItem
      description: "Delete an item"
      tags:
        - mod
      parameters:
        - name: itemId
          in: path
          required: true
          description: ID of the item to delete
          schema:
            type: string
          examples:
            DELETE_ITEM:
              value: '123-to-be-deleted'
      responses:
        '204':
          description: No Content - The item was successfully deleted
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest(
            "DELETE",
            "/items/123-to-be-deleted"
        )
        val response = HttpResponse(204, emptyMap())

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("DELETE")
        assertThat(stub.response.status).isEqualTo(204)
    }

    @Test
    fun `should use all the path parameter examples for a no body response`() {
        val openAPI =
            """
---
openapi: 3.0.3
info:
  title: Example API
  description: An API with operations that have no response bodies or headers.
  version: 1.0.0
  contact:
    name: Jack
servers:
  - url: http://prod
tags:
  - name: mod
  - name: read
paths:
  /items/{itemId}:
    delete:
      summary: Delete an item
      operationId: deleteItem
      description: "Delete an item"
      tags:
        - mod
      parameters:
        - name: itemId
          in: path
          required: true
          description: ID of the item to delete
          schema:
            type: string
          examples:
            DELETE_ITEM:
              value: '123-to-be-deleted'
            DELETE_ANOTHER_ITEM:
              value: '456-to-be-deleted'
      responses:
        '204':
          description: No Content - The item was successfully deleted
        '203':
          description: No Content - 203
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        fun createAndAssertStub(request: HttpRequest, response: HttpResponse, expectedMethod: String, expectedStatus: Int) {
            val stub: HttpStubData = feature.matchingStub(request, response)

            println(stub.requestType)

            assertThat(stub.requestType.method).isEqualTo(expectedMethod)
            assertThat(stub.response.status).isEqualTo(expectedStatus)
        }

        val request1 = HttpRequest("DELETE", "/items/123-to-be-deleted")
        val response1 = HttpResponse(203, emptyMap())
        createAndAssertStub(request1, response1, "DELETE", 203)

        val request2 = HttpRequest("DELETE", "/items/456-to-be-deleted")
        val response2 = HttpResponse(203, emptyMap())
        createAndAssertStub(request2, response2, "DELETE", 203)
    }

    @Test
    fun `should respond with the first no body status given a stubbed request and it should have the specmatic random header`() {
        val openAPI =
            """
---
openapi: 3.0.3
info:
  title: Example API
  description: An API with operations that have no response bodies or headers.
  version: 1.0.0
  contact:
    name: Jack
servers:
  - url: http://prod
tags:
  - name: mod
  - name: read
paths:
  /items/{itemId}:
    delete:
      summary: Delete an item
      operationId: deleteItem
      description: "Delete an item"
      tags:
        - mod
      parameters:
        - name: itemId
          in: path
          required: true
          description: ID of the item to delete
          schema:
            type: string
          examples:
            DELETE_ITEM:
              value: '123-to-be-deleted'
            DELETE_ANOTHER_ITEM:
              value: '456-to-be-deleted'
      responses:
        '204':
          description: No Content - The item was successfully deleted
        '203':
          description: No Content - 203
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        HttpStub(feature).use { stub ->
            stub.client.execute(HttpRequest("DELETE", "/items/123-to-be-deleted")).also { response ->
                assertThat(response.status).isEqualTo(203)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }

            stub.client.execute(HttpRequest("DELETE", "/items/456-to-be-deleted")).also { response ->
                assertThat(response.status).isEqualTo(203)
                assertThat(response.headers).doesNotContainEntry(SPECMATIC_TYPE_HEADER, "random")
            }
        }
    }

    @Test
    fun `example of no-body response should execute as test`() {
        val openAPI =
            """
---
openapi: 3.0.3
info:
  title: Example API
  description: An API with operations that have no response bodies or headers.
  version: 1.0.0
  contact:
    name: Jack
servers:
  - url: http://prod
tags:
  - name: mod
  - name: read
paths:
  /items/{itemId}:
    delete:
      summary: Delete an item
      operationId: deleteItem
      description: "Delete an item"
      tags:
        - mod
      parameters:
        - name: itemId
          in: path
          required: true
          description: ID of the item to delete
          schema:
            type: string
          examples:
            DELETE_ITEM:
              value: '123-to-be-deleted'
            DELETE_ANOTHER_ITEM:
              value: '456-to-be-deleted'
      responses:
        '204':
          description: No Content - The item was successfully deleted
        '203':
          description: No Content - 203
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return if(request.path!!.contains("deleted"))
                    HttpResponse(203, NoBodyValue)
                else
                    HttpResponse(204, NoBodyValue)
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `should use the path parameter example for a no body 304 response`() {
        val openAPI =
            """
---
openapi: 3.0.3
info:
  title: Example API
  description: An API with operations that have no response bodies or headers.
  version: 1.0.0
  contact:
    name: Jack
servers:
  - url: http://prod
tags:
  - name: mod
  - name: read
paths:
  /items/{itemId}:
    delete:
      summary: Delete an item
      operationId: deleteItem
      description: "Delete an item"
      tags:
        - mod
      parameters:
        - name: itemId
          in: path
          required: true
          description: ID of the item to delete
          schema:
            type: string
          examples:
            DELETE_ITEM:
              value: '123-to-be-deleted'
      responses:
        '304':
          description: No Content - The item was successfully deleted
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest(
            "DELETE",
            "/items/123-to-be-deleted"
        )
        val response = HttpResponse(304, emptyMap())

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("DELETE")
        assertThat(stub.response.status).isEqualTo(304)
    }

    @Test
    fun `should use the given response example if there is no request body or params present as part of the request`() {
        val openAPI =
            """
---
openapi: 3.0.3
info:
  title: example api
  description: an api with operations that have no response bodies or headers.
  version: 1.0.0
  contact:
    name: jack
servers:
  - url: http://prod
tags:
  - name: mod
  - name: read
paths:
  /ping:
    get:
      summary: Just ping to see if the service responds
      operationId: ping
      description: "Ping"
      tags:
        - mod
      responses:
        '200':
          description: Success
          content:
            text/plain:
              schema:
                type: string
              examples:
                PING:
                  value: success
""".trimIndent()

        val feature = OpenApiSpecification.fromYAML(openAPI, "").toFeature()

        val request = HttpRequest(
            "GET",
            "/ping"
        )
        val response = HttpResponse(200, emptyMap(), StringValue("success"))

        val stub: HttpStubData = feature.matchingStub(request, response)

        println(stub.requestType)

        assertThat(stub.requestType.method).isEqualTo("GET")
        assertThat(stub.response.status).isEqualTo(200)
        assertThat(stub.response.body).isInstanceOf(StringValue::class.java)
        val responseBody = stub.response.body as StringValue
        assertThat(responseBody.string).isEqualTo("success")
    }

    @Nested
    inner class NegativeScenariosForQueryParams {
        @Test
        fun `should generate the negative scenarios where the mandatory keys are missing`() {
            val spec = """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /persons:
                    get:
                      parameters:
                        - in: query
                          name: id
                          schema:
                            type: string
                          required: true
                        - in: query
                          name: name 
                          schema:
                            type: string
                          required: false
                        - in: query
                          name: age
                          schema:
                            type: string
                          required: true
                      responses:
                        200:
                          content:
                            text/plain:
                              schema:
                                type: "string"
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
            val tests = feature.negativeTestScenarios().toList()
            val firstScenario = tests.first().second.value
            val secondScenario = tests.last().second.value

            assertThat(tests.size).isEqualTo(2)

            val firstScenarioAsTest = ScenarioAsTest(
                scenario = firstScenario,
                feature = feature,
                originalScenario = firstScenario,
                flagsBased = DefaultStrategies
            )
            val secondScenarioAsTest = ScenarioAsTest(
                scenario = secondScenario,
                feature = feature,
                originalScenario = secondScenario,
                flagsBased = DefaultStrategies
            )

            firstScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.queryParams.keys.toList()).contains("age").doesNotContain("id")
                    return HttpResponse.OK
                }
            })
            secondScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.queryParams.keys.toList()).contains("id").doesNotContain("age")
                    return HttpResponse.OK
                }
            })

            assertThat(firstScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.QUERY.id mandatory query param not sent]")
            assertThat(secondScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.QUERY.age mandatory query param not sent]")
        }

        @Test
        fun `should generate the negative scenario where the mandatory keys of array based query params are missing`() {
            val spec = """
                    ---
                    openapi: "3.0.1"
                    info:
                      title: "Array Param API"
                      version: "1"
                    paths:
                      /items:
                        get:
                          parameters:
                            - in: query
                              name: ids
                              schema:
                                type: array
                                items:
                                  type: string
                              required: true
                            - in: query
                              name: category 
                              schema:
                                type: string
                              required: false
                          responses:
                            200:
                              content:
                                text/plain:
                                  schema:
                                    type: "string"
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
            val tests = feature.negativeTestScenarios().toList()

            assertThat(tests.size).isEqualTo(1)

            val firstScenario = tests.first().second.value
            val firstScenarioAsTest = ScenarioAsTest(
                scenario = firstScenario,
                feature = feature,
                originalScenario = firstScenario,
                flagsBased = DefaultStrategies
            )
            firstScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.queryParams.keys).doesNotContain("ids")
                    return HttpResponse.OK
                }
            })

            assertThat(firstScenario.testDescription()).isEqualTo(" Scenario: GET /items -> 4xx [REQUEST.PARAMETERS.QUERY.ids mandatory query param not sent]")
        }

        @Test
        fun `should generate the negative scenarios where the mandatory keys are missing even if example is present`() {
            val spec = """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /persons:
                    get:
                      parameters:
                        - in: query
                          name: id
                          schema:
                            type: string
                          required: true
                          examples:
                            EXAMPLE:  
                              value: "id" 
                        - in: query
                          name: name 
                          schema:
                            type: string
                          required: false
                          examples:
                            EXAMPLE:  
                              value: "name" 
                        - in: query
                          name: age
                          schema:
                            type: string
                          required: true
                          examples:
                            EXAMPLE:  
                              value: "age" 
                      responses:
                        200:
                          content:
                            text/plain:
                              schema:
                                type: "string"
                              examples:
                                EXAMPLE:  
                                  value: "response" 

            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
            val tests = feature.negativeTestScenarios().toList()

            assertThat(tests.size).isEqualTo(2)

            val firstScenario = tests.first().second.value
            val secondScenario = tests.last().second.value
            val firstScenarioAsTest = ScenarioAsTest(
                scenario = firstScenario,
                feature = feature,
                originalScenario = firstScenario,
                flagsBased = DefaultStrategies
            )
            val secondScenarioAsTest = ScenarioAsTest(
                scenario = secondScenario,
                feature = feature,
                originalScenario = secondScenario,
                flagsBased = DefaultStrategies
            )
            firstScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.queryParams.keys.toList()).contains("age").doesNotContain("id")
                    return HttpResponse.OK
                }
            })
            secondScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.queryParams.keys.toList()).contains("id").doesNotContain("age")
                    return HttpResponse.OK
                }
            })

            assertThat(firstScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.QUERY.id mandatory query param not sent] | EX:EXAMPLE")
            assertThat(secondScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.QUERY.age mandatory query param not sent] | EX:EXAMPLE")
        }

    }

    @Nested
    inner class NegativeScenariosForHeaders {
        @Test
        fun `should generate the negative scenarios where the mandatory headers are missing`() {
            val spec = """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /persons:
                    get:
                      parameters:
                        - in: header
                          name: X-Required-Header
                          schema:
                            type: string
                          required: true
                        - in: header
                          name: X-Optional-Header
                          schema:
                            type: string
                          required: false
                        - in: header
                          name: X-Another-Required-Header
                          schema:
                            type: string
                          required: true
                      responses:
                        200:
                          content:
                            text/plain:
                              schema:
                                type: "string"
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
            val tests = feature.negativeTestScenarios().toList()

            assertThat(tests.size).isEqualTo(2)

            val firstScenario = tests.first().second.value
            val secondScenario = tests.last().second.value
            val firstScenarioAsTest = ScenarioAsTest(
                scenario = firstScenario,
                feature = feature,
                originalScenario = firstScenario,
                flagsBased = DefaultStrategies
            )
            val secondScenarioAsTest = ScenarioAsTest(
                scenario = secondScenario,
                feature = feature,
                originalScenario = secondScenario,
                flagsBased = DefaultStrategies
            )

            firstScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers.keys.toList())
                        .doesNotContain("X-Required-Header")
                        .contains("X-Another-Required-Header")
                    return HttpResponse.OK
                }
            })

            secondScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers.keys.toList())
                        .contains("X-Required-Header")
                        .doesNotContain("X-Another-Required-Header")
                    return HttpResponse.OK
                }
            })

            assertThat(firstScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.HEADER.X-Required-Header mandatory header not sent]")
            assertThat(secondScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.HEADER.X-Another-Required-Header mandatory header not sent]")
        }

        @Test
        fun `should generate the negative scenarios where the mandatory headers are missing even if example is present`() {
            val spec = """
                ---
                openapi: "3.0.1"
                info:
                  title: "Person API"
                  version: "1"
                paths:
                  /persons:
                    get:
                      parameters:
                        - in: header
                          name: X-Required-Header
                          schema:
                            type: string
                          required: true
                        - in: header
                          name: X-Optional-Header
                          schema:
                            type: string
                          required: false
                          examples:
                            EXAMPLE:  
                              value: "optional-value" 
                        - in: header
                          name: X-Another-Required-Header
                          schema:
                            type: string
                          required: true
                          examples:
                            EXAMPLE: 
                              value: "another-required-value"
                      responses:
                        200:
                          content:
                            text/plain:
                              schema:
                                type: "string"
                        400:
                          content:
                            text/plain:
                              schema:
                                type: "string"
                              examples:
                                EXAMPLE:
                                  value: "This is an error response"
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
            val tests = feature.negativeTestScenarios().toList()

            assertThat(tests.size).isEqualTo(2)

            val firstScenario = tests.first().second.value
            val secondScenario = tests.last().second.value
            val firstScenarioAsTest = ScenarioAsTest(
                scenario = firstScenario,
                feature = feature,
                originalScenario = firstScenario,
                flagsBased = DefaultStrategies
            )
            val secondScenarioAsTest = ScenarioAsTest(
                scenario = secondScenario,
                feature = feature,
                originalScenario = secondScenario,
                flagsBased = DefaultStrategies
            )

            firstScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers.keys.toList())
                        .doesNotContain("X-Required-Header")
                        .contains("X-Another-Required-Header")
                    return HttpResponse.OK
                }
            })

            secondScenarioAsTest.runTest(object: TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers.keys.toList())
                        .contains("X-Required-Header")
                        .doesNotContain("X-Another-Required-Header")
                    return HttpResponse.OK
                }
            })
            assertThat(firstScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.HEADER.X-Required-Header mandatory header not sent]")
            assertThat(secondScenario.testDescription()).isEqualTo(" Scenario: GET /persons -> 4xx [REQUEST.PARAMETERS.HEADER.X-Another-Required-Header mandatory header not sent]")
        }
    }

    @Test
    fun `workflow values should not used in negative tests`() {
        val baseDir = "src/test/resources/openapi/spec_with_workflow_config"
        val openApiFilePath = "$baseDir/spec.yaml"
        val specmaticConfig = loadSpecmaticConfig("$baseDir/specmatic.yaml")
        val feature = OpenApiSpecification
            .fromFile(openApiFilePath, specmaticConfig)
            .toFeature()
            .enableGenerativeTesting()


        class NegativeGETTestData(val actualId: Int, val idInRequest: String) {
            override fun toString(): String {
                return "actualId: $actualId, idInRequest: $idInRequest"
            }
        }

        val negativesSeenOfGET: MutableList<NegativeGETTestData> = mutableListOf()

        feature.executeTests(object : TestExecutor {
            var isNegative: Boolean = true
            val id: Int = 10

            override fun execute(request: HttpRequest): HttpResponse {
                if(isNegative) {
                    if(request.method == "GET")
                        negativesSeenOfGET.add(NegativeGETTestData(id, request.path!!.split("/").last()))

                    return HttpResponse(400, "failed")
                } else {
                    return if(request.method == "POST") {
                        HttpResponse(201, parsedJSONObject("""{"id": $id}"""))
                    } else {
                        HttpResponse(200, parsedJSONObject("""{"productId": "pqr", "quantity": 10}"""))
                    }
                }
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                isNegative = scenario.isNegative
            }
        })

        assertThat(negativesSeenOfGET).isNotEmpty()
        assertThat(negativesSeenOfGET).allSatisfy {
            assertThat(it.actualId.toString()).isNotEqualTo(it.idInRequest)
        }

    }

    @Test
    fun `should mark the properties as required within the resolved allOf schema using an Any type via overlay`() {
        val specContent = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products/{productId}:
                get:
                  summary: Retrieve a product by its ID
                  description: Retrieve details of a product using its unique identifier.
                  parameters:
                    - name: productId
                      in: path
                      required: true
                      schema:
                        type: string
                      description: The unique identifier of the product to retrieve
                  responses:
                    '200':
                      description: Product retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Product'
                    '404':
                      description: Product not found
            components:
              schemas:
                BaseProduct:
                  type: object
                  properties:
                    '@type':
                      type: string
                      description: Type of the product
                    description:
                      type: string
                      description: Detailed description of the product
                    href:
                      type: string
                      format: uri
                      description: URL to access the product details
                    id:
                      type: string
                      description: Unique identifier for the product
                    price:
                      type: number
                      format: float
                      description: Price of the product
                    category:
                      type: string
                      description: Category of the product
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/BaseProduct'
                    - type: object
                      properties:
                        name:
                          type: string
                          description: Name of the product
                        status:
                          type: string
                          description: Availability status of the product
                          enum: [available, out_of_stock, discontinued]
        """.trimIndent()

        val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas
              update:
                AnyValue: {}
            - target: ${'$'}.components.schemas.Product.allOf
              update:
                type: object
                required:
                - '@type'
                - description
                - id
                properties:
                  '@type':
                    type: '#/components/schemas/AnyValue'
                  description:
                    type: '#/components/schemas/AnyValue'
                  id:
                    type: '#/components/schemas/AnyValue'
        """.trimIndent()


        val feature = OpenApiSpecification.fromYAML(
            specContent,
            "",
            overlayContent = overlayContent
        ).toFeature()
        val scenario = feature.scenarios.first { it.isA2xxScenario() }

        val resolvedBodyPattern = resolvedHop(
            scenario.httpResponsePattern.body,
            scenario.resolver
        ) as JSONObjectPattern

        assertThat(resolvedBodyPattern.pattern.containsKey("@type"))
        assertThat(resolvedBodyPattern.pattern.keys).doesNotContain("@type?")

        assertThat(resolvedBodyPattern.pattern.containsKey("description"))
        assertThat(resolvedBodyPattern.pattern.keys).doesNotContain("description?")

        assertThat(resolvedBodyPattern.pattern.containsKey("id"))
        assertThat(resolvedBodyPattern.pattern.keys).doesNotContain("id?")
    }

    @Test
    fun `should not resolve deep discriminators in an allOf schema`() {
        val specFile = "src/test/resources/openapi/vehicle_deep_allof.yaml"
        val feature = OpenApiSpecification.fromFile(specFile).toFeature()

        assertThat(feature.scenarios).allSatisfy { scenario ->
            val responseBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver).let {
                when (it) {
                    is ListPattern -> resolvedHop(it.pattern, scenario.resolver)
                    else -> it
                }
            }
            val requestBodyPattern = scenario.httpRequestPattern.body.takeIf { it !is NoBodyPattern }?.let {
                resolvedHop(scenario.httpRequestPattern.body, scenario.resolver)
            }

            assertThat(responseBodyPattern).isNotInstanceOf(AnyPattern::class.java)
            if (requestBodyPattern != null) {
                when (scenario.method) {
                    "POST" -> {
                        assertThat(requestBodyPattern).isInstanceOf(AnyPattern::class.java)
                        (requestBodyPattern as AnyPattern).let {
                            assertThat(it.pattern).hasSize(2)
                            assertThat(it.discriminator!!.property).isEqualTo("type")
                            assertThat(it.discriminator!!.values).containsExactlyInAnyOrder("car", "truck")
                        }
                    }
                    "PATCH" -> assertThat(requestBodyPattern).isNotInstanceOf(AnyPattern::class.java)
                    else -> Exception("Unexpected method: ${scenario.method}")
                }
            }
        }
    }

    @Test
    fun `should apply top-level required fields to properties in resolved allOf schema`() {
        val specContent = """
        openapi: 3.0.3
        info:
          title: Products API
          description: API for managing products
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: A list of products
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: '#/components/schemas/ProductRes'
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/ProductRes'
              responses:
                '201':
                  description: Successfully created a products
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/ProductRes'
        components:
          schemas:
            Product:
              type: object
              properties:
                id:
                  type: string
                description:
                  type: string
                type:
                  type: string
                createdAt:
                  type: string
                  format: date-time
              required:
                - type
            ProductRes:
              type: object
              allOf:
                - ${'$'}ref: '#/components/schemas/Product'
              required:
                - id
                - description
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(specContent, "").toFeature()

        assertThat(feature.scenarios).allSatisfy { scenario ->
            val responsePattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver)

            val requestBodyPattern = resolvedHop(scenario.httpRequestPattern.body, scenario.resolver) as? JSONObjectPattern
            val responseBodyPattern = when (responsePattern) {
                is JSONObjectPattern -> responsePattern
                is ListPattern -> resolvedHop(responsePattern.pattern, scenario.resolver) as JSONObjectPattern
                else -> throw IllegalArgumentException("Unexpected response pattern")
            }

            if (requestBodyPattern != null) {
                assertThat(requestBodyPattern.pattern).containsKey("type").doesNotContainKey("type?")
                assertThat(requestBodyPattern.pattern).containsKey("id").doesNotContainKey("id?")
                assertThat(requestBodyPattern.pattern).containsKey("description").doesNotContainKey("description?")
                assertThat(requestBodyPattern.pattern).containsKey("createdAt?").doesNotContainKey("createdAt")
            }

            assertThat(responseBodyPattern.pattern).containsKey("type").doesNotContainKey("type?")
            assertThat(responseBodyPattern.pattern).containsKey("id").doesNotContainKey("id?")
            assertThat(responseBodyPattern.pattern).containsKey("description").doesNotContainKey("description?")
            assertThat(responseBodyPattern.pattern).containsKey("createdAt?").doesNotContainKey("createdAt")
        }
    }

    @Test
    fun `should not propagate top-level required fields into nested schemas in allOf`() {
        val specContent = """
        openapi: 3.0.3
        info:
          title: Products API
          description: API for managing products
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: A list of products
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: '#/components/schemas/ProductRes'
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/ProductRes'
              responses:
                '201':
                  description: Successfully created a products
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/ProductRes'
        components:
          schemas:
            Product:
              type: object
              properties:
                id:
                  type: string
                description:
                  type: string
                type:
                  type: string
                createdAt:
                  type: string
                  format: date-time
                buyer:
                  ${'$'}ref: '#/components/schemas/Buyer'
              required:
                - type
            ProductRes:
              allOf:
                - ${'$'}ref: '#/components/schemas/Product'
              required:
                - id
                - description
                - name
            Buyer:
              type: object
              properties:
                name:
                  type: string
                email:
                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(specContent, "").toFeature()

        assertThat(feature.scenarios).allSatisfy { scenario ->
            val responsePattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver)
            val resolvedBodyPattern = when (responsePattern) {
                is JSONObjectPattern -> responsePattern
                is ListPattern -> resolvedHop(responsePattern.pattern, scenario.resolver) as JSONObjectPattern
                else -> throw IllegalArgumentException("Unexpected response pattern")
            }

            assertThat(resolvedBodyPattern.pattern).containsKey("type").doesNotContainKey("type?")
            assertThat(resolvedBodyPattern.pattern).containsKey("id").doesNotContainKey("id?")
            assertThat(resolvedBodyPattern.pattern).containsKey("description").doesNotContainKey("description?")

            assertThat(resolvedBodyPattern.pattern).containsKey("createdAt?").doesNotContainKey("createdAt")
            assertThat(resolvedBodyPattern.pattern).containsKey("buyer?").doesNotContainKey("buyer")

            val assignedToPattern = resolvedHop(resolvedBodyPattern.pattern["buyer?"]!!, scenario.resolver) as JSONObjectPattern
            assertThat(assignedToPattern.pattern).containsKey("name?").doesNotContainKey("name")
            assertThat(assignedToPattern.pattern).containsKey("email?").doesNotContainKey("email")
        }
    }

    @Test
    fun `should include unreferenced or indirectly referenced schema patterns`() {
        val specContent = """
        openapi: 3.0.3
        info:
          title: Products API
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: Successful response
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: '#/components/schemas/ExtendedDetails'
        components:
          schemas:
            User:
              type: object
              properties:
                name:
                  type: string
            ExtendedDetails:
              allOf:
                - ${'$'}ref: '#/components/schemas/BaseDetails'
                - ${'$'}ref: '#/components/schemas/User'
            BaseDetails:
              type: object
              properties:
                id:
                  type: string
                email:
                  type: string
              required:
                - id
            Address:
              type: object
              properties:
                street:
                  type: string
                city:
                  type: string
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(specContent, "").toFeature()
        val directlyNonReferencedPatterns = listOf("(BaseDetails)", "(User)", "(Address)")

        assertThat(feature.scenarios).allSatisfy { scenario ->
            directlyNonReferencedPatterns.forEach {
                assertThat(scenario.patterns).containsKey(it)
                assertThat(scenario.resolver.newPatterns).containsKey(it)
            }
        }
    }

    @Test
    fun `should not require discriminator mappings when datatype value in propertyName field is the same as schema name in oneOf schema`() {
        val specContent = """
        openapi: 3.0.0
        info:
          title: Object API
          version: "2.0"
        paths:
          /sample:
            post:
              summary: Create a sample object
              requestBody:
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/sampleObject'
              responses:
                '201':
                  description: Created
                  content:
                    application/json:
                      schema:
                        type: string
        components:
          schemas:
            sampleObject:
              type: object
              oneOf:
                - ${'$'}ref: '#/components/schemas/simpleObject'
                - ${'$'}ref: '#/components/schemas/complexObject'
              discriminator:
                propertyName: objectType
            simpleObject:
              type: object
              required:
                - objectType
                - property1
              properties:
                objectType:
                  type: string
                property1:
                  type: string
            complexObject:
              type: object
              required:
                - objectType
                - property2
              properties:
                objectType:
                  type: string
                property2:
                  type: string
        """.trimIndent()
        val apiSpec = OpenApiSpecification.fromYAML(specContent, "")
        val feature = apiSpec.toFeature()
        val scenario = feature.scenarios.first()
        val resolver = scenario.resolver
        val requestBodyPattern = resolvedHop(scenario.httpRequestPattern.body, resolver)

        assertThat(requestBodyPattern).isInstanceOf(AnyPattern::class.java)
        (requestBodyPattern as AnyPattern).let {
            assertThat(it.discriminator!!.values).isEqualTo(setOf("simpleObject", "complexObject"))
            assertThat(it.discriminator!!.property).isEqualTo("objectType")
            assertThat(it.discriminator!!.mapping).isEqualTo(
                mapOf(
                    "simpleObject" to "#/components/schemas/simpleObject",
                    "complexObject" to "#/components/schemas/complexObject"
                )
            )
        }
    }

    @Test
    fun `implied discriminator mappings should not override user defined discriminator mappings in oneOf schema`() {
        val specContent = """
        openapi: 3.0.0
        info:
          title: Object API
          version: "2.0"
        paths:
          /sample:
            post:
              summary: Create a sample object
              requestBody:
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/sampleObject'
              responses:
                '201':
                  description: Created
                  content:
                    application/json:
                      schema:
                        type: string
        components:
          schemas:
            sampleObject:
              type: object
              oneOf:
                - ${'$'}ref: '#/components/schemas/simpleObject'
                - ${'$'}ref: '#/components/schemas/complexObject'
              discriminator:
                propertyName: objectType
                mapping:
                  simple: "#/components/schemas/simpleObject"
            simpleObject:
              type: object
              required:
                - objectType
                - property1
              properties:
                objectType:
                  type: string
                property1:
                  type: string
            complexObject:
              type: object
              required:
                - objectType
                - property2
              properties:
                objectType:
                  type: string
                property2:
                  type: string
        """.trimIndent()
        val apiSpec = OpenApiSpecification.fromYAML(specContent, "")
        val feature = apiSpec.toFeature()
        val scenario = feature.scenarios.first()
        val resolver = scenario.resolver
        val requestBodyPattern = resolvedHop(scenario.httpRequestPattern.body, resolver)

        assertThat(requestBodyPattern).isInstanceOf(AnyPattern::class.java)
        (requestBodyPattern as AnyPattern).let {
            assertThat(it.discriminator!!.values).isEqualTo(setOf("simple", "complexObject"))
            assertThat(it.discriminator!!.property).isEqualTo("objectType")
            assertThat(it.discriminator!!.mapping).isEqualTo(
                mapOf(
                    "simple" to "#/components/schemas/simpleObject",
                    "complexObject" to "#/components/schemas/complexObject"
                )
            )
        }
    }

    @Test
    fun `operations should inherit global security schemas when operation level security schemas are not defined`() {
        val specContent = """
        openapi: '3.0.3'
        info:
          title: Security API
          version: '1.0'
        paths:
          /security:
            get:
              responses:
                '200':
                  description: OK
        components:
          securitySchemes:
            bearerAuth:
              type: http
              scheme: bearer
        security:
          - bearerAuth: []
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(specContent, "").toFeature()
        val secureScenario = feature.scenarios.first { it.path == "/security" }

        assertThat(secureScenario.httpRequestPattern.securitySchemes)
            .hasOnlyElementsOfType(BearerSecurityScheme::class.java)
            .hasSize(1)
    }

    @Test
    fun `operation level security schemas should override global level security schemas`() {
        val specContent = """
        openapi: '3.0.3'
        info:
          title: Security API
          version: '1.0'
        paths:
          /no-security:
            get:
              security: []
              responses:
                '200':
                  description: OK
        components:
          securitySchemes:
            bearerAuth:
              type: http
              scheme: bearer
        security:
          - bearerAuth: []
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specContent, "").toFeature()
        val unSecureScenario = feature.scenarios.first { it.path == "/no-security" }

        assertThat(unSecureScenario.httpRequestPattern.securitySchemes)
            .hasOnlyElementsOfType(NoSecurityScheme::class.java)
            .hasSize(1)
    }

    @Test
    fun `should ignore other properties when $ref is defined`() {
        val specContent = """
        openapi: '3.0.3'
        info:
          title: Simple Pet API
          version: '1.0'
        paths:
          /pet:
            get:
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/Pet'
        components:
          schemas:
            Pet:
              type: object
              properties:
                name:
                  type: string
                pet-type:
                  type: string
                  ${"$"}ref: '#/components/schemas/PetType'
            PetType:
              type: string
              enum:
                - dog
                - cat
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(specContent, "").toFeature()

        val petScenario = feature.scenarios.first { it.path == "/pet" }
        val petPattern = resolvedHop(petScenario.httpResponsePattern.body, petScenario.resolver) as JSONObjectPattern
        val petTypePattern = resolvedHop(petPattern.pattern.getValue("pet-type?"), petScenario.resolver) as EnumPattern

        assertThat(petPattern.pattern.values).hasSize(2).hasOnlyElementsOfTypes(StringPattern::class.java, DeferredPattern::class.java)
        assertThat(petTypePattern.pattern.pattern).hasSize(2).hasOnlyElementsOfTypes(ExactValuePattern::class.java)
        assertThat(petTypePattern.pattern.pattern.map { it.pattern }).containsExactlyInAnyOrder(
            StringValue("dog"), StringValue("cat")
        )
    }

    @Test
    fun `the patterns in AnyPattern for a discriminated schema should have the correct type-aliases`() {
        val specContent = """
        openapi: 3.0.0
        info:
          title: Object API
          version: "2.0"
        paths:
          /object:
            get:
              summary: Gets a object
              responses:
                '200':
                  description: Success
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/Object'
        components:
          schemas:
            Base:
              type: object
              required:
                - objectType
              properties:
                id:
                  type: number
                objectType:
                  type: string
            Object:
              allOf:
                - ${'$'}ref: '#/components/schemas/Base'
              discriminator:
                propertyName: objectType
                mapping:
                  simple: '#/components/schemas/simpleObject'
                  complex: '#/components/schemas/complexObject'
            simpleObject:
              allOf:
                - ${'$'}ref: '#/components/schemas/Object'
                - type: object
                  required:
                    - property1
                  properties:
                    property1:
                      type: string
            complexObject:
              allOf:
                - ${'$'}ref: '#/components/schemas/Object'
                - type: object
                  required:
                    - property2
                  properties:
                    property2:
                      type: string
        """.trimIndent()
        val apiSpec = OpenApiSpecification.fromYAML(specContent, "")
        val feature = apiSpec.toFeature()
        val scenario = feature.scenarios.first()
        val resolver = scenario.resolver
        val responseBodyPattern = resolvedHop(scenario.httpResponsePattern.body, resolver)

        assertThat(responseBodyPattern).isInstanceOf(AnyPattern::class.java)
        responseBodyPattern as AnyPattern
        assertThat(responseBodyPattern.typeAlias).isEqualTo("(Object)")
        assertThat(responseBodyPattern.pattern.map { it.typeAlias }).containsExactlyInAnyOrder(
            "(simpleObject)", "(complexObject)"
        )
    }

    @Test
    fun `object schema with additional properties set to true should be converted to freeForm object pattern`() {
        val specContent = """
        openapi: '3.0.3'
        info:
          title: Simple API
          version: '1.0'
        paths:
          /test:
            get:
              summary: A simple test
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/FreeFormObject'
        components:
          schemas:
            FreeFormObject:
              type: object
              properties:
                name:
                  type: string
                address:
                  type: string
              required:
                - name
              additionalProperties: true
        """.trimIndent()
        val specification = OpenApiSpecification.fromYAML(specContent, "")
        val feature = specification.toFeature()
        val scenario =  feature.scenarios.first()
        val responseBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver)

        assertThat(responseBodyPattern).isInstanceOf(JSONObjectPattern::class.java)
        responseBodyPattern as JSONObjectPattern
        assertThat(responseBodyPattern.typeAlias).isEqualTo("(FreeFormObject)")
        assertThat(responseBodyPattern.pattern).isEqualTo(mapOf(
            "name" to StringPattern(), "address?" to StringPattern()
        ))

        assertThat(responseBodyPattern.additionalProperties).isEqualTo(AdditionalProperties.FreeForm)
    }

    @Test
    fun `object schema with empty object additionalProperties should be converted to to freeForm object pattern`() {
        val specContent = """
        openapi: '3.0.3'
        info:
          title: Simple API
          version: '1.0'
        paths:
          /test:
            get:
              summary: A simple test
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/FreeFormObject'
        components:
          schemas:
            FreeFormObject:
              type: object
              properties:
                name:
                  type: string
                address:
                  type: string
              required:
                - name
              additionalProperties: {}
        """.trimIndent()
        val specification = OpenApiSpecification.fromYAML(specContent, "")
        val feature = specification.toFeature()
        val scenario =  feature.scenarios.first()
        val responseBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver)

        assertThat(responseBodyPattern).isInstanceOf(JSONObjectPattern::class.java)
        responseBodyPattern as JSONObjectPattern
        assertThat(responseBodyPattern.typeAlias).isEqualTo("(FreeFormObject)")
        assertThat(responseBodyPattern.pattern).isEqualTo(mapOf(
            "name" to StringPattern(), "address?" to StringPattern()
        ))

        assertThat(responseBodyPattern.additionalProperties).isEqualTo(AdditionalProperties.FreeForm)
    }

    @Test
    fun `object schema with primitive schema as additional properties should be converted to patternConstrained object pattern`() {
        val specContent = """
        openapi: '3.0.3'
        info:
          title: Simple API
          version: '1.0'
        paths:
          /test:
            get:
              summary: A simple test
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/ValueConstrained'
        components:
          schemas:
            ValueConstrained:
              type: object
              properties:
                name:
                  type: string
                address:
                  type: string
              required:
                - name
              additionalProperties:
                type: string
                minLength: 1
                maxLength: 3
        """.trimIndent()
        val specification = OpenApiSpecification.fromYAML(specContent, "")
        val feature = specification.toFeature()
        val scenario =  feature.scenarios.first()
        val responseBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver)

        assertThat(responseBodyPattern).isInstanceOf(JSONObjectPattern::class.java)
        responseBodyPattern as JSONObjectPattern
        assertThat(responseBodyPattern.typeAlias).isEqualTo("(ValueConstrained)")
        assertThat(responseBodyPattern.pattern).isEqualTo(mapOf(
            "name" to StringPattern(), "address?" to StringPattern()
        ))

        val additionalProperties = responseBodyPattern.additionalProperties
        assertThat(additionalProperties).isInstanceOf(AdditionalProperties.PatternConstrained::class.java)
        additionalProperties as AdditionalProperties.PatternConstrained
        assertThat(additionalProperties.pattern).isEqualTo(
            StringPattern(maxLength = 3, minLength = 1)
        )
    }

    @Test
    fun `object schema with complex schema as additional properties should be converted to patternConstrained object pattern`() {
        val specContent = """
        openapi: '3.0.3'
        info:
          title: Simple API
          version: '1.0'
        paths:
          /test:
            get:
              summary: A simple test
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/ValueConstrained'
        components:
          schemas:
            ValueConstrained:
              type: object
              properties:
                name:
                  type: string
                address:
                  type: string
              required:
                - name
              additionalProperties:
                ${'$'}ref: '#/components/schemas/ComplexSchema'
            ComplexSchema:
              oneOf:
                - type: object
                  properties:
                    property1:
                      type: string
                - type: object
                  properties:
                    property2:
                      type: string
        """.trimIndent()
        val specification = OpenApiSpecification.fromYAML(specContent, "")
        val feature = specification.toFeature()
        val scenario = feature.scenarios.first()
        val responseBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver)

        assertThat(responseBodyPattern).isInstanceOf(JSONObjectPattern::class.java)
        responseBodyPattern as JSONObjectPattern
        assertThat(responseBodyPattern.typeAlias).isEqualTo("(ValueConstrained)")
        assertThat(responseBodyPattern.pattern).isEqualTo(mapOf(
            "name" to StringPattern(), "address?" to StringPattern()
        ))

        val additionalProperties = responseBodyPattern.additionalProperties
        assertThat(additionalProperties).isInstanceOf(AdditionalProperties.PatternConstrained::class.java)
        additionalProperties as AdditionalProperties.PatternConstrained

        val patterns = listOf(
            parsedPattern("{ \"property1?\": \"(string)\" }"),
            parsedPattern("{ \"property2?\": \"(string)\" }")
        )
        assertThat(resolvedHop(additionalProperties.pattern, scenario.resolver)).isEqualTo(
            AnyPattern(
                pattern = patterns, typeAlias = "(ComplexSchema)", extensions = patterns.extractCombinedExtensions()
            )
        )
    }

    @Test
    fun `when a content-type header with a specific value is given it should override the media-type`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  parameters:
                    - in: header
                      name: Content-Type
                      schema:
                        type: string
                        enum:
                        - 'application/json; charset=utf-8'
                      required: true
                      description: Unneeded content type
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                  responses:
                    '201':
                      description: Product created successfully
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
                return HttpResponse(201, parsedJSONObject("""{"id": 1, "name": "Phone", "price": 1000, "category": "Electronics"}"""))
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `should detect content type headers that conflict with media type at parse time`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  parameters:
                    - in: header
                      name: Content-Type
                      schema:
                        type: string
                        enum:
                        - application/json; charset=utf-8
                      required: true
                      description: Unneeded content type
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                  responses:
                    '201':
                      description: Product created successfully
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'

        """.trimIndent()

        val (output, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(output).contains("WARNING: Media type \"application/json\" in request of POST /products does not match the respective Content-Type header. Using the Content-Type header as an override.")
    }

    @Test
    fun `inline examples should use the overridden request content type`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  parameters:
                    - in: header
                      name: Content-Type
                      schema:
                        type: string
                        enum:
                        - application/json; charset=utf-8
                      examples:
                        SUCCESS:
                          value: application/json; charset=utf-8
                      required: true
                      description: Unneeded content type
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                        examples:
                          SUCCESS:
                            value:
                              name: Phone
                              price: 1000
                              category: Electronics
                  responses:
                    '201':
                      description: Product created successfully
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
                          examples:
                            SUCCESS:
                              value:
                                id: 1
                                name: Phone
                                price: 1000
                                category: Electronics
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
                return HttpResponse(201, body = parsedJSONObject("""{"id": 10, "name": "Phone", "price": 1000, "category": "Electronics"}"""))
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `inline examples should use the overridden request content type when no example is given`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  parameters:
                    - in: header
                      name: Content-Type
                      schema:
                        type: string
                        enum:
                        - application/json; charset=utf-8
                      required: true
                      description: Unneeded content type
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                        examples:
                          SUCCESS:
                            value:
                              name: Phone
                              price: 1000
                              category: Electronics
                  responses:
                    '201':
                      description: Product created successfully
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
                          examples:
                            SUCCESS:
                              value:
                                id: 1
                                name: Phone
                                price: 1000
                                category: Electronics
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
                return HttpResponse(201, body = parsedJSONObject("""{"id": 10, "name": "Phone", "price": 1000, "category": "Electronics"}"""))
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `if inline example of overridden request Content Type header is wrong then parse should error out`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  parameters:
                  - in: header
                    name: Content-Type
                    schema:
                      type: string
                      enum:
                      - application/json; charset=utf-8
                    examples:
                      SUCCESS:
                        value: application/json
                    required: true
                    description: Unneeded content type
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                        examples:
                          SUCCESS:
                            value:
                              name: Phone
                              price: 1000
                              category: Electronics
                  responses:
                    '201':
                      description: Product created successfully
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
                          examples:
                            SUCCESS:
                              value:
                                id: 1
                                name: Phone
                                price: 1000
                                category: Electronics
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
                return HttpResponse(201, body = parsedJSONObject("""{"id": 10, "name": "Phone", "price": 1000, "category": "Electronics"}"""))
            }
        })

        assertThat(results.report()).contains("""expected "application/json; charset=utf-8" but found value "application/json"""")
    }

    @Test
    fun `inline examples should use the overridden response content type`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                        examples:
                          SUCCESS:
                            value:
                              name: Phone
                              price: 1000
                              category: Electronics
                  responses:
                    '201':
                      description: Product created successfully
                      headers:
                        Content-Type:
                          schema:
                            type: string
                            enum:
                            - application/json; charset=utf-8
                          examples:
                            SUCCESS:
                              value: application/json; charset=utf-8
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
                          examples:
                            SUCCESS:
                              value:
                                id: 1
                                name: Phone
                                price: 1000
                                category: Electronics
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        HttpStub(feature).use { stub ->
            val response = stub.client.execute(HttpRequest("POST", "/products", headers = mapOf("Content-Type" to "application/json; charset=utf-8"), body = parsedJSONObject("""{"name": "Phone", "price": 1000, "category": "Electronics"}""")))
            assertThat(response.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
        }
    }

    @Test
    fun `inline examples should use the overridden response content type when no header example is given`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                        examples:
                          SUCCESS:
                            value:
                              name: Phone
                              price: 1000
                              category: Electronics
                  responses:
                    '201':
                      description: Product created successfully
                      headers:
                        Content-Type:
                          schema:
                            type: string
                            enum:
                            - application/json; charset=utf-8
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
                          examples:
                            SUCCESS:
                              value:
                                id: 1
                                name: Phone
                                price: 1000
                                category: Electronics
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val (output, _) = captureStandardOutput {
            HttpStub(feature).use { stub ->
                val response = stub.client.execute(
                    HttpRequest(
                        "POST",
                        "/products",
                        headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                        body = parsedJSONObject("""{"name": "Phone", "price": 1000, "category": "Electronics"}""")
                    )
                )
                assertThat(response.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
            }
        }

        assertThat(output).doesNotContain("does not match \"application/json; charset=utf-8\" in the spec")
    }

    @Test
    fun `if inline of overridden response Content Type header is wrong then parse should error out`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                post:
                  summary: Add a new product
                  parameters:
                    - in: header
                      name: Content-Type
                      schema:
                        type: string
                        enum:
                        - application/json; charset=utf-8
                      examples:
                        SUCCESS:
                          value: application/json
                      required: true
                      description: Unneeded content type
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/ProductDetails'
                        examples:
                          SUCCESS:
                            value:
                              name: Phone
                              price: 1000
                              category: Electronics
                  responses:
                    '201':
                      description: Product created successfully
                      headers:
                        Content-Type:
                          schema:
                            type: string
                            enum:
                            - application/json; charset=utf-8
                          examples:
                            SUCCESS:
                              value: application/json
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Product'
                          examples:
                            SUCCESS:
                              value:
                                id: 1
                                name: Phone
                                price: 1000
                                category: Electronics
            components:
              schemas:
                ProductId:
                  type: object
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                ProductDetails:
                  type: object
                  required:
                    - name
                    - price
                    - category
                  properties:
                    name:
                      type: string
                    price:
                      type: number
                    category:
                      type: string
                      enum:
                        - Electronics
                        - Clothing
                        - Books
                Product:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/ProductId'
                    - ${"$"}ref: '#/components/schemas/ProductDetails'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
                return HttpResponse(201, body = parsedJSONObject("""{"id": 10, "name": "Phone", "price": 1000, "category": "Electronics"}"""))
            }
        })

        println(results.report())
        assertThat(results.report()).contains("""expected "application/json; charset=utf-8" but found value "application/json"""")
    }

    @Test
    fun `should be able to load OAS with security schemes defined in logical AND or OR combination`() {
        val oasFile = File("src/test/resources/openapi/has_composite_security/api.yaml")
        val feature = Flags.using("bearerAuth" to "API-SECRET", "apiKeyQuery" to "1234") {
            OpenApiSpecification.fromFile(oasFile.canonicalPath).toFeature()
        }
        val (secure, partial, overlap, insecure) = feature.scenarios

        assertThat(secure.httpRequestPattern.securitySchemes).isEqualTo(listOf(
            CompositeSecurityScheme(listOf(
                BearerSecurityScheme(configuredToken = "API-SECRET"),
                APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = "1234")
            ))
        ))

        assertThat(overlap.httpRequestPattern.securitySchemes).isEqualTo(listOf(
            CompositeSecurityScheme(listOf(
                BearerSecurityScheme(configuredToken = "API-SECRET"),
                APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = "1234")
            )),
            BearerSecurityScheme(configuredToken = "API-SECRET"),
        ))

        assertThat(partial.httpRequestPattern.securitySchemes).isEqualTo(listOf(
            BearerSecurityScheme(configuredToken = "API-SECRET"),
            APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = "1234")
        ))

        assertThat(insecure.httpRequestPattern.securitySchemes).isEqualTo(listOf(
            NoSecurityScheme()
        ))
    }

    @Test
    fun `should pass checkSpecValidity with valid openapi file`() {
        assertThatCode {
            OpenApiSpecification.checkSpecValidity("openApiTest.yaml")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `checkSpecValidity should throw ContractException with invalid openapi file`(@TempDir tempDir: File) {
        val invalidOpenApiContent = """
            invalid yaml content
            this is not valid yaml: [
        """.trimIndent()
        
        val invalidOpenApiFile = tempDir.resolve(File("invalidOpenApi.yaml"))
        invalidOpenApiFile.createNewFile()
        invalidOpenApiFile.writeText(invalidOpenApiContent)
        
        try {
            assertThatThrownBy {
                OpenApiSpecification.checkSpecValidity(invalidOpenApiFile.canonicalPath)
            }.isInstanceOf(ContractException::class.java)
                .hasMessageContaining("Could not parse contract")
                .hasMessageContainingAll("Could not parse contract", invalidOpenApiFile.name)
        } finally {
            invalidOpenApiFile.delete()
        }
    }

    @Test
    fun `checkSpecValidity should throw ContractException when a valid openapi file gets parsed with issues`(@TempDir tempDir: File) {
        val invalidOpenApiContent = """
            openapi: 3.0.1
            servers:
              - url: "http://localhost:3000"
            paths:
              "/products/{id}":
                get:
                  summary: Get a product by ID
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: integer
                  responses:
                    '200':
                      description: Successful response
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
        """.trimIndent()

        val invalidOpenApiFile = tempDir.resolve(File("invalidOpenApi.yaml"))
        invalidOpenApiFile.createNewFile()
        invalidOpenApiFile.writeText(invalidOpenApiContent)

        try {
            assertThatThrownBy {
                OpenApiSpecification.checkSpecValidity(invalidOpenApiFile.canonicalPath)
            }.isInstanceOf(ContractException::class.java)
                .hasMessageContaining("attribute info is missing")
        } finally {
            invalidOpenApiFile.delete()
        }
    }

    @Test
    fun `getImplicitOverlayContent should return empty string when OpenAPI file does not exist`(@TempDir tempDir: File) {
        val nonExistentOpenApiFile = tempDir.resolve("non_existent_api.yaml")
        
        val result = OpenApiSpecification.getImplicitOverlayContent(nonExistentOpenApiFile.canonicalPath)
        
        assertThat(result).isEmpty()
    }

    @Test
    fun `getImplicitOverlayContent should return empty string when OpenAPI file exists but overlay file does not exist`(@TempDir tempDir: File) {
        val openApiContent = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /test:
                get:
                  responses:
                    '200':
                      description: Success
        """.trimIndent()
        
        val openApiFile = tempDir.resolve("test_api.yaml")
        openApiFile.writeText(openApiContent)
        
        val result = OpenApiSpecification.getImplicitOverlayContent(openApiFile.canonicalPath)
        
        assertThat(result).isEmpty()
    }

    @Test
    fun `getImplicitOverlayContent should return overlay content when both OpenAPI file and overlay file exist`(@TempDir tempDir: File) {
        val openApiContent = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /test:
                get:
                  responses:
                    '200':
                      description: Success
        """.trimIndent()
        
        val overlayContent = """
            overlay: 1.0.0
            info:
              title: Test API with Overlay
              version: 1.0.1
            actions:
              - target: $.info.description
                update: "API with overlay applied"
        """.trimIndent()
        
        val openApiFile = tempDir.resolve("test_api.yaml")
        openApiFile.writeText(openApiContent)
        
        val overlayFile = tempDir.resolve("test_api_overlay.yaml")
        overlayFile.writeText(overlayContent)
        
        val result = OpenApiSpecification.getImplicitOverlayContent(openApiFile.canonicalPath)
        
        assertThat(result).isEqualTo(overlayContent)
    }

    private fun ignoreButLogException(function: () -> OpenApiSpecification) {
        try {
            function()
        } catch (e: Throwable) {
            println(exceptionCauseMessage(e))
        }
    }
}
