package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SPECMATIC_RESPONSE_CODE_HEADER
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.util.*

class ExamplesAsStubTest {
    private val featureWithQueryParamExamples = OpenApiSpecification.fromYAML(
        """
openapi: 3.0.1
info:
  title: Data API
  version: "1"
paths:
  /:
    get:
      summary: Data
      parameters:
        - name: type
          schema:
            type: string
          in: query
          required: true
          examples:
            QUERY_SUCCESS:
              value: data
      responses:
        "200":
          description: Data
          content:
            application/json:
              schema:
                type: array
                items:
                  type:
                    string
              examples:
                QUERY_SUCCESS:
                  value: ["one", "two"]
""".trim(), ""
    ).toFeature()

    private val featureWithPathParamExamples = OpenApiSpecification.fromYAML(
        """
openapi: 3.0.1
info:
  title: Data API
  version: "1"
paths:
  /{productId}:
    get:
      summary: Data
      parameters:
        - name: productId
          schema:
            type: string
          in: path
          required: true
          examples:
            PATH_SUCCESS:
              value: xyz123
            PATH_FAILURE:
              value: abc123
      responses:
        "200":
          description: Data
          content:
            application/json:
              schema:
                type: array
                items:
                  type:
                    string
              examples:
                PATH_SUCCESS:
                  value: ["one", "two"]
        "404":
          description: Data not found
          content:
            text/plain:
              schema:
                type: string
              examples:
                PATH_FAILURE:
                  value: "Could not find abc123"
""".trim(), ""
    ).toFeature()

    private val featureWithHeaderParamExamples = OpenApiSpecification.fromYAML(
        """
openapi: 3.0.1
info:
  title: Header Example API
  version: "1"
paths:
  /hello:
    get:
      parameters:
        - name: userId
          schema:
            type: string
          in: header
          required: true
          examples:
            HEADER_SUCCESS:
              value: John
            HEADER_FAILURE:
              value: Jane
      responses:
        "200":
          description: Data
          content:
            text/plain:
              schema:
                type: string
              examples:
                HEADER_SUCCESS:
                  value: "Hello John"
        "401":
          description: Unauthorized
          content:
            text/plain:
              schema:
                type: string
              examples:
                HEADER_FAILURE:
                  value: "User Jane not authorized"
""".trim(), ""
    ).toFeature()

    private val featureWithOmittedParamExamples = OpenApiSpecification.fromYAML(
        """
openapi: 3.0.1
info:
  title: Header Example API
  version: "1"
paths:
  /hello:
    get:
      parameters:
        - name: userId
          schema:
            type: string
          in: header
          required: true
          examples:
            HEADER_SUCCESS:
              value: John
        - name: role
          schema:
            type: string
          in: header
          required: false
          examples:
            HEADER_SUCCESS:
              value: "(omit)"
      responses:
        "200":
          description: Data
          content:
            text/plain:
              schema:
                type: string
              examples:
                HEADER_SUCCESS:
                  value: "Hello John"
""".trim(), ""
    ).toFeature()

    @Test
    fun `any value should match the globally declared security scheme`() {
        val feature = OpenApiSpecification.fromYAML(
            """
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
  /hello:
    post:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - message
              properties:
                message:
                  type: string
            examples:
              SUCCESS:
                value:
                  message: Hello World!
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value:
                    Hello to you!
components:
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer

security:
  - BearerAuth: []
         """.trimIndent(), ""
        ).toFeature()

        val credentials = "Basic " + Base64.getEncoder().encodeToString("user:password".toByteArray())

        HttpStub(feature).use {
            val response = it.client.execute(HttpRequest(
                "POST",
                "/hello",
                mapOf("Authorization" to "Bearer $credentials"),
                parsedJSONObject("""{"message": "Hello World!"}""")
            ))

            assertThat(response.body.toStringLiteral()).isEqualTo("Hello to you!")
        }
    }

    @Test
    fun `any value should match the security scheme declared for a specific API`() {
        val feature = OpenApiSpecification.fromYAML(
            """
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
  /hello:
    post:
      security:
        - BearerAuth: []
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - message
              properties:
                message:
                  type: string
            examples:
              SUCCESS:
                value:
                  message: Hello World!
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value:
                    Hello to you!
components:
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
         """.trimIndent(), ""
        ).toFeature()

        val credentials = "Basic " + Base64.getEncoder().encodeToString("user:password".toByteArray())

        HttpStub(feature).use {
            val response = it.client.execute(HttpRequest(
                "POST",
                "/hello",
                mapOf("Authorization" to "Bearer $credentials"),
                parsedJSONObject("""{"message": "Hello World!"}""")
            ))

            assertThat(response.body.toStringLiteral()).isEqualTo("Hello to you!")
        }
    }

    @Test
    fun `examples as stub should work for request with body when there are no security schemes`() {
        val feature = OpenApiSpecification.fromYAML(
            """
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
  /hello:
    post:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - message
              properties:
                message:
                  type: string
            examples:
              SUCCESS:
                value:
                  message: Hello World!
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value:
                    Hello to you!
         """.trimIndent(), ""
        ).toFeature()

        HttpStub(feature).use {
            val response = it.client.execute(HttpRequest(
                "POST",
                "/hello",
                body = parsedJSONObject("""{"message": "Hello World!"}""")
            ))

            assertThat(response.body.toStringLiteral()).isEqualTo("Hello to you!")
        }
    }

    @Test
    fun `any value should match the given security scheme when request body does not exist`() {
        val feature = OpenApiSpecification.fromYAML(
            """
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
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - name: greeting
          schema:
            type: string
          in: query
          examples:
            SUCCESS:
              value: "Hello"
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value:
                    Hello to you!
components:
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer

security:
  - BearerAuth: []
         """.trimIndent(), ""
        ).toFeature()

        val credentials = "Basic " + Base64.getEncoder().encodeToString("user:password".toByteArray())

        HttpStub(feature).use {
            val response = it.client.execute(HttpRequest(
                "GET",
                "/hello",
                mapOf("Authorization" to "Bearer $credentials"),
                queryParametersMap = mapOf("greeting" to "Hello")
            ))

            assertThat(response.body.toStringLiteral()).isEqualTo("Hello to you!")
        }
    }

    @Test
    fun `expectations for query params from examples`() {
        HttpStub(featureWithQueryParamExamples).use { stub ->
            stub.client.execute(HttpRequest("GET", "/?type=data"))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(parsedJSONArray("""["one", "two"]"""))
                }
        }
    }

    @Test
    fun `expectations for path params from examples`() {
        HttpStub(featureWithPathParamExamples).use { stub ->
            stub.client.execute(HttpRequest("GET", "/xyz123"))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(parsedJSONArray("""["one", "two"]"""))
                }
            stub.client.execute(HttpRequest("GET", "/abc123"))
                .let { response ->
                    assertThat(response.status).isEqualTo(404)
                    assertThat(response.body.toStringLiteral()).isEqualTo("Could not find abc123")
                }
        }
    }

    @Test
    fun `expectations for header params from examples`() {
        HttpStub(featureWithHeaderParamExamples).use { stub ->
            stub.client.execute(HttpRequest("GET", "/hello", headers = mapOf("userId" to "John")))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body.toStringLiteral()).isEqualTo("Hello John")
                }
            stub.client.execute(HttpRequest("GET", "/hello", headers = mapOf("userId" to "Jane")))
                .let { response ->
                    assertThat(response.status).isEqualTo(401)
                    assertThat(response.body.toStringLiteral()).isEqualTo("User Jane not authorized")
                }
        }
    }

    @Test
    fun `expectations from examples ignores omitted parameters while matching stub request`() {
        HttpStub(featureWithOmittedParamExamples).use { stub ->
            stub.client.execute(HttpRequest("GET", "/hello", headers = mapOf("userId" to "John")))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body.toStringLiteral()).isEqualTo("Hello John")
                }
        }
    }

    @Test
    fun `expectations from inline example in response headers`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products:
    post:
      summary: create product
      description: create product
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
              REQUEST_BODY_REF_200:
                value:
                  name: 'Macbook'
      responses:
        '200':
          description: OK
          headers:
            X-RateLimit-Limit:
              schema:
                type: integer
              description: Request limit per hour.
              examples:
                REQUEST_BODY_REF_200:
                  value:
                    100
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/ProductId'
              examples:
                REQUEST_BODY_REF_200:
                  value:
                    id: 10
components:
  schemas:
    ProductId:
      title: Product Id
      type: object
      properties:
        id:
          type: integer
      required:
        - id
  examples:
    NAME_200:
      value:
        name: 'Macbook'""".trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract).use { stub ->
            stub.client.execute(HttpRequest("POST", "/products", emptyMap(), parsedJSONObject("""{"name": "Macbook"}""")))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.headers["X-RateLimit-Limit"]).isEqualTo("100")
                    assertThat(response.body).isEqualTo(
                        parsedJSONObject("""
                        {
                            "id": 10
                        }
                    """.trimIndent())
                    )
                }
        }
    }

    @Test
    fun `expectations with path param + header + request body`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products/{id}:
    parameters:
      - name: id
        in: path
        required: true
        schema:
          type: integer
        examples:
          SUCCESS:
            value: 10
      - name: trace-id
        in: header
        required: true
        schema:
          type: integer
        examples:
          SUCCESS:
            value: 10
    post:
      summary: create product
      description: create product
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
                  name: 'Macbook'
      responses:
        '200':
          description: OK
          content:
            text/plain:
              schema:
                type: string
              examples:
                SUCCESS:
                  value: success
""".trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract).use { stub ->
            val request = HttpRequest(
                "POST",
                "/products/10",
                mapOf("trace-id" to "10"),
                parsedJSONObject("""{"name": "Macbook"}""")
            )
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).isEqualTo("success")
        }
    }


    @Test
    fun `errors when loading examples with invalid values as expectations should mention the example name`() {
        val nameOfExample = "EXAMPLE_OF_SUCCESS"
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products/{id}:
    parameters:
      - name: id
        in: path
        required: true
        schema:
          type: integer
        examples:
          $nameOfExample:
            value: "abc"
    post:
      summary: create product
      description: create product
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
              $nameOfExample:
                value:
                  name: 'Macbook'
      responses:
        '200':
          description: OK
          content:
            text/plain:
              schema:
                type: string
              examples:
                $nameOfExample:
                  value: success
""".trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val (output, _) = captureStandardOutput {
            try {
                HttpStub(contract).close()
            } catch(_: Throwable) {
            }
        }

        assertThat(output).withFailMessage(output).contains("EXAMPLE_OF_SUCCESS")
    }

    @Test
    fun `should use multi-value dictionary when serving requests with no examples`() {
        val openApiFile = File("src/test/resources/openapi/spec_with_multi_value_dict/api.yaml")
        val feature = OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature()
        val assertNumberValue: (String) -> Unit = { it ->
            assertThat(it.toInt()).isIn(123, 456)
        }

        HttpStub(feature).use { stub ->
            val request201 = feature.scenarios.first().generateHttpRequest(DefaultStrategies)
            val response201 = stub.client.execute(request201)
            val response201Body = (response201.body as JSONObjectValue).jsonObject

            assertThat(response201.status).isEqualTo(201)
            assertNumberValue(response201Body["id"]!!.toStringLiteral())
            assertNumberValue(response201Body["petId"]!!.toStringLiteral())
            assertNumberValue(response201Body["creatorId"]!!.toStringLiteral())
            assertThat(response201Body["name"]!!.toStringLiteral()).isIn("Tom", "Jerry")
        }

        HttpStub(feature).use { stub ->
            val request400 = feature.scenarios.first().generateHttpRequest(DefaultStrategies)
            val response400 = stub.client.execute(request400.updateHeader(SPECMATIC_RESPONSE_CODE_HEADER, "400"))
            val response400Body = (response400.body as JSONObjectValue).jsonObject

            assertThat(response400.status).isEqualTo(400)
            assertNumberValue(response400Body["code"]!!.toStringLiteral())
            assertThat(response400Body["message"]!!.toStringLiteral()).isIn("Bad Request", "Internal Server Error")
        }
    }

    @Nested
    inner class AttributeSelection {
        @BeforeEach
        fun setup() {
            System.setProperty(ATTRIBUTE_SELECTION_QUERY_PARAM_KEY, "columns")
            System.setProperty(ATTRIBUTE_SELECTION_DEFAULT_FIELDS, "id")
        }

        @AfterEach
        fun tearDown() {
            System.clearProperty(ATTRIBUTE_SELECTION_QUERY_PARAM_KEY)
            System.clearProperty(ATTRIBUTE_SELECTION_DEFAULT_FIELDS)
        }

        private fun File.getExternalExamplesFromContract(): List<ScenarioStub> {
            val attributeExamples = this.parentFile.resolve("${this.nameWithoutExtension}$EXAMPLES_DIR_SUFFIX")
            val normalExamples = this.parentFile.resolve("${this.nameWithoutExtension}${EXAMPLES_DIR_SUFFIX}_no_attr")

            return attributeExamples.listFiles()?.map { ScenarioStub.readFromFile(it) }?.plus(
                normalExamples.listFiles()?.map { ScenarioStub.readFromFile(it) } ?: emptyList()
            ) ?: emptyList()
        }

        @Test
        fun `should match example when attribute selection is used and response is an object`() {
            val specFilepath = File("src/test/resources/openapi/attribute_selection_tests/api.yaml")

            val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
            val stubScenarios = specFilepath.getExternalExamplesFromContract()

            HttpStub(feature, stubScenarios).use {
                val response = it.client.execute(HttpRequest(
                    "GET",
                    "/employeesObjectResponse",
                    queryParametersMap = mapOf("columns" to "name")
                ))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers.keys).doesNotContain("X-Specmatic-Type")

                val body = (response.body as JSONObjectValue).jsonObject
                assertThat(body.keys).containsExactlyInAnyOrder("id", "name")
                assertThat(body.keys).doesNotContain("salary", "isActive")
            }
        }

        @Test
        fun `should match example when attribute selection is not used response is an object`() {
            val specFilepath = File("src/test/resources/openapi/attribute_selection_tests/api.yaml")

            val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
            val stubScenarios = specFilepath.getExternalExamplesFromContract()

            HttpStub(feature, stubScenarios).use {
                val response = it.client.execute(HttpRequest(
                    "GET",
                    "/employeesObjectResponse"
                ))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers.keys).doesNotContain("X-Specmatic-Type")

                val body = (response.body as JSONObjectValue).jsonObject
                assertThat(body.keys).containsExactlyInAnyOrder("id", "name", "salary")
                assertThat(body.keys).doesNotContain("isActive")
            }
        }

        @Test
        fun `should match example when attribute selection is used and response is an array`() {
            val specFilepath = File("src/test/resources/openapi/attribute_selection_tests/api.yaml")

            val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
            val stubScenarios = specFilepath.getExternalExamplesFromContract()

            HttpStub(feature, stubScenarios).use {
                val response = it.client.execute(HttpRequest(
                    "GET",
                    "/employeesArrayResponse",
                    queryParametersMap = mapOf("columns" to "name")
                ))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers.keys).doesNotContain("X-Specmatic-Type")

                val body = ((response.body as JSONArrayValue).list.first() as JSONObjectValue).jsonObject
                assertThat(body.keys).containsExactlyInAnyOrder("id", "name")
                assertThat(body.keys).doesNotContain("salary", "isActive")
            }
        }

        @Test
        fun `should match example when attribute selection is not used and response is an array`() {
            val specFilepath = File("src/test/resources/openapi/attribute_selection_tests/api.yaml")

            val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
            val stubScenarios = specFilepath.getExternalExamplesFromContract()

            HttpStub(feature, stubScenarios).use {
                val response = it.client.execute(HttpRequest(
                    "GET",
                    "/employeesArrayResponse"
                ))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers.keys).doesNotContain("X-Specmatic-Type")

                val body = ((response.body as JSONArrayValue).list.first() as JSONObjectValue).jsonObject
                assertThat(body.keys).containsExactlyInAnyOrder("id", "name", "salary")
                assertThat(body.keys).doesNotContain("isActive")
            }
        }

        @Test
        fun `should match example when attribute selection is used response is an allOf`() {
            val specFilepath = File("src/test/resources/openapi/attribute_selection_tests/api.yaml")

            val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
            val stubScenarios = specFilepath.getExternalExamplesFromContract()

            HttpStub(feature, stubScenarios).use {
                val response = it.client.execute(HttpRequest(
                    "GET",
                    "/employeesAllOfResponse",
                    queryParametersMap = mapOf("columns" to "name,department")
                ))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers.keys).doesNotContain("X-Specmatic-Type")

                val body = (response.body as JSONObjectValue).jsonObject
                assertThat(body.keys).containsExactlyInAnyOrder("id", "name", "department")
                assertThat(body.keys).doesNotContain("salary", "designation", "isActive")
            }
        }

        @Test
        fun `should match example when attribute selection is not used response is an allOf`() {
            val specFilepath = File("src/test/resources/openapi/attribute_selection_tests/api.yaml")

            val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
            val stubScenarios = specFilepath.getExternalExamplesFromContract()

            HttpStub(feature, stubScenarios).use {
                val response = it.client.execute(HttpRequest(
                    "GET",
                    "/employeesAllOfResponse"
                ))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers.keys).doesNotContain("X-Specmatic-Type")

                val body = (response.body as JSONObjectValue).jsonObject
                assertThat(body.keys).containsExactlyInAnyOrder("id", "name", "salary", "designation", "department")
                assertThat(body.keys).doesNotContain("isActive")
            }
        }

        @Test
        fun `should fallback to random generation when non-existent attribute is selected`() {
            val specFilepath = File("src/test/resources/openapi/attribute_selection_tests/api.yaml")

            val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
            val stubScenarios = specFilepath.getExternalExamplesFromContract()

            HttpStub(feature, stubScenarios).use {
                val response = it.client.execute(HttpRequest(
                    "GET",
                    "/employeesObjectResponse",
                    queryParametersMap = mapOf("columns" to "name,unknownKey")
                ))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers["X-Specmatic-Type"]).isEqualTo("random")

                val body = (response.body as JSONObjectValue).jsonObject
                assertThat(body.keys).containsExactlyInAnyOrder("id", "name", "salary", "isActive")
            }
        }
    }
}
