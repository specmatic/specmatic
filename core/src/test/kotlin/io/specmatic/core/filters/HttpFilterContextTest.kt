package io.specmatic.core.filters

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Scenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class HttpFilterContextTest {
    @ParameterizedTest
    @MethodSource("provideTestParameters")
    fun `test includes method with various parameters`(
        key: String,
        value: String,
        expected: Boolean,
        scenario: Scenario
    ) {
        val httpFilterContext = HttpFilterContext(scenario)
        assertThat(httpFilterContext.includes(key, listOf(value))).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("provideTestParametersForCompare")
    fun `test compare method with various parameters`(
        key: String,
        operator: String,
        value: String,
        expected: Boolean,
        scenario: Scenario
    ) {
        val httpFilterContext = HttpFilterContext(scenario)
        assertThat(httpFilterContext.compare(key, operator, value)).isEqualTo(expected)
    }

    companion object {

        private val simpleSpecWithOneGetUrl = OpenApiSpecification.fromYAML(
            """
openapi: "3.0.1"
info:
  version: "1"
paths:
  /foo:
    get:
      parameters:
        - name: type
          schema:
            type: string
          in: query
          required: true
        - name: authentication
          schema:
            type: string
          in: header
          required: false
      responses:
        "200":
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
            application/vnd.acme+foo:
              schema:
                type: array
                vendor_items:
                  type:
                    string
              examples:
                QUERY_SUCCESS:
                  value: ["one", "two"]
        """.trim(), ""
        ).toFeature()

        private val usersGetAPI = OpenApiSpecification.fromYAML(
            """
openapi: "3.0.1"
info:
  version: "1"
paths:
  /users/{id}:
    get:
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
          examples:
            SUCCESS:
              value: 1
      responses:
        "200":
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
              examples:
                SUCCESS:
                  value:
                    id: 1
                    name: "John Doe"
        "400":
          content:
            application/json:
              schema:
                type: object
                properties:
                  error:
                    type: string
                """.trim(), ""
        ).toFeature()


        private val usersPostAPI = OpenApiSpecification.fromYAML(
            """
openapi: "3.0.1"
info:
  version: "1"
paths:
  /users:
    post:
      summary: "Create a new user"
      operationId: "createUser"
      description: "This is a create user endpoint"
      tags:
      - "users"
      parameters:
        - name: authentication
          schema:
            type: string
          in: header
          required: false
        - name: csrf-token
          schema:
            type: string
          in: cookie
          required: false
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                name:
                  type: string
                age:
                  type: integer
          text/plain:
            schema:
              type: string
      responses:
        "200":
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
            text/plain:
              schema:
                type: string
                
        "400":
          content:
            application/json:
              schema:
                type: object
                properties:
                  error:
                    type: string
              """.trim(), ""
        ).toFeature()


        private val usersSearchApi = OpenApiSpecification.fromYAML(
            """

openapi: "3.0.1"
info:
  version: "1"
paths:
  /users:
    get:
      parameters:
        - name: byId
          in: query
          required: false
          schema:
            type: integer
          examples:
            example1:
              value: 42
            example2:
              value: 123
        - name: byName
          in: query
          required: false
          schema:
            type: string
          examples:
            example1:
              value: "John"
            example2:
              value: "Jane"
      responses:
        "200":
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
              examples:
                example1:
                  value:
                    id: 42
                    name: "John Doe"
                example2:
                  value:
                    id: 123
                    name: "Jane Smith"
        "400":
          content:
            application/json:
              schema:
                type: object
                properties:
                  error:
                    type: string
                
            """.trimIndent(), ""
        ).toFeature()

        @JvmStatic
        fun provideTestParameters() = listOf(
            // paths are case-sensitive
            Arguments.of("PATH", "/foo", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("PATH", "/FOO", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("PATH", "/non-existant", false, simpleSpecWithOneGetUrl.scenarios[0]),

            // path globs
            Arguments.of("PATH", "/*", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("PATH", "/bob/*", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("PATH", "/users/*", true, usersGetAPI.scenarios[0]),
            Arguments.of("PATH", "/non-existant/*", false, usersGetAPI.scenarios[0]),
            Arguments.of("PATH", "/users/{id}", true, usersGetAPI.scenarios[0]),
            Arguments.of("PATH", "/users/{userId}", false, usersGetAPI.scenarios[0]),
            Arguments.of("PATH", "/users/(id:integer)", false, usersGetAPI.scenarios[0]),
            Arguments.of("PATH", "/users/(id:number)", false, usersGetAPI.scenarios[0]),
            Arguments.of("PATH", "/users/(id: number)", false, usersGetAPI.scenarios[0]),

            // params are case-sensitive
            Arguments.of("PATH", "/users/(ID:number)", false, usersGetAPI.scenarios[0]),

            // inline query params
            Arguments.of("PATH", "/foo?bar=123", false, simpleSpecWithOneGetUrl.scenarios[0]),

            // path parameter
            Arguments.of("PARAMETERS.PATH", "id", true, usersGetAPI.scenarios[0]),
            Arguments.of("PARAMETERS.PATH", "userId", false, usersGetAPI.scenarios[0]),

            // path parameter with key value
            Arguments.of("PARAMETERS.PATH.id", "1", true, usersGetAPI.scenarios[0]),
            Arguments.of("PARAMETERS.PATH.id", "10", false, usersGetAPI.scenarios[0]),

            // header keys are case-insensitive
            Arguments.of("PARAMETERS.HEADER", "AUTHenTICATION", true, simpleSpecWithOneGetUrl.scenarios[0]),

            Arguments.of("PARAMETERS.HEADER", "content-type", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("PARAMETERS.HEADER", "non-existent-header", false, simpleSpecWithOneGetUrl.scenarios[0]),

            // query param keys are case-sensitive
            Arguments.of("PARAMETERS.QUERY", "type", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("PARAMETERS.QUERY", "TYPE", false, simpleSpecWithOneGetUrl.scenarios[0]),

            // specific query params
            Arguments.of("PARAMETERS.QUERY.byId", "123", true, usersSearchApi.scenarios[0]),
            Arguments.of("PARAMETERS.QUERY.byId", "ABC", false, usersSearchApi.scenarios[0]),

            Arguments.of("PARAMETERS.QUERY.byName", "John", true, usersSearchApi.scenarios[0]),
            Arguments.of("PARAMETERS.QUERY.byName", "Bob", false, usersSearchApi.scenarios[0]),

            // methods are case-insensitive
            Arguments.of("METHOD", "GET", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("METHOD", "Get", true, simpleSpecWithOneGetUrl.scenarios[0]),

            // status
            Arguments.of("STATUS", "200", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("STATUS", "201", false, simpleSpecWithOneGetUrl.scenarios[0]),

            // example name
            Arguments.of("EXAMPLE-NAME", "QUERY_SUCCESS", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("EXAMPLE-NAME", "NON_EXISTENT_EXAMPLE", false, simpleSpecWithOneGetUrl.scenarios[0]),

            // response content type for get requests
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/*", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/json", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/j*", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/json*", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/*+json", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/*json", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "application/foo", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "foo", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("RESPONSE.CONTENT-TYPE", "text/plain", true, usersPostAPI.scenarios[1]),

            // body content type for post requests
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "application/*", true, usersPostAPI.scenarios[0]),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "application/json", true, usersPostAPI.scenarios[0]),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "application/json*", false, usersPostAPI.scenarios[0]),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "text/plain", true, usersPostAPI.scenarios[1]),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "text/*", true, usersPostAPI.scenarios[1]),
            Arguments.of("REQUEST-BODY.CONTENT-TYPE", "text/p*", false, usersPostAPI.scenarios[1]),

            // tags
            Arguments.of("TAGS", "users", true, usersPostAPI.scenarios[0]),
            Arguments.of("TAGS", "pet", false, usersPostAPI.scenarios[0]),

            // summary
            Arguments.of("SUMMARY", "create a new user", true, usersPostAPI.scenarios[0]),
            Arguments.of("SUMMARY", "create a random user", false, usersPostAPI.scenarios[0]),

            // description
            Arguments.of("DESCRIPTION", "This is a create user endpoint", true, usersPostAPI.scenarios[0]),
            Arguments.of("DESCRIPTION", "create a random user", false, usersPostAPI.scenarios[0]),

            // operationId
            Arguments.of("OPERATION-ID", "createUser", true, usersPostAPI.scenarios[0]),
            Arguments.of("OPERATION-ID", "createEndpoint", false, usersPostAPI.scenarios[0]),
        )

        @JvmStatic
        fun provideTestParametersForCompare() = listOf(
            Arguments.of("STATUS", ">", "200", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("STATUS", "<", "200", false, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("STATUS", ">=", "200", true, simpleSpecWithOneGetUrl.scenarios[0]),
            Arguments.of("STATUS", "<=", "200", true, simpleSpecWithOneGetUrl.scenarios[0]),

            // 200 status code for /users/{id} endpoint
            Arguments.of("STATUS", ">", "200", false, usersGetAPI.scenarios[0]),
            Arguments.of("STATUS", "<", "200", false, usersGetAPI.scenarios[0]),
            Arguments.of("STATUS", ">=", "200", true, usersGetAPI.scenarios[0]),
            Arguments.of("STATUS", "<=", "200", true, usersGetAPI.scenarios[0]),

            // 400 status code for /users/{id} path
            Arguments.of("STATUS", ">", "200", true, usersGetAPI.scenarios[1]),
            Arguments.of("STATUS", "<", "200", false, usersGetAPI.scenarios[1]),
            Arguments.of("STATUS", ">=", "200", true, usersGetAPI.scenarios[1]),
            Arguments.of("STATUS", "<=", "200", false, usersGetAPI.scenarios[1]),
        )
    }
}
