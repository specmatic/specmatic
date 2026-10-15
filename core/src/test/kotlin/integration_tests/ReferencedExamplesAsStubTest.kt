package integration_tests

import io.specmatic.testPort
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReferencedExamplesAsStubTest {

    @Test
    fun `expectations from referenced examples in query parameters`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products:
    get:
      summary: get products
      description: Get multiple products filtered by Brand Ids
      parameters:
        - name: brand_id
          in: query
          required: true
          schema:
            type: number
          examples:
            QUERY_REF_200:
             ${'$'}ref: '#/components/examples/BRAND_ID_200'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items:
                  ${'$'}ref: '#/components/schemas/Product'
              examples:
                QUERY_REF_200:
                  value:
                    - id: 1
                      name: 'Macbook'
                      brand_id: 1
                    - id: 2
                      name: 'IPhone'
                      brand_id: 1
                    - id: 3
                      name: 'IPad'
                      brand_id: 1
components:
  schemas:
    Product:
      title: Product
      type: object
      properties:
        id:
          type: integer
        name:
          type: string
        brand_id:
          type: integer
      required:
        - id
        - name
        - brand_id
  examples:
    BRAND_ID_200:
      value:
        10""".trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract, port = testPort()).use { stub ->
            stub.client.execute(HttpRequest("GET", "/products?brand_id=10"))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(
                        parsedJSONArray("""
                        [
                            {
                                "id": 1,
                                "name": "Macbook",
                                "brand_id": 1
                            },
                            {
                                "id": 2,
                                "name": "IPhone",
                                "brand_id": 1
                            },
                            {
                                "id": 3,
                                "name": "IPad",
                                "brand_id": 1
                            }
                        ]
                    """.trimIndent())
                    )
                }
        }
    }

    @Test
    fun `expectations from referenced examples in path parameters`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products/{productId}:
    get:
      summary: get product
      description: Get product by productId
      parameters:
        - name: productId
          in: path
          required: true
          schema:
            type: number
          examples:
            PATH_REF_200:
              ${'$'}ref: '#/components/examples/PRODUCT_ID_200'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Product'
              examples:
                PATH_REF_200:
                  value:
                    id: 10
                    name: 'Macbook'
components:
  schemas:
    Product:
      title: Product
      type: object
      properties:
        id:
          type: integer
        name:
          type: string
      required:
        - id
        - name
  examples:
    PRODUCT_ID_200:
      value:
        10""".trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract, port = testPort()).use { stub ->
            stub.client.execute(HttpRequest("GET", "/products/10"))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(
                        parsedJSONObject("""
                        {
                            "id": 10,
                            "name": "Macbook"
                        }
                    """.trimIndent())
                    )
                }
        }
    }

    @Test
    fun `expectations from referenced examples in header parameters`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products:
    get:
      summary: get products
      description: Get all products
      parameters:
        - name: Authenticate
          in: header
          required: true
          schema:
            type: string
          examples:
            HEADER_REF_200:
              ${'$'}ref: '#/components/examples/AUTHENTICATE_200'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Product'
              examples:
                HEADER_REF_200:
                  value:
                    id: 10
                    name: 'Macbook'
components:
  schemas:
    Product:
      title: Product
      type: object
      properties:
        id:
          type: integer
        name:
          type: string
      required:
        - id
        - name
  examples:
    AUTHENTICATE_200:
      value:
        'ABCD1234'""".trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract, port = testPort()).use { stub ->
            stub.client.execute(HttpRequest("GET", "/products", mapOf("Authenticate" to "ABCD1234")))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(
                        parsedJSONObject("""
                        {
                            "id": 10,
                            "name": "Macbook"
                        }
                    """.trimIndent())
                    )
                }
        }
    }

    @Test
    fun `expectations from referenced examples in request body`() {
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
                ${'$'}ref: '#/components/examples/NAME_200'
      responses:
        '200':
          description: OK
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
        HttpStub(contract, port = testPort()).use { stub ->
            stub.client.execute(HttpRequest("POST", "/products", emptyMap(), parsedJSONObject("""{"name": "Macbook"}""")))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
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
    fun `expectations from referenced examples in response headers`() {
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
                  ${'$'}ref: '#/components/examples/RateLimitHeader200'
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
    RateLimitHeader200:
      value:
        100
    NAME_200:
      value:
        name: 'Macbook'""".trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract, port = testPort()).use { stub ->
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
    fun `expectations from referenced examples in response body`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products/{productId}:
    get:
      summary: get product
      description: Get product by productId
      parameters:
        - name: productId
          in: path
          required: true
          schema:
            type: number
          examples:
            PATH_REF_200:
              value:
                10
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Product'
              examples:
                PATH_REF_200:
                  ${'$'}ref: '#/components/examples/RESPONSE_200'
components:
  schemas:
    Product:
      title: Product
      type: object
      properties:
        id:
          type: integer
        name:
          type: string
      required:
        - id
        - name
  examples:
    RESPONSE_200:
      value:
        id: 10
        name: 'Macbook'
        """.trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract, port = testPort()).use { stub ->
            stub.client.execute(HttpRequest("GET", "/products/10"))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(
                        parsedJSONObject("""
                        {
                            "id": 10,
                            "name": "Macbook"
                        }
                    """.trimIndent())
                    )
                }
        }
    }

    @Test
    fun `expectations from referenced examples in path parameters and response body`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products/{productId}:
    get:
      summary: get product
      description: Get product by productId
      parameters:
        - name: productId
          in: path
          required: true
          schema:
            type: number
          examples:
            PATH_REF_200:
              ${'$'}ref: '#/components/examples/PRODUCT_ID_200'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Product'
              examples:
                PATH_REF_200:
                  ${'$'}ref: '#/components/examples/RESPONSE_200'
components:
  schemas:
    Product:
      title: Product
      type: object
      properties:
        id:
          type: integer
        name:
          type: string
      required:
        - id
        - name
  examples:
    PRODUCT_ID_200:
      value:
        10
    RESPONSE_200:
      value:
        id: 10
        name: 'Macbook'
        """.trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        HttpStub(contract, port = testPort()).use { stub ->
            stub.client.execute(HttpRequest("GET", "/products/10"))
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(
                        parsedJSONObject("""
                        {
                            "id": 10,
                            "name": "Macbook"
                        }
                    """.trimIndent())
                    )
                }
        }
    }

    @Test
    fun `expectations from stub json alongside referenced examples in path parameters and response body`() {
        val spec = """
openapi: 3.0.0
info:
  title: Product API
  version: 0.1.9
paths:
  /products/{productId}:
    get:
      summary: get product
      description: Get product by productId
      parameters:
        - name: productId
          in: path
          required: true
          schema:
            type: number
          examples:
            PATH_REF_200:
              ${'$'}ref: '#/components/examples/PRODUCT_ID_200'
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                ${'$'}ref: '#/components/schemas/Product'
              examples:
                PATH_REF_200:
                  ${'$'}ref: '#/components/examples/RESPONSE_200'
components:
  schemas:
    Product:
      title: Product
      type: object
      properties:
        id:
          type: integer
        name:
          type: string
      required:
        - id
        - name
  examples:
    PRODUCT_ID_200:
      value:
        10
    RESPONSE_200:
      value:
        id: 10
        name: 'Macbook'
        """.trimIndent()

        val contract = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val request = HttpRequest("GET", "/products/20")
        val responseBodyFromJSONExpectationFile = parsedJSONObject(
            """
                        {
                            "id": 20,
                            "name": "iPad"
                        }
                    """.trimIndent()
        )
        val response = HttpResponse(200, body = responseBodyFromJSONExpectationFile)

        val expectationFromFile = listOf(ScenarioStub(request, response))

        HttpStub(contract, expectationFromFile, port = testPort()).use { stub ->
            stub.client.execute(request)
                .let { response ->
                    assertThat(response.status).isEqualTo(200)
                    assertThat(response.body).isEqualTo(
                        responseBodyFromJSONExpectationFile
                    )
                }
        }
    }
}
