package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.Value
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class RefPartParsingTests {
    @Test
    fun `path parameter ref`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello/{id}:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - ${"$"}ref: '#/components/parameters/Id'
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                200_OK:
                  value: success
components:
  parameters:
    Id:
      in: path
      name: id
      schema:
        type: integer
      examples:
        200_OK:
          value: 10
      required: true
      description: Numeric ID
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/hello/10")
                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.success()).isTrue()
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `path example ref`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello/{id}:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - ${"$"}ref: '#/components/parameters/Id'
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                200_OK:
                  value: success
components:
  parameters:
    Id:
      in: path
      name: id
      schema:
        type: integer
      examples:
        200_OK:
          ${"$"}ref: '#/components/examples/Id_Request_Example'
      required: true
      description: Numeric ID
  examples:
    Id_Request_Example:
      value: 10
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/hello/10")
                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.success()).isTrue()
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `request header ref`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - ${"$"}ref: '#/components/parameters/HelloRequestHeader'
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                200_OK:
                  value: success
components:
  parameters:
    HelloRequestHeader:
      in: header
      name: X-HelloRequestHeader
      schema:
        type: string
      examples:
        200_OK:
          value: helloworld
      required: true
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/hello")
                assertThat(request.headers["X-HelloRequestHeader"]).isEqualTo("helloworld")

                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `request header example ref`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - ${"$"}ref: '#/components/parameters/HelloRequestHeader'
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                200_OK:
                  value: success
components:
  parameters:
    HelloRequestHeader:
      in: header
      name: X-HelloRequestHeader
      schema:
        type: string
      examples:
        200_OK:
          ${"$"}ref: '#/components/examples/Request_Header_Example'
      required: true
  examples:
    Request_Header_Example:
      value: helloworld
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/hello")
                assertThat(request.headers["X-HelloRequestHeader"]).isEqualTo("helloworld")

                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    @Disabled
    fun `response header ref`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello:
    get:
      summary: hello world
      parameters:
        - in: header
          name: X-HelloRequestHeader
          schema:
            type: string
          examples:
            200_OK:
              value: helloworld
      responses:
        '201':
          description: Created
          headers:
            X-HelloResponseHeader:
              ${"$"}ref: '#/components/headers/HelloResponseHeader'
components:
  headers:
    HelloResponseHeader:
      schema:
        type: string
      examples:
        200_OK:
          value: helloworld
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/hello")
                assertThat(request.headers["X-HelloRequestHeader"]).isEqualTo("helloworld")

                return HttpResponse(status = 201, headers = mapOf("X-HelloResponseHeader" to "helloworld"))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.report())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `response header ref where header component is missing`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello:
    get:
      summary: hello world
      responses:
        '200':
          description: Says hello
          headers:
            X-HelloResponseHeader:
              ${"$"}ref: '#/components/headers/HelloResponseHeader'
          content:
            application/json:
              schema:
                type: string
              examples:
                200_OK:
                  value: success
        """.trimIndent()

        assertThatThrownBy {
            OpenApiSpecification.fromYAML(specification, "").toFeature()
        }
            .isInstanceOf(ContractException::class.java)
            .satisfies( {
                assertThat(exceptionCauseMessage(it)).contains("The element referred to at this path was not found in the spec. Please add the missing header named \"HelloResponseHeader\"")
            })
    }

    @Test
    fun `query param ref`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - ${"$"}ref: '#/components/parameters/Id'
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                200_OK:
                  value: success
components:
  parameters:
    Id:
      in: query
      name: id
      schema:
        type: integer
      examples:
        200_OK:
          value: 10
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/hello")
                assertThat(request.queryParams.getValues("id").first()).isEqualTo("10")

                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.successCount).isEqualTo(1)
        assertThat(results.failureCount).isEqualTo(0)
    }

    @Test
    fun `query param example ref`() {
        val specification = """
openapi: 3.0.0
info:
  title: Sample API
paths:
  /hello:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - ${"$"}ref: '#/components/parameters/Id'
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
              examples:
                200_OK:
                  value: success
components:
  parameters:
    Id:
      in: query
      name: id
      schema:
        type: integer
      examples:
        200_OK:
          ${"$"}ref: '#/components/examples/Query_Parameter_Example'
      required: true
      description: Numeric ID
  examples:
    Query_Parameter_Example:
      value: 10
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path).isEqualTo("/hello")
                assertThat(request.queryParams.getValues("id").first()).isEqualTo("10")

                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `request body ref`() {
        val specification = """
---
openapi: "3.0.1"
info:
  title: "Person API"
paths:
  /person:
    post:
      summary: "Get person by id"
      parameters: []
      requestBody:
        ${"$"}ref: '#/components/requestBodies/PersonRequest'
      responses:
        200:
          description: "Get person by id"
          content:
            text/plain:
              schema:
                type: "string"
              examples:
                200_OK:
                  value: "success"
components:
  requestBodies:
    PersonRequest:
      content:
        application/json:
          schema:
            required:
            - "id"
            properties:
              id:
                type: "string"
          examples:
            200_OK:
              value:
                id: "abc123"
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val expectedRequest = parsedJSONObject("""{"id":"abc123"}""")
                assertThat(request.body).isEqualTo(expectedRequest)
                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.distinctReport())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `request body with nested allOf refs`() {
        val specification = """
---
openapi: "3.0.1"
info:
  title: "Person API"
paths:
  /person:
    post:
      summary: "Get person by id"
      parameters: []
      requestBody:
        ${"$"}ref: '#/components/requestBodies/PersonRequest'
      responses:
        200:
          description: "Get person by id"
          content:
            text/plain:
              schema:
                type: "string"
              examples:
                200_OK:
                  value: "success"
components:
  schemas:
    Id:
      type: object
      properties:
        id:
          type: string
      required:
        - id
    OtherData:
      allOf:
        - type: object
          properties:
            name:
              type: string
          required:
            - name
        - ${"$"}ref: '#/components/schemas/Description'
    Description:
      type: object
      properties:
        description:
          type: string
      required:
          - description
  requestBodies:
    PersonRequest:
      content:
        application/json:
          schema:
            allOf:
              - ${"$"}ref: '#/components/schemas/Id'
              - ${"$"}ref: '#/components/schemas/OtherData'
          examples:
            200_OK:
              value:
                id: "abc123"
                name: "Jane"
                description: "A person"
        """.trimIndent()

        val apiSpecification = OpenApiSpecification.fromYAML(specification, "")
        val feature = apiSpecification.toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val expectedRequest = parsedJSONObject("""{"id":"abc123", "name": "Jane", "description": "A person"}""")
                assertThat(request.body).isEqualTo(expectedRequest)
                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.distinctReport())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `response body ref`() {
        val specification = """
---
openapi: "3.0.1"
info:
  title: "Person API"
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
              - "name"
              properties:
                name:
                  type: "string"
            examples:
              200_OK:
                value:
                  name: "Jack"
      responses:
        200:
          ${"$"}ref: '#/components/responses/IdResponse'
components:
  responses:
    IdResponse:
      description: "Get person by id"
      content:
        application/json:
          schema:
            required:
            - "id"
            properties:
              id:
                type: integer
          examples:
            200_OK:
              value:
                id: 123
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val expectedRequest = parsedJSONObject("""{"name":"Jack"}""")
                assertThat(request.body).isEqualTo(expectedRequest)
                return HttpResponse.ok(parsedJSONObject("""{"id":123}"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.distinctReport())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `request body example ref`() {
        val specification = """
---
openapi: "3.0.1"
info:
  title: "Person API"
paths:
  /person:
    post:
      summary: "Get person by id"
      requestBody:
        ${"$"}ref: '#/components/requestBodies/Request_Body' 
      responses:
        200:
          description: "Get person by id"
          content:
            application/json:
              schema:
                required:
                - "id"
                properties:
                  id:
                    type: integer
              examples:
                200_OK:
                  value:
                    id: 123
components:
  requestBodies:
    Request_Body:
      content:
        application/json:
          schema:
            required:
            - "name"
            properties:
              name:
                type: "string"
          examples:
            200_OK:
              ${"$"}ref: '#/components/examples/200_OK_Request_Example' 
  examples:
    200_OK_Request_Example:
      value:
        name: "Jack"

        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val expectedRequest = parsedJSONObject("""{"name":"Jack"}""")
                assertThat(request.body).isEqualTo(expectedRequest)
                return HttpResponse.ok(parsedJSONObject("""{"id":123}"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.distinctReport())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }

    @Test
    fun `response body example ref`() {
        val specification = """
---
openapi: "3.0.1"
info:
  title: "Person API"
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
              - "name"
              properties:
                name:
                  type: "string"
            examples:
              200_OK:
                value:
                  name: "Jack"
      responses:
        200:
          description: "Get person by id"
          content:
            application/json:
              schema:
                required:
                - "id"
                properties:
                  id:
                    type: integer
              examples:
                200_OK:
                  ${"$"}ref: '#/components/examples/200_OK_Response_Example'
components:
  examples:
    200_OK_Response_Example:
      value:
        id: 123

        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val expectedRequest = parsedJSONObject("""{"name":"Jack"}""")
                assertThat(request.body).isEqualTo(expectedRequest)
                return HttpResponse.ok(parsedJSONObject("""{"id":123}"""))
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        println(results.distinctReport())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isEqualTo(1)
    }
}
