package io.specmatic.conversions.links

import io.specmatic.conversions.OperationMetadata
import io.specmatic.core.DEFAULT_RESPONSE_CODE
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpQueryParamPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.ReturnFailure
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.unwrapOrContractException
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.links.Link
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.servers.ServerVariable
import io.swagger.v3.oas.models.servers.ServerVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OpenApiLinkTest {
    @Test
    fun `should be able to convert parameters into http-request with prefixes`() {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = HttpPathPattern.from("/test/(pathParam:string)"), method = "POST",
                    httpQueryParamPattern = HttpQueryParamPattern(mapOf("queryParam" to StringPattern())),
                    headersPattern = HttpHeadersPattern(mapOf("headerParam" to NumberPattern())),
                ),
                httpResponsePattern = HttpResponsePattern(status = 201),
                operationMetadata = OperationMetadata(operationId = "testPost"),
            ),
        )

        val parameters = buildMap {
            put("path.pathParam", OpenApiValueOrLinkExpression.from("pathValue", "TEST").value)
            put("query.queryParam", OpenApiValueOrLinkExpression.from("queryValue", "TEST").value)
            put("header.headerParam", OpenApiValueOrLinkExpression.from(123, "TEST").value)
        }

        val httpRequest = OpenApiLink(
            name = "TEST",
            parameters = parameters,
            forOperation = OpenApiOperationReference("/test", "POST", 201, "testPost"),
            byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet"),
        ).toHttpRequest(scenario).unwrapOrContractException()

        assertThat(httpRequest.path).isEqualTo("/test/pathValue")
        assertThat(httpRequest.queryParams.getOrDefault("queryParam", "null")).isEqualTo("queryValue")
        assertThat(httpRequest.headers["headerParam"]).isEqualTo("123")
    }

    @Test
    fun `should be able to convert parameters into http-request with no prefixes`() {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    httpPathPattern = HttpPathPattern.from("/test/(pathParam:string)"), method = "POST",
                    httpQueryParamPattern = HttpQueryParamPattern(mapOf("queryParam" to StringPattern())),
                    headersPattern = HttpHeadersPattern(mapOf("headerParam" to NumberPattern())),
                ),
                httpResponsePattern = HttpResponsePattern(status = 201),
                operationMetadata = OperationMetadata(operationId = "testPost"),
            ),
        )

        val parameters = buildMap {
            put("pathParam", OpenApiValueOrLinkExpression.from("pathValue", "TEST").value)
            put("queryParam", OpenApiValueOrLinkExpression.from("queryValue", "TEST").value)
            put("headerParam", OpenApiValueOrLinkExpression.from(123, "TEST").value)
        }

        val httpRequest = OpenApiLink(
            name = "TEST",
            parameters = parameters,
            forOperation = OpenApiOperationReference("/test", "POST", 201, "testPost"),
            byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet"),
        ).toHttpRequest(scenario).unwrapOrContractException()

        assertThat(httpRequest.path).isEqualTo("/test/pathValue")
        assertThat(httpRequest.queryParams.getOrDefault("queryParam", "null")).isEqualTo("queryValue")
        assertThat(httpRequest.headers["headerParam"]).isEqualTo("123")
    }

    @Test
    fun `should complain if one of the path parameters have not beed defined, as the path cannot be created without all`() {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from("/test/(param1:string)/(param2:string)"), method = "POST",),
                httpResponsePattern = HttpResponsePattern(status = 201),
                operationMetadata = OperationMetadata(operationId = "testPost"),
            ),
        )

        val parameters = buildMap {
            put("param1", OpenApiValueOrLinkExpression.from("param1Value", "TEST").value)
        }

        val httpRequestReturnValue = OpenApiLink(
            name = "TEST",
            parameters = parameters,
            forOperation = OpenApiOperationReference("/test", "POST", 201, "testPost"),
            byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet"),
        ).toHttpRequest(scenario)

        assertThat(httpRequestReturnValue).isInstanceOf(HasFailure::class.java); httpRequestReturnValue as HasFailure
        assertThat(httpRequestReturnValue.toFailure().reportString()).isEqualToIgnoringWhitespace("""
        >> LINKS.TEST.parameters.param2
        Expected mandatory path parameter 'param2' is missing from link parameters
        """.trimIndent())
    }

    @Nested
    inner class OpenApiLinkParsingTest {
        @MethodSource("io.specmatic.conversions.links.OpenApiLinkTest#validOpenApiLinks")
        @ParameterizedTest
        fun `should be able to parse openapi link object to an internal representation`(arguments: Pair<OpenAPI, Link>) {
            val (openapi, link) = arguments
            val byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet")
            val openApiLink = OpenApiLink.from(openapi, byOperation, "TEST",link)
            assertThat(openApiLink)
                .withFailMessage { (openApiLink as? ReturnFailure)?.toFailure()?.reportString().orEmpty() }
                .isInstanceOf(HasValue::class.java)
        }

        @MethodSource("io.specmatic.conversions.links.OpenApiLinkTest#inValidOpenApiLinks")
        @ParameterizedTest
        fun `should return failure when parsing invalid openapi link object to an internal representation`(arguments: Triple<OpenAPI, Link, String>) {
            val (openapi, link, errorMessage) = arguments
            val byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet")
            val openApiLink = OpenApiLink.from(openapi, byOperation, "TEST",link)

            assertThat(openApiLink).isInstanceOf(ReturnFailure::class.java); openApiLink as ReturnFailure
            assertThat(openApiLink.toFailure().reportString()).isEqualToIgnoringWhitespace(errorMessage)
        }

        @Test
        fun `should pick the first available 2xx statusCode when extension is not specified for an given operationId`() {
            val openAPI = OpenAPI()
                .withOperation("/test", "POST", "400", "testPost")
                .withOperation("/test", "POST", "201", "testPost")
            val link = Link().apply { operationId = "testPost" }
            val byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet")
            val openApiLink = OpenApiLink.from(openAPI, byOperation, "TEST", link)

            assertThat(openApiLink).isInstanceOf(HasValue::class.java); openApiLink as HasValue
            assertThat(openApiLink.value.forOperation.path).isEqualTo("/test")
            assertThat(openApiLink.value.forOperation.method).isEqualTo("POST")
            assertThat(openApiLink.value.forOperation.status).isEqualTo(201)
            assertThat(openApiLink.value.forOperation.operationId).isEqualTo("testPost")
        }

        @Test
        fun `should pick the first available 2xx statusCode when extension is not specified for an given operationRef`() {
            val openAPI = OpenAPI()
                .withOperation("/test", "POST", "400", null)
                .withOperation("/test", "POST", "201", null)
            val link = Link().apply { operationRef = "#/paths/~1test/post" }
            val byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet")
            val openApiLink = OpenApiLink.from(openAPI, byOperation, "TEST", link)

            assertThat(openApiLink).isInstanceOf(HasValue::class.java); openApiLink as HasValue
            assertThat(openApiLink.value.forOperation.path).isEqualTo("/test")
            assertThat(openApiLink.value.forOperation.method).isEqualTo("POST")
            assertThat(openApiLink.value.forOperation.status).isEqualTo(201)
            assertThat(openApiLink.value.forOperation.operationId).isNull()
        }

        @Test
        fun `should pick the default status-code if it exists in the specification and no other 2xx response is specified`() {
            val openAPI = OpenAPI()
                .withOperation("/test", "POST", "400", "testPost")
                .withOperation("/test", "POST", "default", "testPost")
            val link = Link().apply { operationId = "testPost" }
            val byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet")
            val openApiLink = OpenApiLink.from(openAPI, byOperation, "TEST", link)

            assertThat(openApiLink).isInstanceOf(HasValue::class.java); openApiLink as HasValue
            assertThat(openApiLink.value.forOperation.path).isEqualTo("/test")
            assertThat(openApiLink.value.forOperation.method).isEqualTo("POST")
            assertThat(openApiLink.value.forOperation.status).isEqualTo(DEFAULT_RESPONSE_CODE)
            assertThat(openApiLink.value.forOperation.operationId).isEqualTo("testPost")
        }

        @Test
        fun `should pick the first available statusCode if 2xx and default statuses don't exist`() {
            val openAPI = OpenAPI().withOperation("/test", "POST", "400", "testPost")
            val link = Link().apply { operationId = "testPost" }
            val byOperation = OpenApiOperationReference("/test", "GET", 200, "testGet")
            val openApiLink = OpenApiLink.from(openAPI, byOperation, "TEST", link)

            assertThat(openApiLink).isInstanceOf(HasValue::class.java); openApiLink as HasValue
            assertThat(openApiLink.value.forOperation.path).isEqualTo("/test")
            assertThat(openApiLink.value.forOperation.method).isEqualTo("POST")
            assertThat(openApiLink.value.forOperation.status).isEqualTo(400)
            assertThat(openApiLink.value.forOperation.operationId).isEqualTo("testPost")
        }
    }

    companion object {
        @JvmStatic
        fun validOpenApiLinks(): List<Pair<OpenAPI, Link>> {
            return listOf(
                Pair(
                    first = OpenAPI().withOperation("/test", "get", "200", "getTest"),
                    second = Link().apply { operationId = "getTest" },
                ),
                Pair(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply { operationRef = "#/paths/~1test~1{id}/get" },
                ),
                Pair(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                    },
                ),
                Pair(
                    first = OpenAPI().withOperation("/test/{id}", "get", "default", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                        extensions = mapOf("x-StatusCode" to "404")
                    },
                ),
                Pair(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                        extensions = mapOf("x-Partial" to "true")
                    },
                ),
                Pair(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                        extensions = mapOf("x-Partial" to false)
                    },
                ),
            )
        }

        @JvmStatic
        fun inValidOpenApiLinks(): List<Triple<OpenAPI, Link, String>> {
            return listOf(
                Triple(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link(),
                    """
                    >> LINKS.TEST
                    Must define either operationId or operationRef in OpenApi Link
                    """.trimIndent(),
                ),
                Triple(
                    first = OpenAPI().withOperation("/test", "get", "200", "getTest"),
                    second = Link().apply { operationId = "getTestTypo" },
                    """
                    >> LINKS.TEST.operationId
                    No Operation Found with operationId 'getTestTypo' in the specification
                    """.trimIndent(),
                ),
                Triple(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply { operationRef = "#/paths/~1tests/get" },
                    """
                    >> LINKS.TEST.operationRef
                    Path '/tests' doesn't exist in the specification
                    """.trimIndent(),
                ),
                Triple(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply { operationRef = "#/paths/~1test~1{id}/post" },
                    """
                     >> LINKS.TEST.operationRef
                    Http Method 'POST' doesn't exist on path '/test/{id}'
                    """.trimIndent(),
                ),
                Triple(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                        server = Server().apply {
                            url = "/specmatic/test"
                            variables = ServerVariables().apply {
                                addServerVariable(
                                    "role",
                                    ServerVariable().apply {
                                        default = "Unknown"
                                        enum = listOf("Admin", "User")
                                    },
                                )
                            }
                        }
                    },
                    """
                    >> LINKS.TEST.server.variables.role.default
                    Invalid Server variable 'role'
                    Property `default` in Server variable object cannot be null, must be one of Admin, User
                    """.trimIndent(),
                ),
                Triple(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                        extensions = mapOf("x-StatusCode" to "InvalidStatusCode")
                    },
                    """
                    >> LINKS.TEST.extensions.x-StatusCode
                    Invalid Expected Status Code 'InvalidStatusCode' must be a valid integer or 'default'
                    """.trimIndent(),
                ),
                Triple(
                    first = OpenAPI()
                        .withOperation("/test/{id}", "get", "200", null)
                        .withOperation("/test/{id}", "get", "400", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                        extensions = mapOf("x-StatusCode" to "201")
                    },
                    """
                    >> LINKS.TEST.extensions.x-StatusCode
                    Invalid status Code for x-StatusCode '201' is not possible
                    Must be one of 200, 400
                    """.trimIndent(),
                ),
                Triple(
                    first = OpenAPI().withOperation("/test/{id}", "get", "200", null),
                    second = Link().apply {
                        operationRef = "#/paths/~1test~1{id}/get"
                        parameters = mapOf("id" to "${'$'}response.body#/data/0/product/id")
                        extensions = mapOf("x-Partial" to "ShouldBeABoolean")
                    },
                    """
                    >> LINKS.TEST.extensions.x-Partial
                    Invalid Is-Partial value 'ShouldBeABoolean' must be a valid boolean
                    """.trimIndent(),
                ),
            )
        }

        private fun OpenAPI.withOperation(path: String, method: String, status: String, operationId: String?): OpenAPI {
            val httpMethod = PathItem.HttpMethod.valueOf(method.uppercase())
            return this.apply {
                paths(paths ?: Paths())
                val operation = paths.computeIfAbsent(path) { PathItem() }.readOperationsMap()[httpMethod] ?: Operation()
                paths.getValue(path).apply {
                    operation(httpMethod, operation.apply {
                        operationId(operationId)
                        responses = responses ?: ApiResponses()
                        responses.addApiResponse(status, ApiResponse())
                    })
                }
            }
        }
    }
}
