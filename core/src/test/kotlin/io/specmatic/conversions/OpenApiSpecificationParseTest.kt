package io.specmatic.conversions

import integration_tests.OpenApiVersion
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.IssueSeverity
import io.specmatic.core.NestedQuerySchema
import io.specmatic.core.ObjectQueryRoot
import io.specmatic.core.ObjectQuerySyntax
import io.specmatic.core.QueryParameterCollisionOwnerKind
import io.specmatic.core.QueryArrayIndexStyle
import io.specmatic.core.QueryParameters
import io.specmatic.core.QueryPropertyStyle
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.pattern.AnythingPattern
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.AnyOfPattern
import io.specmatic.core.pattern.BooleanPattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.EnumPattern
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.NullPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLTypeData
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedJsonValue
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.utilities.yamlMapper
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.stub.captureStandardOutput
import io.specmatic.toViolationReportString
import io.specmatic.core.value.toXMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.math.BigDecimal
import java.util.stream.Stream

class OpenApiSpecificationParseTest {
    @Test
    fun `unsupported enum type preserves null string fallback`() {
        val specification = """
            openapi: 3.1.0
            info:
              title: Null enum
              version: "1"
            paths:
              /state:
                get:
                  responses:
                    "200":
                      description: state
                      content:
                        application/json:
                          schema:
                            type: mystery
                            enum: ["null"]
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specification, "").toFeature()
        val scenario = feature.scenarios.single()

        assertThat(scenario.httpResponsePattern.body.matches(StringValue("null"), scenario.resolver))
            .isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.0", "3.1.0"])
    fun `should parse openapi paths with multi parameters per segment using a separator`(openApiVersion: String) {
        val spec = """
            openapi: $openApiVersion
            info:
              title: Composite Path Segment
              version: 1.0.0
            paths:
              /orders/{param1},{param2}/data:
                get:
                  parameters:
                    - in: path
                      name: param1
                      required: true
                      schema:
                        type: integer
                    - in: path
                      name: param2
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val pathPattern = feature.scenarios.single().httpRequestPattern.httpPathPattern
        assertThat(pathPattern?.toInternalPath()).isEqualTo("/orders/(param1:number),(param2:string)/data")

        assertThat(pathPattern?.matches("/orders/123,abc/data", Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pathPattern?.matches("/orders/abc,123/data", Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `single wrapper allOf should preserve wrapped scalar enum and nullability 3_0`() {
        val specFile = File("src/test/resources/openapi/has_single_wrapper_allof/openapi_30.yaml").canonicalFile
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        val statusScenario = feature.scenarios.single { it.httpRequestPattern.httpPathPattern?.toInternalPath() == "/status" }
        val wrappedStatusScenario = feature.scenarios.single { it.httpRequestPattern.httpPathPattern?.toInternalPath() == "/wrappedStatus" }
        val wrappedOptionalStatusScenario = feature.scenarios.single { it.httpRequestPattern.httpPathPattern?.toInternalPath() == "/wrappedOptionalStatus" }

        val statusPattern = resolvedHop(statusScenario.httpResponsePattern.body, statusScenario.resolver)
        assertThat(statusPattern).isInstanceOf(EnumPattern::class.java)
        assertThat(statusPattern).isNotInstanceOf(JSONObjectPattern::class.java)

        val wrappedStatusPattern = resolvedHop(wrappedStatusScenario.httpResponsePattern.body, wrappedStatusScenario.resolver) as JSONObjectPattern
        val wrappedStatusValuePattern = resolvedHop(wrappedStatusPattern.pattern.getValue("status"), wrappedStatusScenario.resolver)
        assertThat(wrappedStatusValuePattern).isInstanceOf(EnumPattern::class.java)
        assertThat(wrappedStatusValuePattern).isNotInstanceOf(JSONObjectPattern::class.java)

        val wrappedOptionalStatusPattern = resolvedHop(wrappedOptionalStatusScenario.httpResponsePattern.body, wrappedOptionalStatusScenario.resolver) as JSONObjectPattern
        val wrappedOptionalStatusValuePattern = resolvedHop(wrappedOptionalStatusPattern.pattern.getValue("status"), wrappedOptionalStatusScenario.resolver)
        assertThat(wrappedOptionalStatusValuePattern).isInstanceOf(AnyPattern::class.java)

        val wrappedOptionalStatusMembers = (wrappedOptionalStatusValuePattern as AnyPattern).pattern
        assertThat(wrappedOptionalStatusMembers).anyMatch { it is NullPattern }
        assertThat(wrappedOptionalStatusMembers).anyMatch { it is DeferredPattern }
    }

    @Test
    fun `single wrapper allOf should preserve wrapped scalar enum and nullability 3_1`() {
        val specFile = File("src/test/resources/openapi/has_single_wrapper_allof/openapi_31.yaml").canonicalFile
        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        val statusScenario = feature.scenarios.single { it.httpRequestPattern.httpPathPattern?.toInternalPath() == "/status" }
        val wrappedStatusScenario = feature.scenarios.single { it.httpRequestPattern.httpPathPattern?.toInternalPath() == "/wrappedStatus" }
        val wrappedOptionalStatusScenario = feature.scenarios.single { it.httpRequestPattern.httpPathPattern?.toInternalPath() == "/wrappedOptionalStatus" }

        val statusPattern = resolvedHop(statusScenario.httpResponsePattern.body, statusScenario.resolver)
        assertThat(statusPattern).isInstanceOf(EnumPattern::class.java)
        assertThat(statusPattern).isNotInstanceOf(JSONObjectPattern::class.java)

        val wrappedStatusPattern = resolvedHop(wrappedStatusScenario.httpResponsePattern.body, wrappedStatusScenario.resolver) as JSONObjectPattern
        val wrappedStatusValuePattern = resolvedHop(wrappedStatusPattern.pattern.getValue("status"), wrappedStatusScenario.resolver)
        assertThat(wrappedStatusValuePattern).isInstanceOf(EnumPattern::class.java)
        assertThat(wrappedStatusValuePattern).isNotInstanceOf(JSONObjectPattern::class.java)

        val wrappedOptionalStatusPattern = resolvedHop(wrappedOptionalStatusScenario.httpResponsePattern.body, wrappedOptionalStatusScenario.resolver) as JSONObjectPattern
        val wrappedOptionalStatusValuePattern = resolvedHop(wrappedOptionalStatusPattern.pattern.getValue("status"), wrappedOptionalStatusScenario.resolver)
        assertThat(wrappedOptionalStatusValuePattern).isInstanceOf(AnyOfPattern::class.java)

        val wrappedOptionalStatusMembers = (wrappedOptionalStatusValuePattern as AnyOfPattern).pattern
        assertThat(wrappedOptionalStatusMembers).anyMatch { it is NullPattern }
        assertThat(wrappedOptionalStatusMembers).anyMatch { it is DeferredPattern }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.0", "3.1.0"])
    fun `should parse and match interpolated openapi path parameters`(openApiVersion: String) {
        val spec = """
            openapi: $openApiVersion
            info:
              title: Interpolated Path Segment
              version: 1.0.0
            paths:
              /product/product-{id}/order/order-{orderId}/latest:
                get:
                  parameters:
                    - in: path
                      name: id
                      required: true
                      schema:
                        type: integer
                    - in: path
                      name: orderId
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val requestPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern
        val pathPattern = requestPattern.httpPathPattern!!

        assertThat(pathPattern.toInternalPath()).isEqualTo("/product/product-(id:number)/order/order-(orderId:string)/latest")
        assertThat(pathPattern.matches("/product/product-12/order/order-abc/latest", Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pathPattern.matches("/product/product-abc/order/abc/latest", Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.0", "3.1.0"])
    fun `should parse path item and operation parameters together for path query and header`(openApiVersion: String) {
        val spec = """
            openapi: $openApiVersion
            info:
              title: Parse merged params
              version: 1.0.0
            paths:
              /orders/{orderId}:
                parameters:
                  - in: query
                    name: includeDetails
                    required: true
                    schema:
                      type: boolean
                  - in: header
                    name: X-Tenant-Id
                    required: true
                    schema:
                      type: string
                get:
                  parameters:
                    - in: path
                      name: orderId
                      required: true
                      schema:
                        type: string
                    - in: query
                      name: page
                      required: true
                      schema:
                        type: integer
                    - in: header
                      name: X-Request-Id
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val requestPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern
        assertThat(requestPattern.httpPathPattern?.toInternalPath()).isEqualTo("/orders/(orderId:string)")

        val queryPatterns = requestPattern.httpQueryParamPattern.queryPatterns
        assertThat(queryPatterns.keys).contains("includeDetails", "page")
        assertThat(queryPatterns["includeDetails"]).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((queryPatterns["includeDetails"] as QueryParameterScalarPattern).pattern).isInstanceOf(BooleanPattern::class.java)
        assertThat(queryPatterns["page"]).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((queryPatterns["page"] as QueryParameterScalarPattern).pattern).isInstanceOf(NumberPattern::class.java)

        val headerPatterns = requestPattern.headersPattern.pattern
        assertThat(headerPatterns.keys).contains("X-Tenant-Id", "X-Request-Id")
        assertThat(headerPatterns["X-Tenant-Id"]).isInstanceOf(StringPattern::class.java)
        assertThat(headerPatterns["X-Request-Id"]).isInstanceOf(StringPattern::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.0", "3.1.0"])
    fun `operation level parameters should override path item parameters with same name and location`(openApiVersion: String) {
        val spec = """
            openapi: $openApiVersion
            info:
              title: Parse override precedence
              version: 1.0.0
            paths:
              /orders/{orderId}:
                parameters:
                  - in: path
                    name: orderId
                    required: true
                    schema:
                      type: string
                  - in: query
                    name: page
                    required: true
                    schema:
                      type: string
                  - in: header
                    name: X-Request-Id
                    required: true
                    schema:
                      type: string
                get:
                  parameters:
                    - in: path
                      name: orderId
                      required: true
                      schema:
                        type: integer
                    - in: query
                      name: page
                      required: true
                      schema:
                        type: integer
                    - in: header
                      name: X-Request-Id
                      required: true
                      schema:
                        type: integer
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val requestPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern
        assertThat(requestPattern.httpPathPattern?.toInternalPath()).contains("(orderId:number)")

        val queryPattern = requestPattern.httpQueryParamPattern.queryPatterns["page"]
        assertThat(queryPattern).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((queryPattern as QueryParameterScalarPattern).pattern).isInstanceOf(NumberPattern::class.java)

        val headerPattern = requestPattern.headersPattern.pattern["X-Request-Id"]
        assertThat(headerPattern).isInstanceOf(NumberPattern::class.java)
    }

    @Test
    fun `scalar only query parameters with unique wire keys should not create collision groups`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Scalar Query Params
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: page
                      required: true
                      schema:
                        type: integer
                    - in: query
                      name: status
                      required: false
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val queryParamPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern
        val queryPatterns = queryParamPattern.queryPatterns

        assertThat(queryPatterns.keys).containsExactlyInAnyOrder("page", "status?")
        assertThat(queryPatterns["page"]).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((queryPatterns["page"] as QueryParameterScalarPattern).pattern).isInstanceOf(NumberPattern::class.java)
        assertThat(queryPatterns["status?"]).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((queryPatterns["status?"] as QueryParameterScalarPattern).pattern).isInstanceOf(StringPattern::class.java)
        assertThat(queryParamPattern.collisionGroupsByWireKey).isEmpty()
        assertThat(
            queryParamPattern.matches(
                HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("page" to "1", "status" to "open"))),
                Resolver()
            )
        ).isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `should parse form exploded object query parameter properties as query params`(explicitSerialization: Boolean) {
        val serialization = if (explicitSerialization) {
            "          style: form\n          explode: true\n"
        } else {
            ""
        }
        val spec = """
            |openapi: 3.0.0
            |info:
            |  title: Form Exploded Object Query Param
            |  version: 1.0.0
            |paths:
            |  /orders:
            |    get:
            |      parameters:
            |        - in: query
            |          name: info
            |          required: true
            |${serialization}          schema:
            |            ${"$"}ref: '#/components/schemas/info'
            |        - in: query
            |          name: type
            |          required: false
            |          schema:
            |            type: string
            |      responses:
            |        '200':
            |          description: OK
            |components:
            |  schemas:
            |    info:
            |      type: object
            |      required:
            |        - name
            |      properties:
            |        name:
            |          type: string
            |        description:
            |          type: string
        """.trimMargin()

        val queryParamPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        assertThat(queryParamPattern.queryPatterns.keys).containsExactlyInAnyOrder("name", "description?", "type?")
        assertThat(queryParamPattern.queryPatterns).doesNotContainKey("info")
        assertThat(queryParamPattern.queryPatterns["name"]).isInstanceOf(QueryParameterScalarPattern::class.java)

        val result = queryParamPattern.matches(
            HttpRequest(
                "GET",
                "/orders",
                queryParams = QueryParameters(listOf("name" to "Jane", "description" to "buyer", "type" to "retail"))
            ),
            Resolver()
        )
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should infer nested object query parameter syntax from inline example`() {
        val queryParamPattern = OpenApiSpecification.fromYAML(
            nestedObjectQueryParamSpec(
                schema = nestedDetailsSchema(),
                parameterFields = mapOf("example" to "name=Jack&address[0].street=Baker Street")
            ),
            ""
        ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        val nestedObjectQueryParam = queryParamPattern.nestedObjectQueryParams.single()

        assertThat(nestedObjectQueryParam.parameterName).isEqualTo("details")
        assertThat(queryParamPattern.queryPatterns.keys).containsExactly("details")
        assertThat(nestedObjectQueryParam.syntax).isEqualTo(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )
    }

    @Test
    fun `should not infer nested object query parameter syntax for scalar-only object query parameter`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Scalar Object Query Param
              version: 1.0.0
            paths:
              /people:
                get:
                  parameters:
                    - in: query
                      name: details
                      required: true
                      schema:
                        type: object
                        properties:
                          name:
                            type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val queryParamPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        assertThat(queryParamPattern.nestedObjectQueryParams).isEmpty()
        assertThat(queryParamPattern.queryPatterns.keys).containsExactly("name?")
    }

    @Test
    fun `should traverse referenced object query property schema when inferring nested syntax`() {
        val queryParamPattern = OpenApiSpecification.fromYAML(
            nestedObjectQueryParamSpec(
                schema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "info" to mapOf("\$ref" to "#/components/schemas/Info")
                    )
                ),
                parameterFields = mapOf("example" to "info.data=abc"),
                componentsSchemas = mapOf("Info" to infoComponentSchema())
            ),
            ""
        ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        val nestedObjectQueryParam = queryParamPattern.nestedObjectQueryParams.single()

        assertThat(nestedObjectQueryParam.syntax).isEqualTo(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )
    }

    @Test
    fun `should traverse referenced array item schema when inferring nested syntax`() {
        val queryParamPattern = OpenApiSpecification.fromYAML(
            nestedObjectQueryParamSpec(
                schema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "items" to mapOf(
                            "type" to "array",
                            "items" to mapOf("\$ref" to "#/components/schemas/Info")
                        )
                    )
                ),
                parameterFields = mapOf("example" to "items[0][data]=abc"),
                componentsSchemas = mapOf("Info" to infoComponentSchema())
            ),
            ""
        ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        val nestedObjectQueryParam = queryParamPattern.nestedObjectQueryParams.single()

        assertThat(nestedObjectQueryParam.syntax).isEqualTo(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
        )
    }

    @Test
    fun `should treat object query property schema with no properties as allowing arbitrary scalar properties`() {
        val queryParamPattern = OpenApiSpecification.fromYAML(
            nestedObjectQueryParamSpec(
                schema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "method" to mapOf("\$ref" to "#/components/schemas/HttpMethod"),
                        "errors" to mapOf(
                            "type" to "array",
                            "items" to mapOf("\$ref" to "#/components/schemas/MicroserviceError")
                        )
                    )
                ),
                parameterFields = mapOf("example" to "method.status=GET&errors[0].code=ERROR"),
                componentsSchemas = mapOf(
                    "HttpMethod" to mapOf("type" to "object"),
                    "MicroserviceError" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("code" to mapOf("type" to "string"))
                    )
                )
            ),
            ""
        ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        val nestedObjectQueryParam = queryParamPattern.nestedObjectQueryParams.single()

        assertThat(nestedObjectQueryParam.syntax).isEqualTo(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
        )
    }

    @Test
    fun `should reject nested query examples that reference pruned circular branches`() {
        val exception = assertThrows<ContractException> {
            OpenApiSpecification.fromYAML(
                nestedObjectQueryParamSpec(
                    schema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "node" to mapOf("\$ref" to "#/components/schemas/Node")
                        )
                    ),
                    parameterFields = mapOf(
                        "required" to false,
                        "example" to "node.child.name=abc"
                    ),
                    componentsSchemas = mapOf(
                        "Node" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "child" to mapOf("\$ref" to "#/components/schemas/Node"),
                                "name" to mapOf("type" to "string")
                            )
                        ),
                    )
                ),
                ""
            ).toFeature()
        }

        assertThat(exception.report()).contains(OpenApiLintViolations.INVALID_NESTED_QUERY_PARAMETER_EXAMPLE.id)
        assertThat(exception.report()).contains("parameters[0].example")
        assertThat(exception.report()).contains("nested query key \"node.child.name\" that could not be parsed")
    }

    @Test
    fun `should prune optional circular nested object query branches without reporting unsupported schema`() {
        val (stdout, queryParamPattern) = captureStandardOutput {
            OpenApiSpecification.fromYAML(
                nestedObjectQueryParamSpec(
                    schema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "node" to mapOf("\$ref" to "#/components/schemas/Node")
                        )
                    ),
                    parameterFields = mapOf("example" to "node.name=abc"),
                    componentsSchemas = mapOf(
                        "Node" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "child" to mapOf("\$ref" to "#/components/schemas/Node"),
                                "name" to mapOf("type" to "string")
                            )
                        )
                    )
                ),
                "recursive-query.yaml"
            ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern
        }

        val nestedObjectQueryParam = queryParamPattern.nestedObjectQueryParams.single()
        val nodeSchema = (nestedObjectQueryParam.schema.properties.getValue("node") as NestedQuerySchema.Object)

        assertThat(stdout).doesNotContain(OpenApiLintViolations.UNSUPPORTED_NESTED_QUERY_PARAMETER_SCHEMA.id)
        assertThat(nodeSchema.properties.keys).containsExactly("name")
    }

    @Test
    fun `should prune unavoidable circular nested object query branches at the cycle boundary`() {
        val (stdout, queryParamPattern) = captureStandardOutput {
            OpenApiSpecification.fromYAML(
                nestedObjectQueryParamSpec(
                    schema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "node" to mapOf("\$ref" to "#/components/schemas/Node")
                        )
                    ),
                    parameterFields = mapOf("example" to "node.name=abc"),
                    componentsSchemas = mapOf(
                        "Node" to mapOf(
                            "type" to "object",
                            "required" to listOf("child"),
                            "properties" to mapOf(
                                "child" to mapOf("\$ref" to "#/components/schemas/Node"),
                                "name" to mapOf("type" to "string")
                            )
                        )
                    )
                ),
                "recursive-query.yaml"
            ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern
        }

        val nestedObjectQueryParam = queryParamPattern.nestedObjectQueryParams.single()
        val nodeSchema = (nestedObjectQueryParam.schema.properties.getValue("node") as NestedQuerySchema.Object)

        assertThat(stdout).doesNotContain(OpenApiLintViolations.UNSUPPORTED_NESTED_QUERY_PARAMETER_SCHEMA.id)
        assertThat(nodeSchema.properties.keys).containsExactly("name")
    }

    @Test
    fun `should report invalid nested object query syntax when inline example is malformed`() {
        val exception = assertThrows<ContractException> {
            OpenApiSpecification.fromYAML(
                nestedObjectQueryParamSpec(
                    schema = nestedDetailsSchema(),
                    parameterFields = mapOf("example" to "name=Jack&address[0][street")
                ),
                ""
            ).toFeature()
        }

        assertThat(exception.report()).contains(OpenApiLintViolations.INVALID_NESTED_QUERY_PARAMETER_EXAMPLE.id)
        assertThat(exception.report()).contains("paths./people.get.parameters[0].example")
    }

    @Test
    fun `should report conflicting nested object query property syntax in inline example`() {
        val exception = assertThrows<ContractException> {
            OpenApiSpecification.fromYAML(
                nestedObjectQueryParamSpec(
                    schema = nestedDetailsSchema(),
                    parameterFields = mapOf("example" to "address[0].street=Baker Street&address[0][city]=London")
                ),
                ""
            ).toFeature()
        }

        assertThat(exception.report()).contains(OpenApiLintViolations.INVALID_NESTED_QUERY_PARAMETER_EXAMPLE.id)
        assertThat(exception.report()).contains("paths./people.get.parameters[0].example")
        assertThat(exception.report()).contains("Examples use conflicting property serialization styles for nested query parameters")
        assertThat(exception.report()).contains("\"address[0].street\" uses dot property notation")
        assertThat(exception.report()).contains("\"address[0][city]\" uses bracket property notation")
    }

    @Test
    fun `should scan examples until one demonstrates nested object query syntax during conversion`() {
        val queryParamPattern = OpenApiSpecification.fromYAML(
            nestedObjectQueryParamSpec(
                schema = nestedDetailsSchema(),
                parameterFields = mapOf(
                    "examples" to mapOf(
                        "first" to mapOf("value" to "name=Jack"),
                        "second" to mapOf("value" to "name=Jill&address[0][street]=Baker Street")
                    )
                )
            ),
            ""
        ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        val nestedObjectQueryParam = queryParamPattern.nestedObjectQueryParams.single()

        assertThat(nestedObjectQueryParam.syntax).isEqualTo(
            ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Bracket, QueryArrayIndexStyle.Bracket)
        )
    }

    @Test
    fun `should require required object properties only when optional form exploded object query param is present`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Optional Form Exploded Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: false
                      schema:
                        type: object
                        required:
                          - name
                        properties:
                          name:
                            type: string
                          description:
                            type: string
                    - in: query
                      name: type
                      required: false
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val queryParamPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        val withoutInfo = queryParamPattern.matches(
            HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("type" to "retail"))),
            Resolver()
        )
        assertThat(withoutInfo).isInstanceOf(Result.Success::class.java)

        val partialInfo = queryParamPattern.matches(
            HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("description" to "buyer"))),
            Resolver()
        )
        assertThat(partialInfo).isInstanceOf(Result.Failure::class.java)
        assertThat(partialInfo.reportString()).contains("name")

        val completeInfo = queryParamPattern.matches(
            HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("name" to "Jane"))),
            Resolver()
        )
        assertThat(completeInfo).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `required form exploded object query param with no required properties treats all object properties as optional`() {
        val queryParamPattern = OpenApiSpecification.fromYAML(requiredObjectQueryParamWithNoRequiredPropertiesSpec(), "")
            .toFeature()
            .scenarios
            .single()
            .httpRequestPattern
            .httpQueryParamPattern

        assertThat(queryParamPattern.queryPatterns.keys).containsExactlyInAnyOrder("name?", "description?")

        assertThat(queryParamPattern.matches(HttpRequest("GET", "/orders"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(
            queryParamPattern.matches(
                HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("description" to "buyer"))),
                Resolver()
            )
        ).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `required form exploded object query param with no required properties should warn`() {
        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(requiredObjectQueryParamWithNoRequiredPropertiesSpec(), "spec.yaml").toFeature()
        }

        assertThat(stdout).containsIgnoringWhitespaces(
            toViolationReportString(
                breadCrumb = "paths./orders.get.parameters[0].required",
                details = "Query parameter info is a required form-exploded object, but its schema does not define any required properties. Since form-exploded object parameters are represented by their properties, no property is made mandatory.",
                OpenApiLintViolations.REQUIRED_QUERY_OBJECT_CONFLICT
            )
        )
    }

    @Test
    fun `same type collision between standalone query parameter and form exploded object property should be accepted`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - type
                        properties:
                          type:
                            type: integer
                    - in: query
                      name: type
                      required: false
                      schema:
                        type: integer
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val queryPatterns = queryParamPattern.queryPatterns
        val typePattern = queryPatterns["type?"]
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("type")

        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("info.type", "type")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("type")
        assertThat(collisionGroup.authoritativeOwner.kind).isEqualTo(QueryParameterCollisionOwnerKind.ScalarParameter)
        assertThat(queryPatterns.keys).containsExactly("type?")
        assertThat(typePattern).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((typePattern as QueryParameterScalarPattern).pattern).isInstanceOf(NumberPattern::class.java)
    }

    @Test
    fun `collision detection should resolve top level scalar query parameter refs before reading names`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Referenced Scalar Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - age
                        properties:
                          age:
                            type: integer
                    - ${"$"}ref: '#/components/parameters/AgeQueryParam'
                  responses:
                    '200':
                      description: OK
            components:
              parameters:
                AgeQueryParam:
                  in: query
                  name: age
                  required: false
                  schema:
                    type: integer
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("age")

        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("info.age", "age")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("age")
    }

    @Test
    fun `required query object conflict lint violation should be classified as a warning`() {
        assertThat(OpenApiLintViolations.REQUIRED_QUERY_OBJECT_CONFLICT.severity).isEqualTo(IssueSeverity.WARNING)
    }

    @Test
    fun `query parameter type collision lint violation should be classified as a warning`() {
        assertThat(OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION.severity).isEqualTo(IssueSeverity.WARNING)
    }

    @Test
    fun `different type collision between standalone query parameter and form exploded object property should warn in strict parsing`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - type
                        properties:
                          type:
                            type: integer
                    - in: query
                      name: type
                      required: false
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val (stdout, feature) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern

        assertThat(stdout).contains(OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION.id)
        assertThat(stdout).doesNotContain(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION.id)
        assertThat(stdout).contains("type")
        assertThat(stdout).contains("info.type")
        assertThat(stdout).contains("last declared query parameter type")
        assertThat(stdout).doesNotContain("if parsing continues")
        assertThat(queryParamPattern.collisionGroupsByWireKey.getValue("type").authoritativeOwner.sourceName).isEqualTo("type")
    }

    @Test
    fun `different type collision diagnostic should include every owner location and authoritative owner`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - type
                        properties:
                          type:
                            type: integer
                    - in: query
                      name: filter
                      required: true
                      schema:
                        type: object
                        required:
                          - type
                        properties:
                          type:
                            type: boolean
                    - in: query
                      name: type
                      required: false
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val (stdout, feature) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern

        assertThat(stdout).contains(OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION.id)
        assertThat(stdout).doesNotContain(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION.id)
        assertThat(stdout).contains("wire key type")
        assertThat(stdout).contains("- info.type at paths./orders.get.parameters[0].schema.properties.type")
        assertThat(stdout).contains("- filter.type at paths./orders.get.parameters[1].schema.properties.type")
        assertThat(stdout).contains("- type at paths./orders.get.parameters[2].name")
        assertThat(stdout).doesNotContain("/paths/~1orders/get/parameters")
        assertThat(stdout).contains("last declared query parameter type as authoritative")
        assertThat(stdout).doesNotContain("if parsing continues")
        assertThat(queryParamPattern.collisionGroupsByWireKey.getValue("type").owners.map { it.sourceName }).containsExactly("info.type", "filter.type", "type")
    }

    @Test
    fun `different type collision should select standalone scalar as authoritative when declared last in lenient parsing`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - type
                        properties:
                          type:
                            type: integer
                    - in: query
                      name: type
                      required: false
                      schema:
                        type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val (feature, result) = OpenApiSpecification.fromYAML(spec, "").toFeatureLenient()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val typePattern = queryParamPattern.queryPatterns["type?"]
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("type")

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.toIssues().single().severity).isEqualTo(IssueSeverity.WARNING)
        assertThat(result.reportString()).contains(OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION.id)
        assertThat(result.reportString()).doesNotContain(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION.id)
        assertThat(result.reportString()).contains("type")
        assertThat(result.reportString()).contains("info.type")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("type")
        assertThat(collisionGroup.authoritativeOwner.kind).isEqualTo(QueryParameterCollisionOwnerKind.ScalarParameter)
        assertThat(typePattern).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((typePattern as QueryParameterScalarPattern).pattern).isInstanceOf(StringPattern::class.java)
    }

    @Test
    fun `last declared scalar collision owner should not require object query property`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Required Object Property Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - status
                        properties:
                          status:
                            type: integer
                    - in: query
                      name: status
                      required: false
                      schema:
                        type: boolean
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val result = queryParamPattern.matches(
            HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("status" to "866"))),
            Resolver()
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).reportString()).contains("Expected type boolean")
        assertThat(result.reportString()).doesNotContain("mandatory property \"status\"")
    }

    @Test
    fun `last declared scalar collision owner should not require object query property when matching stub examples`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Required Object Property Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - status
                        properties:
                          status:
                            type: integer
                    - in: query
                      name: status
                      required: false
                      schema:
                        type: boolean
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val exception = assertThrows<NoMatchingScenario> {
            feature.matchingStub(
                HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("status" to "866"))),
                HttpResponse.OK
            )
        }

        assertThat(exception.message).contains("Expected type boolean")
        assertThat(exception.message).doesNotContain("mandatory property \"status\"")
    }

    @Test
    fun `last declared scalar collision owner should not require unwrapped nested object query property`() {
        val spec = nestedStatusCollisionSpec(scalarDeclaredLast = true)

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("status")
        val result = queryParamPattern.matches(
            HttpRequest(
                "GET",
                "/orders",
                queryParams = QueryParameters(listOf("name" to "Jack", "address.street" to "Baker", "status" to "866"))
            ),
            Resolver()
        )

        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("details.status", "status")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("status")
        assertThat(collisionGroup.authoritativeOwner.kind).isEqualTo(QueryParameterCollisionOwnerKind.ScalarParameter)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).reportString()).contains("Expected type boolean")
        assertThat(result.reportString()).doesNotContain("mandatory property \"status\"")
    }

    @Test
    fun `last declared unwrapped nested object query property should take precedence over scalar query parameter`() {
        val spec = nestedStatusCollisionSpec(scalarDeclaredLast = false)

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("status")

        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("status", "details.status")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("details.status")
        assertThat(collisionGroup.authoritativeOwner.kind).isEqualTo(QueryParameterCollisionOwnerKind.NestedObjectProperty)
        assertThat(
            queryParamPattern.matches(
                HttpRequest(
                    "GET",
                    "/orders",
                    queryParams = QueryParameters(listOf("name" to "Jack", "address.street" to "Baker", "status" to "866"))
                ),
                Resolver()
            )
        ).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `different type collision should select object property as authoritative when declared last in lenient parsing`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: type
                      required: false
                      schema:
                        type: string
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        required:
                          - type
                        properties:
                          type:
                            type: integer
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val (feature, result) = OpenApiSpecification.fromYAML(spec, "").toFeatureLenient()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val typePattern = queryParamPattern.queryPatterns["type"]
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("type")

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains(OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION.id)
        assertThat(result.reportString()).doesNotContain(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION.id)
        assertThat(result.reportString()).contains("- type at paths./orders.get.parameters[0].name")
        assertThat(result.reportString()).contains("- info.type at paths./orders.get.parameters[1].schema.properties.type")
        assertThat(result.reportString()).contains("last declared query parameter info.type as authoritative")
        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("type", "info.type")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("info.type")
        assertThat(collisionGroup.authoritativeOwner.kind).isEqualTo(QueryParameterCollisionOwnerKind.FormExplodedObjectProperty)
        assertThat(typePattern).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((typePattern as QueryParameterScalarPattern).pattern).isInstanceOf(NumberPattern::class.java)
        assertThat(queryParamPattern.matches(HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("type" to "10"))), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `same type collision across multiple form exploded object query params should resolve refs before comparing`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Params
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        ${"$"}ref: '#/components/schemas/InfoParams'
                    - in: query
                      name: filters
                      required: true
                      schema:
                        ${"$"}ref: '#/components/schemas/FilterParams'
                  responses:
                    '200':
                      description: OK
            components:
              schemas:
                InfoParams:
                  type: object
                  required:
                    - age
                  properties:
                    age:
                      ${"$"}ref: '#/components/schemas/Age'
                FilterParams:
                  type: object
                  required:
                    - age
                  properties:
                    age:
                      type: integer
                Age:
                  type: integer
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()
        val queryParamPattern = feature.scenarios.single().httpRequestPattern.httpQueryParamPattern
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("age")

        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("info.age", "filters.age")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("filters.age")
    }

    @Test
    fun `different type collision across two form exploded object query params should select last object property at parse time`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Params
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        ${"$"}ref: '#/components/schemas/InfoParams'
                    - in: query
                      name: filters
                      required: true
                      schema:
                        ${"$"}ref: '#/components/schemas/FilterParams'
                  responses:
                    '200':
                      description: OK
            components:
              schemas:
                InfoParams:
                  type: object
                  required:
                    - age
                  properties:
                    age:
                      type: integer
                FilterParams:
                  type: object
                  required:
                    - age
                  properties:
                    age:
                      type: boolean
        """.trimIndent()

        val (stdout, feature) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }
        val scenario = feature.scenarios.single()
        val queryParamPattern = scenario.httpRequestPattern.httpQueryParamPattern
        val agePattern = queryParamPattern.queryPatterns["age"]
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("age")

        assertThat(stdout).contains(OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION.id)
        assertThat(stdout).contains("- info.age at components.schemas.InfoParams.properties.age")
        assertThat(stdout).contains("- filters.age at components.schemas.FilterParams.properties.age")
        assertThat(stdout).contains("last declared query parameter filters.age as authoritative")
        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("info.age", "filters.age")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("filters.age")
        assertThat(collisionGroup.authoritativeOwner.kind).isEqualTo(QueryParameterCollisionOwnerKind.FormExplodedObjectProperty)
        assertThat(agePattern).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat(resolvedHop((agePattern as QueryParameterScalarPattern).pattern, scenario.resolver)).isInstanceOf(BooleanPattern::class.java)
        assertThat(queryParamPattern.matches(HttpRequest("GET", "/orders", queryParams = QueryParameters(listOf("age" to "true"))), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `different type collision across three query parameter owners should preserve owner order`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Colliding Form Exploded Object Query Params
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        ${"$"}ref: '#/components/schemas/InfoParams'
                    - in: query
                      name: filters
                      required: true
                      schema:
                        ${"$"}ref: '#/components/schemas/FilterParams'
                    - in: query
                      name: age
                      required: false
                      schema:
                        type: boolean
                  responses:
                    '200':
                      description: OK
            components:
              schemas:
                InfoParams:
                  type: object
                  required:
                    - age
                  properties:
                    age:
                      ${"$"}ref: '#/components/schemas/Age'
                FilterParams:
                  type: object
                  required:
                    - age
                  properties:
                    age:
                      type: string
                Age:
                  type: integer
        """.trimIndent()

        val (feature, result) = OpenApiSpecification.fromYAML(spec, "").toFeatureLenient()
        val scenario = feature.scenarios.single()
        val queryParamPattern = scenario.httpRequestPattern.httpQueryParamPattern
        val agePattern = queryParamPattern.queryPatterns["age?"]
        val collisionGroup = queryParamPattern.collisionGroupsByWireKey.getValue("age")

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains(OpenApiLintViolations.QUERY_PARAMETER_TYPE_COLLISION.id)
        assertThat(result.reportString()).doesNotContain(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION.id)
        assertThat(result.reportString()).contains("- info.age at components.schemas.InfoParams.properties.age")
        assertThat(result.reportString()).contains("- filters.age at components.schemas.FilterParams.properties.age")
        assertThat(result.reportString()).contains("- age at paths./orders.get.parameters[2].name")
        assertThat(result.reportString()).doesNotContain("/paths/~1orders/get/parameters")
        assertThat(collisionGroup.owners.map { it.sourceName }).containsExactly("info.age", "filters.age", "age")
        assertThat(collisionGroup.authoritativeOwner.sourceName).isEqualTo("age")
        assertThat(agePattern).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat(resolvedHop((agePattern as QueryParameterScalarPattern).pattern, scenario.resolver)).isInstanceOf(BooleanPattern::class.java)
    }

    @ParameterizedTest(name = "openapi {0}")
    @ValueSource(strings = ["3.0.3", "3.1.0"])
    fun `should resolve path item parameter referenced from components`(openApiVersion: String) {
        val spec = $$"""
            openapi: $$openApiVersion
            info:
              title: Parse refed path param in components
              version: 1.0.0
            paths:
              /orders/{orderId}:
                parameters:
                  - $ref: '#/components/parameters/OrderIdPathParam'
                get:
                  responses:
                    '200':
                      description: OK
            components:
              parameters:
                OrderIdPathParam:
                  name: orderId
                  in: path
                  required: true
                  schema:
                    type: string
        """.trimIndent()

        val requestPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern
        assertThat(requestPattern.httpPathPattern?.toInternalPath()).isEqualTo("/orders/(orderId:string)")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.0", "3.1.0"])
    fun `should not collapse ref-only parameter lists while deduplicating`(openApiVersion: String) {
        val spec = $$"""
            openapi: $$openApiVersion
            info:
              title: Parse ref-only dedup params
              version: 1.0.0
            paths:
              /orders/{orderId}:
                parameters:
                  - $ref: '#/components/parameters/OrderIdPathParam'
                get:
                  parameters:
                    - $ref: '#/components/parameters/IncludeDetailsQueryParam'
                    - $ref: '#/components/parameters/TenantHeaderParam'
                  responses:
                    '200':
                      description: OK
            components:
              parameters:
                OrderIdPathParam:
                  name: orderId
                  in: path
                  required: true
                  schema:
                    type: string
                IncludeDetailsQueryParam:
                  name: includeDetails
                  in: query
                  required: true
                  schema:
                    type: boolean
                TenantHeaderParam:
                  name: X-Tenant-Id
                  in: header
                  required: true
                  schema:
                    type: string
        """.trimIndent()

        val requestPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern

        assertThat(requestPattern.httpPathPattern?.toInternalPath()).isEqualTo("/orders/(orderId:string)")
        assertThat(requestPattern.httpQueryParamPattern.queryPatterns).containsKey("includeDetails")
        assertThat(requestPattern.headersPattern.pattern).containsKey("X-Tenant-Id")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.0", "3.1.0"])
    fun `operation level ref parameter should override path item inline parameter with same name and location`(openApiVersion: String) {
        val spec = $$"""
            openapi: $$openApiVersion
            info:
              title: Parse mixed override precedence
              version: 1.0.0
            paths:
              /orders/{orderId}:
                parameters:
                  - in: path
                    name: orderId
                    required: true
                    schema:
                      type: string
                get:
                  parameters:
                    - $ref: '#/components/parameters/OrderIdPathParam'
                  responses:
                    '200':
                      description: OK
            components:
              parameters:
                OrderIdPathParam:
                  name: orderId
                  in: path
                  required: true
                  schema:
                    type: integer
        """.trimIndent()

        val requestPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern
        assertThat(requestPattern.httpPathPattern?.toInternalPath()).contains("(orderId:number)")
    }

    @Test
    fun `should be able to parse primitives inside XML pattern schemas with their constraints intact`() {
        val specFile = File("src/test/resources/openapi/has_xml_payloads/api.yaml")
        val specification = OpenApiSpecification.fromYAML(specFile.readText(), specFile.canonicalPath)
        val create201Scenario = specification.toFeature().scenarios.first()

        val requestPattern = resolvedHop(DeferredPattern("(InventoryCreateRequest)"), create201Scenario.resolver)
        assertThat(requestPattern).isInstanceOf(XMLPattern::class.java); requestPattern as XMLPattern

        val requestInventory = requestPattern.pattern.nodes.firstNotNullOf { (it.pattern as? XMLTypeData)?.takeIf { it.name == "inventory" } }
        assertThat(requestInventory.nodes.single()).isInstanceOf(NumberPattern::class.java)

        val reqNumberPattern = requestInventory.nodes.single() as NumberPattern
        assertThat(reqNumberPattern.minimum).isEqualTo(BigDecimal(1))
        assertThat(reqNumberPattern.maximum).isEqualTo(BigDecimal(101))

        val responsePattern = resolvedHop(DeferredPattern("(Inventory)"), create201Scenario.resolver)
        assertThat(responsePattern).isInstanceOf(XMLPattern::class.java); responsePattern as XMLPattern

        val responseInventory = requestPattern.pattern.nodes.firstNotNullOf { (it.pattern as? XMLTypeData)?.takeIf { it.name == "inventory" } }
        assertThat(responseInventory.nodes.single()).isInstanceOf(NumberPattern::class.java)

        val resNumberPattern = responseInventory.nodes.single() as NumberPattern
        assertThat(resNumberPattern.minimum).isEqualTo(BigDecimal(1))
        assertThat(resNumberPattern.maximum).isEqualTo(BigDecimal(101))
    }

    @Test
    fun `XML optional attributes use canonical keys and retain source pointers`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: XML attributes
              version: 1.0.0
            paths:
              /users:
                post:
                  requestBody:
                    content:
                      application/xml:
                        schema:
                          ${'$'}ref: '#/components/schemas/User'
                  responses:
                    '200':
                      description: OK
            components:
              schemas:
                User:
                  type: object
                  xml:
                    name: user
                  properties:
                    id:
                      type: string
                      xml:
                        attribute: true
                    nickname:
                      type: integer
                      xml:
                        attribute: true
                  required:
                    - id
        """.trimIndent()

        val scenario = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single()
        val requestPattern = resolvedHop(scenario.httpRequestPattern.body, scenario.resolver) as XMLPattern
        val userPattern = resolvedHop(DeferredPattern("(User)"), scenario.resolver) as XMLPattern

        assertThat(userPattern.pattern.attributes).containsKeys("id", "nickname?")
        assertThat(requestPattern.attributePointers)
            .containsEntry("nickname?", "/components/schemas/User/properties/nickname")
        assertThat(requestPattern.matches(toXMLNode("""<user id="1"/>"""), scenario.resolver))
            .isInstanceOf(Result.Success::class.java)
        assertThat(requestPattern.matches(toXMLNode("""<user id="1" nickname="2"/>"""), scenario.resolver))
            .isInstanceOf(Result.Success::class.java)
        assertThat(requestPattern.matches(toXMLNode("""<user id="1" nickname="invalid"/>"""), scenario.resolver))
            .isInstanceOf(Result.Failure::class.java)
    }

    @ParameterizedTest
    @MethodSource("openApiVersionsProviders")
    fun `should fail an openapi specification where enum values do not match the specified type`(openApiVersion: OpenApiVersion) {
        val openApiSpecContent = """
        openapi: ${openApiVersion.value}
        components:
          schemas:
            EnumPattern:
              type: integer
              enum:
                - 1
                - ABC
                - 3
        """.trimIndent()
        val exception = assertThrows<ContractException> {
            OpenApiSpecification.fromYAML(openApiSpecContent, "TEST").parseUnreferencedSchemas()
        }

        if (openApiVersion == OpenApiVersion.OAS30) {
            assertThat(exception.report()).isEqualToIgnoringWhitespace("""
            >> components.schemas.EnumPattern.enum
            Failed to parse enum. One or more enum values were parsed as null
            This often happens in OpenAPI 3.0.x when enum values have mixed or invalid types and the parser implicitly coerces those values to null
            Please check the enum schema and entries or mark then schema as nullable if this was intentional

            >> components.schemas.EnumPattern.enum[1]
            Enum values cannot contain null if the enum is not nullable, ignoring null value
            """.trimIndent())
        } else {
            assertThat(exception.report()).isEqualToIgnoringWhitespace("""
            >> components.schemas.EnumPattern.enum[1]
            Enum value "ABC" does not match the declared enum schema, ignoring this value
            """.trimIndent())
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("additionalPropertiesTestCases")
    fun `query param with various additionalProperties configurations`(case: AdditionalPropsCase) {
        val yamlContentMap = buildMap<String, Any?> {
            put("openapi", case.version.value)
            put("info", mapOf("title" to "Test API", "version" to "1.0.0"))
            put(
                "paths", mapOf(
                    "/test" to mapOf(
                        "get" to mapOf(
                            "parameters" to listOf(
                                mapOf(
                                    "name" to "parameterizedParam",
                                    "in" to "query",
                                    "schema" to mapOf("type" to "object", "additionalProperties" to case.additionalProperties)
                                ),
                                mapOf(
                                    "name" to "testParam",
                                    "in" to "query",
                                    "schema" to mapOf("type" to "integer")
                                ),
                            ),
                            "responses" to mapOf("200" to mapOf("description" to "OK"))
                        )
                    )
                )
            )
            put("components", mapOf("schemas" to mapOf("EmailPattern" to mapOf("type" to "string", "format" to "email"))))
        }

        val feature = OpenApiSpecification.fromYAML(yamlMapper.writeValueAsString(yamlContentMap), "test-api.yaml").toFeature()
        val queryParameters = feature.scenarios.first().httpRequestPattern.httpQueryParamPattern
        val paramPattern = queryParameters.queryPatterns["testParam?"]

        assertThat(paramPattern).isInstanceOf(QueryParameterScalarPattern::class.java); paramPattern  as QueryParameterScalarPattern
        assertThat(paramPattern.pattern).isInstanceOf(NumberPattern::class.java)
        case.check(queryParameters.additionalProperties)
    }

    @Test
    fun `form exploded object query params allow any additional query param with free form additional properties`() {
        val queryParamPattern = queryParamPatternWithObjectAdditionalProperties(
            personAdditionalProperties = true
        )

        val result = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "not-a-boolean"))),
            Resolver()
        )

        assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `form exploded object query params allow any additional query param when any object allows free form additional properties`() {
        val queryParamPattern = queryParamPatternWithObjectAdditionalProperties(
            personAdditionalProperties = mapOf("type" to "boolean"),
            departmentAdditionalProperties = true
        )

        val result = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "not-a-boolean"))),
            Resolver()
        )

        assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `form exploded object query params restrict additional query params to a single schema`() {
        val queryParamPattern = queryParamPatternWithObjectAdditionalProperties(
            personAdditionalProperties = mapOf("type" to "boolean")
        )

        val validResult = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "true"))),
            Resolver()
        )
        val invalidResult = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "not-a-boolean"))),
            Resolver()
        )

        assertThat(validResult).withFailMessage(validResult.reportString()).isInstanceOf(Result.Success::class.java)
        assertThat(invalidResult).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `form exploded object query params allow additional query params matching any constrained schema`() {
        val queryParamPattern = queryParamPatternWithObjectAdditionalProperties(
            personAdditionalProperties = mapOf("type" to "boolean"),
            departmentAdditionalProperties = mapOf("type" to "integer")
        )

        val booleanResult = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "true"))),
            Resolver()
        )
        val integerResult = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "10"))),
            Resolver()
        )
        val invalidResult = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "not-a-boolean-or-integer"))),
            Resolver()
        )

        assertThat(booleanResult).withFailMessage(booleanResult.reportString()).isInstanceOf(Result.Success::class.java)
        assertThat(integerResult).withFailMessage(integerResult.reportString()).isInstanceOf(Result.Success::class.java)
        assertThat(invalidResult).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `form exploded object query param should use additionalProperties from referenced object schema`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Referenced Object Query Param
              version: 1.0.0
            paths:
              /test:
                get:
                  parameters:
                    - in: query
                      name: info
                      schema:
                        ${"$"}ref: '#/components/schemas/Info'
                  responses:
                    '200':
                      description: OK
            components:
              schemas:
                Info:
                  type: object
                  properties:
                    name:
                      type: string
                  additionalProperties:
                    type: boolean
        """.trimIndent()

        val queryParamPattern = OpenApiSpecification.fromYAML(spec, "").toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

        val validResult = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "true"))),
            Resolver()
        )
        val invalidResult = queryParamPattern.matches(
            HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("extra" to "not-a-boolean"))),
            Resolver()
        )

        assertThat(validResult).withFailMessage(validResult.reportString()).isInstanceOf(Result.Success::class.java)
        assertThat(invalidResult).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should guard against the swagger-parser regression for discriminator mappings to external schema files with nested refs`() {
        val specFile = File("src/test/resources/openapi/discriminator_external_file_refs/openapi.yaml")

        val feature = OpenApiSpecification.fromFile(specFile.canonicalPath).toFeature()

        assertThat(feature.scenarios).hasSize(1)
    }

    @Test
    fun `internal ref source locations keep their own pointer`(@TempDir tempDir: File) {
        val apiFile = tempDir.resolve("api.yaml")
        apiFile.writeText(loadFixture("jsonPointerSourceMap/baseSpecWithComposition.yaml"))

        val feature = OpenApiSpecification.fromFile(apiFile.canonicalPath).toFeature()
        val sourceLocations = feature.scenarios.single().resolver.sourceLocations

        val componentPointer = "/components/schemas/Submission/properties/id/type"
        val componentLocation = sourceLocations.getValue(componentPointer)

        assertThat(componentLocation.filePath).isEqualTo(apiFile.canonicalPath.replace('\\', '/'))
        assertThat(componentLocation.pointer).isEqualTo(componentPointer)
    }

    @Test
    fun `external ref source locations keep original source pointer`(@TempDir tempDir: File) {
        val apiFile = tempDir.resolve("api.yaml").canonicalFile
        val commonFile = tempDir.resolve("common.yaml").canonicalFile

        apiFile.writeText($$"""
        openapi: 3.0.0
        info:
          title: Pets
          version: "1"
        paths:
          /pets:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        $ref: './common.yaml#/Pet'
        """.trimIndent())

        commonFile.writeText("""
        Pet:
          properties:
            id:
              type: string
        """.trimIndent())

        val feature = OpenApiSpecification.fromFile(apiFile.canonicalPath).toFeature()
        val sourceLocations = feature.scenarios.single().resolver.sourceLocations
        assertThat(sourceLocations.values).allSatisfy {
            assertThat(it).satisfiesAnyOf(
                { location ->
                    assertThat(location.filePath).isEqualTo(apiFile.invariantSeparatorsPath)
                    assertThat(location.pointer).doesNotStartWith("/Pet")
                },
                { location ->
                    assertThat(location.filePath).isEqualTo(commonFile.invariantSeparatorsPath)
                    assertThat(location.pointer).startsWith("/Pet")
                }
            )
        }
    }

    @Test
    fun `pure single schema allOf preserves wrapper component pointer and inner schema annotations`() {
        val spec = $$"""
        openapi: 3.0.1
        info:
          title: Wrapper allOf
          version: 1.0.0
        components:
          schemas:
            Status:
              type: string
            WrappedInlineObject:
              allOf:
                - type: object
                  properties:
                    status:
                      type: string
            WrappedObjectWithRefProperty:
              allOf:
                - type: object
                  properties:
                    status:
                      $ref: "#/components/schemas/Status"
            WrappedOptionalStatus:
              type: object
              required:
                - status
              properties:
                status:
                  nullable: true
                  allOf:
                    - $ref: "#/components/schemas/Status"
        """.trimIndent()

        val specification = OpenApiSpecification.fromYAML(yamlContent = spec, openApiFilePath = "")
        val patterns = specification.parseUnreferencedSchemas()
        val resolver = Resolver(newPatterns = specification.patterns)

        val inlineObjectPattern = patterns.getValue("(WrappedInlineObject)") as JSONObjectPattern
        assertThat(inlineObjectPattern.schemaPointer).isEqualTo("/components/schemas/WrappedInlineObject")
        assertThat(inlineObjectPattern.propertyPointers["status"]).isEqualTo("/components/schemas/WrappedInlineObject/allOf/0/properties/status")

        val objectWithRefPropertyPattern = resolvedHop(patterns.getValue("(WrappedObjectWithRefProperty)"), resolver) as JSONObjectPattern
        assertThat(objectWithRefPropertyPattern.schemaPointer).isEqualTo("/components/schemas/WrappedObjectWithRefProperty")
        assertThat(objectWithRefPropertyPattern.propertyPointers["status"]).isEqualTo("/components/schemas/WrappedObjectWithRefProperty/allOf/0/properties/status")

        val optionalStatusPattern = resolvedHop(patterns.getValue("(WrappedOptionalStatus)"), resolver) as JSONObjectPattern
        assertThat(optionalStatusPattern.schemaPointer).isEqualTo("/components/schemas/WrappedOptionalStatus")
        assertThat(optionalStatusPattern.propertyPointers["status"]).isEqualTo("/components/schemas/WrappedOptionalStatus/properties/status")
    }

    private fun queryParamPatternWithObjectAdditionalProperties(
        personAdditionalProperties: Any?,
        departmentAdditionalProperties: Any? = null
    ) = OpenApiSpecification.fromYAML(
        yamlMapper.writeValueAsString(
            mapOf(
                "openapi" to "3.0.0",
                "info" to mapOf("title" to "Test API", "version" to "1.0.0"),
                "paths" to mapOf(
                    "/test" to mapOf(
                        "get" to mapOf(
                            "parameters" to listOf(
                                mapOf(
                                    "name" to "person_info",
                                    "in" to "query",
                                    "schema" to objectSchema("name", personAdditionalProperties)
                                ),
                                mapOf(
                                    "name" to "department_info",
                                    "in" to "query",
                                    "schema" to objectSchema("department", departmentAdditionalProperties)
                                )
                            ),
                            "responses" to mapOf("200" to mapOf("description" to "OK"))
                        )
                    )
                )
            )
        ),
        "test-api.yaml"
    ).toFeature().scenarios.single().httpRequestPattern.httpQueryParamPattern

    private fun nestedStatusCollisionSpec(scalarDeclaredLast: Boolean): String {
        val scalarStatusParam = """
                    - in: query
                      name: status
                      required: false
                      schema:
                        type: boolean
        """.trimIndent()
        val nestedDetailsParam = """
                    - in: query
                      name: details
                      required: true
                      schema:
                        type: object
                        required:
                          - status
                        properties:
                          status:
                            type: integer
                          name:
                            type: string
                          address:
                            type: object
                            properties:
                              street:
                                type: string
                      example: status=10&name=Jack&address.street=Baker
        """.trimIndent()
        val parameters = if (scalarDeclaredLast) {
            listOf(nestedDetailsParam, scalarStatusParam)
        } else {
            listOf(scalarStatusParam, nestedDetailsParam)
        }.joinToString("\n") { it.prependIndent("                    ") }

        return """
            openapi: 3.0.0
            info:
              title: Colliding Nested Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
$parameters
                  responses:
                    '200':
                      description: OK
        """.trimIndent()
    }

    private fun nestedObjectQueryParamSpec(
        schema: Map<String, Any?>,
        parameterFields: Map<String, Any?>,
        componentsSchemas: Map<String, Any?> = emptyMap()
    ): String {
        val parameter = mapOf(
            "in" to "query",
            "name" to "details",
            "required" to true,
            "schema" to schema
        ) + parameterFields

        return yamlMapper.writeValueAsString(
            buildMap {
                put("openapi", "3.0.0")
                put("info", mapOf("title" to "Nested Object Query Param", "version" to "1.0.0"))
                put(
                    "paths",
                    mapOf(
                        "/people" to mapOf(
                            "get" to mapOf(
                                "parameters" to listOf(parameter),
                                "responses" to mapOf("200" to mapOf("description" to "OK"))
                            )
                        )
                    )
                )

                if (componentsSchemas.isNotEmpty()) {
                    put("components", mapOf("schemas" to componentsSchemas))
                }
            }
        )
    }

    private fun nestedDetailsSchema(): Map<String, Any?> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string"),
                "address" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "street" to mapOf("type" to "string"),
                            "city" to mapOf("type" to "string")
                        )
                    )
                )
            )
        )
    }

    private fun infoComponentSchema(): Map<String, Any?> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "data" to mapOf("type" to "string")
            )
        )
    }

    private fun objectSchema(propertyName: String, additionalProperties: Any?): Map<String, Any?> {
        return buildMap {
            put("type", "object")
            put("properties", mapOf(propertyName to mapOf("type" to "string")))
            if (additionalProperties != null) put("additionalProperties", additionalProperties)
        }
    }

    private fun requiredObjectQueryParamWithNoRequiredPropertiesSpec(): String {
        return """
            openapi: 3.0.0
            info:
              title: Required Form Exploded Object Query Param
              version: 1.0.0
            paths:
              /orders:
                get:
                  parameters:
                    - in: query
                      name: info
                      required: true
                      schema:
                        type: object
                        properties:
                          name:
                            type: string
                          description:
                            type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()
    }


    @Nested
    inner class InlineExampleSourceMetadata {
        @Test
        fun `retains logical request and response variant pointers`() {
            val source = parse("""
            paths:
              /orders:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                        examples:
                          sample:
                            value:
                              id: order-1
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                          examples:
                            sample:
                              value:
                                accepted: true
            """).inlineNamedStubs.single().source

            assertThat(source).isNotNull
            assertThat(source!!.operationPointer).isEqualTo("/paths/~1orders/post")
            assertThat(source.requestVariantPointer).isEqualTo("/paths/~1orders/post/requestBody/content/application~1json")
            assertThat(source.responseVariantPointer).isEqualTo("/paths/~1orders/post/responses/200/content/application~1json")
        }

        @Test
        fun `parameter only example uses the operation and no body request variant`() {
            val source = parse("""
            paths:
              /orders/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                      examples:
                        sample:
                          value: order-1
                  responses:
                    '204':
                      description: No content
            """).inlineNamedStubs.single().source

            assertThat(source).isNotNull
            assertThat(source!!.operationPointer).isEqualTo("/paths/~1orders~1{id}/get")
            assertThat(source.requestVariantPointer).isEqualTo("/paths/~1orders~1{id}/get")
            assertThat(source.responseVariantPointer).isEqualTo("/paths/~1orders~1{id}/get/responses/204")
        }

        @Test
        fun `request only example keeps real request and no body response variants`() {
            val source = parse("""
            paths:
              /orders:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                        examples:
                          sample:
                            value:
                              id: order-1
                  responses:
                    '204':
                      description: No content
            """).inlineNamedStubs.single().source

            assertThat(source).isNotNull
            assertThat(source!!.responseVariantPointer).isEqualTo("/paths/~1orders/post/responses/204")
            assertThat(source.requestVariantPointer).isEqualTo("/paths/~1orders/post/requestBody/content/application~1json")
        }

        @Test
        fun `distinguishes the same name by response variant`() {
            val sources = parse("""
            paths:
              /orders:
                get:
                  parameters:
                    - name: trace
                      in: query
                      schema:
                        type: string
                      examples:
                        sample:
                          value: trace-1
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                          examples:
                            sample:
                              value:
                                state: accepted
                    '400':
                      description: Bad request
                      content:
                        application/json:
                          schema:
                            type: object
                          examples:
                            sample:
                              value:
                                state: rejected
            """).inlineNamedStubs.map { it.source!! }

            assertThat(sources).hasSize(2)
            assertThat(sources.map { it.operationPointer }).containsOnly("/paths/~1orders/get")
            assertThat(sources.map { it.responseVariantPointer }).containsExactlyInAnyOrder(
                "/paths/~1orders/get/responses/200/content/application~1json",
                "/paths/~1orders/get/responses/400/content/application~1json",
            )
        }

        @Test
        fun `payload changes do not change source metadata`() {
            fun source(requestId: String, accepted: Boolean) = parse("""
            paths:
              /orders:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                        examples:
                          sample:
                            value:
                              id: $requestId
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                          examples:
                            sample:
                              value:
                                accepted: $accepted
            """).inlineNamedStubs.single().source
            assertThat(source("order-1", true)).isEqualTo(source("order-2", false))
        }

        private fun parse(paths: String): Feature {
            return OpenApiSpecification.fromYAML("""
            openapi: 3.0.0
            info:
              title: Inline example source metadata
              version: 1.0.0
            """.trimIndent() + "\n" + paths.trimIndent(), "inline-example-source.yaml").toFeature()
        }
    }

    @Nested
    inner class InlineExampleValueDeserialisation {
        @Test
        fun `parameter example values are de-serialised from inline and internal and external refs`(@TempDir tempDir: File) {
            val externalFile = tempDir.resolve("parameter-examples.yaml")
            externalFile.writeText("""
            IdExternal:
              value: person-external
            TraceExternal:
              value: trace-external
            """.trimIndent())

            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText($$"""
            openapi: 3.0.0
            info:
              title: Parameter examples
              version: 1.0.0
            paths:
              /people/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                      examples:
                        INLINE_EXAMPLE:
                          value: person-1
                        INTERNAL_REF_EXAMPLE:
                          $ref: '#/components/examples/IdInternal'
                        EXTERNAL_REF_EXAMPLE:
                          $ref: './parameter-examples.yaml#/IdExternal'
                    - name: X-Trace
                      in: header
                      schema:
                        type: string
                      examples:
                        INLINE_EXAMPLE:
                          value: trace-1
                        INTERNAL_REF_EXAMPLE:
                          $ref: '#/components/examples/TraceInternal'
                        EXTERNAL_REF_EXAMPLE:
                          $ref: './parameter-examples.yaml#/TraceExternal'
                  responses:
                    '200':
                      description: OK
            components:
              examples:
                IdInternal:
                  value: person-internal
                TraceInternal:
                  value: trace-internal
            """.trimIndent())

            val feature = OpenApiSpecification.fromYAML(openApiFile.readText(), openApiFile.canonicalPath).toFeature()
            val rows = feature.scenarios.single().examples.single().rows.associateBy { it.name }

            assertThat(rows["INLINE_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()

            assertThat(rows["INLINE_EXAMPLE"]!!.getField("id")).isEqualTo("person-1")
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.getField("id")).isEqualTo("person-internal")
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.getField("id")).isEqualTo("person-external")

            assertThat(rows["INLINE_EXAMPLE"]!!.getField("X-Trace")).isEqualTo("trace-1")
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.getField("X-Trace")).isEqualTo("trace-internal")
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.getField("X-Trace")).isEqualTo("trace-external")
        }

        @Test
        fun `query parameter example values are de-serialised from inline and internal and external refs`(@TempDir tempDir: File) {
            val externalFile = tempDir.resolve("query-parameter-examples.yaml")
            externalFile.writeText("""
            value:
              value: people-external
            """.trimIndent())

            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText($$"""
            openapi: 3.0.0
            info:
              title: Query parameter examples
              version: 1.0.0
            paths:
              /people:
                get:
                  parameters:
                    - name: search
                      in: query
                      schema:
                        type: string
                      examples:
                        INLINE_EXAMPLE:
                          value: people
                        INTERNAL_REF_EXAMPLE:
                          $ref: '#/components/examples/InternalQueryExample'
                        EXTERNAL_REF_EXAMPLE:
                          $ref: './query-parameter-examples.yaml#/value'
                  responses:
                    '200':
                      description: OK
            components:
              examples:
                InternalQueryExample:
                  value: people-internal
            """.trimIndent())

            val feature = OpenApiSpecification.fromYAML(openApiFile.readText(), openApiFile.canonicalPath).toFeature()
            val rows = feature.scenarios.single().examples.single().rows.associateBy { it.name }

            assertThat(rows["INLINE_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()

            assertThat(rows["INLINE_EXAMPLE"]!!.getField("search")).isEqualTo("people")
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.getField("search")).isEqualTo("people-internal")
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.getField("search")).isEqualTo("people-external")
        }

        @Test
        fun `nested query parameter example values are de-serialised from inline and internal and external refs`(@TempDir tempDir: File) {
            val externalFile = tempDir.resolve("nested-query-parameter-examples.yaml")
            externalFile.writeText("""
            value:
              value: name=External&address[0].street=External%20Street
            """.trimIndent())

            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText($$"""
            openapi: 3.0.0
            info:
              title: Nested query parameter examples
              version: 1.0.0
            paths:
              /people:
                get:
                  parameters:
                    - name: details
                      in: query
                      required: true
                      schema:
                        type: object
                        properties:
                          name:
                            type: string
                          address:
                            type: array
                            items:
                              type: object
                              properties:
                                street:
                                  type: string
                      examples:
                        INLINE_EXAMPLE:
                          value: name=Jack&address[0].street=Baker%20Street
                        INTERNAL_REF_EXAMPLE:
                          $ref: '#/components/examples/InternalNestedQueryExample'
                        EXTERNAL_REF_EXAMPLE:
                          $ref: './nested-query-parameter-examples.yaml#/value'
                  responses:
                    '200':
                      description: OK
            components:
              examples:
                InternalNestedQueryExample:
                  value: name=Internal&address[0].street=Internal%20Street
            """.trimIndent())

            val feature = OpenApiSpecification.fromYAML(openApiFile.readText(), openApiFile.canonicalPath).toFeature()
            val rows = feature.scenarios.single().examples.single().rows.associateBy { it.name }

            assertThat(rows["INLINE_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()

            assertThat(rows["INLINE_EXAMPLE"]!!.getField("details")).contains("Jack", "Baker Street")
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.getField("details")).contains("Internal", "Internal Street")
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.getField("details")).contains("External", "External Street")
        }

        @Test
        fun `response body example values are de-serialised from inline and internal and external refs`(@TempDir tempDir: File) {
            val externalFile = tempDir.resolve("response-body-examples.yaml")
            externalFile.writeText("""
            ExternalResponse:
              value:
                name: External
            ExternalRequest:
              value:
                request: external
            """.trimIndent())

            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText($$"""
            openapi: 3.0.0
            info:
              title: Response body examples
              version: 1.0.0
            paths:
              /people:
                get:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                        examples:
                          INLINE_EXAMPLE:
                            value:
                              request: inline
                          INTERNAL_REF_EXAMPLE:
                            $ref: '#/components/examples/InternalRequest'
                          EXTERNAL_REF_EXAMPLE:
                            $ref: './response-body-examples.yaml#/ExternalRequest'
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                          examples:
                            INLINE_EXAMPLE:
                              value:
                                name: Jack
                            INTERNAL_REF_EXAMPLE:
                              $ref: '#/components/examples/InternalResponse'
                            EXTERNAL_REF_EXAMPLE:
                              $ref: './response-body-examples.yaml#/ExternalResponse'
            components:
              examples:
                InternalResponse:
                  value:
                    name: Internal
                InternalRequest:
                  value:
                    request: internal
            """.trimIndent())

            val feature = OpenApiSpecification.fromYAML(openApiFile.readText(), openApiFile.canonicalPath).toFeature()
            val rows = feature.scenarios.single().examples.single().rows.associateBy { it.name }

            assertThat(rows["INLINE_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()

            assertThat(rows["INLINE_EXAMPLE"]!!.responseExample!!.body).isEqualTo(parsedJSONObject("""{"name":"Jack"}"""))
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.responseExample!!.body).isEqualTo(parsedJSONObject("""{"name":"Internal"}"""))
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.responseExample!!.body).isEqualTo(parsedJSONObject("""{"name":"External"}"""))
        }

        @Test
        fun `response header example values are de-serialised from inline and internal and external refs`(@TempDir tempDir: File) {
            val externalFile = tempDir.resolve("response-header-examples.yaml")
            externalFile.writeText("""
            ExternalHeader:
              value: trace-external
            ExternalRequest:
              value: request-external
            """.trimIndent())

            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText($$"""
            openapi: 3.0.0
            info:
              title: Response header examples
              version: 1.0.0
            paths:
              /people:
                get:
                  parameters:
                    - name: requestId
                      in: query
                      schema:
                        type: string
                      examples:
                        INLINE_EXAMPLE:
                          value: request-inline
                        INTERNAL_REF_EXAMPLE:
                          $ref: '#/components/examples/InternalRequest'
                        EXTERNAL_REF_EXAMPLE:
                          $ref: './response-header-examples.yaml#/ExternalRequest'
                  responses:
                    '200':
                      description: OK
                      headers:
                        X-Trace:
                          schema:
                            type: string
                          examples:
                            INLINE_EXAMPLE:
                              value: trace-1
                            INTERNAL_REF_EXAMPLE:
                              $ref: '#/components/examples/InternalHeader'
                            EXTERNAL_REF_EXAMPLE:
                              $ref: './response-header-examples.yaml#/ExternalHeader'
            components:
              examples:
                InternalHeader:
                  value: trace-internal
                InternalRequest:
                  value: request-internal
            """.trimIndent())

            val feature = OpenApiSpecification.fromYAML(openApiFile.readText(), openApiFile.canonicalPath).toFeature()
            val rows = feature.scenarios.single().examples.single().rows.associateBy { it.name }

            assertThat(rows["INLINE_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.columnNames).doesNotHaveDuplicates()

            assertThat(rows["INLINE_EXAMPLE"]!!.responseExample!!.headers).containsEntry("X-Trace", "trace-1")
            assertThat(rows["INTERNAL_REF_EXAMPLE"]!!.responseExample!!.headers).containsEntry("X-Trace", "trace-internal")
            assertThat(rows["EXTERNAL_REF_EXAMPLE"]!!.responseExample!!.headers).containsEntry("X-Trace", "trace-external")
        }

        @Test
        fun `structured OpenAPI request examples support inline, internal ref, external ref, and externalValue`(@TempDir tempDir: File) {
            val externalFile = tempDir.resolve("external-examples.yaml")
            externalFile.writeText("""
            ExternalExampleRef:
              value:
                type: "external_reference"
                description: "Loaded from a file via external ref"
            """.trimIndent())

            val externalValueFile = tempDir.resolve("external-value.json")
            externalValueFile.writeText("""
            {
              "type": "external_value",
              "description": "Loaded from a file via externalValue"
            }
            """.trimIndent())

            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText($$"""
            openapi: 3.0.0
            info:
              title: Example serialization
              version: 1.0.0
            paths:
              /people:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                        examples:
                          INLINE_EXAMPLE:
                            value:
                              type: "inline"
                              description: "Defined inline with the specification"
                          INTERNAL_REF_EXAMPLE:
                            $ref: '#/components/examples/InternalExampleRef'
                          EXTERNAL_REF_EXAMPLE:
                            $ref: './external-examples.yaml#/ExternalExampleRef'
                          EXTERNAL_VALUE_EXAMPLE:
                            externalValue: './external-value.json'
                  responses:
                    '200':
                      description: OK
            components:
              examples:
                InternalExampleRef:
                  value:
                    type: "internal_reference"
                    description: "Defined as a reusable component within the specification"
            """.trimIndent())

            val specification = OpenApiSpecification.fromYAML(
                yamlContent = openApiFile.readText(),
                openApiFilePath = openApiFile.canonicalPath
            )

            val examplesMap = specification.parsedOpenApi.paths["/people"]!!.post!!.requestBody!!.content["application/json"]!!.examples
            assertThat(examplesMap).containsKeys(
                "INLINE_EXAMPLE",
                "INTERNAL_REF_EXAMPLE",
                "EXTERNAL_REF_EXAMPLE",
                "EXTERNAL_VALUE_EXAMPLE"
            )

            val feature = specification.toFeature()
            val rows = feature.scenarios.single().examples.single().rows

            assertThat(rows).allSatisfy { row ->
                assertThat(row.columnNames).doesNotHaveDuplicates()
                val requestBody = parsedJsonValue(row.getField("(REQUEST-BODY)"))
                assertThat(requestBody).isIn(
                    StringValue(), // externalValue examples are unsupported
                    """{"type":"inline","description":"Defined inline with the specification"}""".let(::parsedJSONObject),
                    """{"type":"external_reference","description":"Loaded from a file via external ref"}""".let(::parsedJSONObject),
                    """{"type":"internal_reference","description":"Defined as a reusable component within the specification"}""".let(::parsedJSONObject),
                )
            }
        }

        @Test
        fun `parameter example values include path parameters when request has a body and unused example with empty response`(@TempDir tempDir: File) {
            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText("""
            openapi: 3.0.0
            info:
              title: Parameter examples with request body
              version: 1.0.0
    
            paths:
              /people/{id}:
                post:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                      examples:
                        INLINE_EXAMPLE:
                          value: person-1
    
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            name:
                              type: string
                        examples:
                          INLINE_EXAMPLE:
                            value:
                              name: John
    
                  responses:
                    '200':
                      description: OK
            """.trimIndent())

            val feature = OpenApiSpecification.fromYAML(openApiFile.readText(), openApiFile.canonicalPath).toFeature()
            val row = feature.scenarios.single().examples.single().rows.single { it.name == "INLINE_EXAMPLE" }

            assertThat(row.columnNames).doesNotHaveDuplicates()
            assertThat(row.getField("id")).isEqualTo("person-1")
            assertThat(row.getField("(REQUEST-BODY)").let(::parsedJsonValue)).isEqualTo("""{"name":"John"}""".let(::parsedJSONObject))
        }

        @Test
        fun `path level parameter example values are included when request has a body and unused example with empty response`(@TempDir tempDir: File) {
            val openApiFile = tempDir.resolve("openapi.yaml")
            openApiFile.writeText("""
            openapi: 3.0.0
            info:
              title: Path level parameter examples with request body
              version: 1.0.0
        
            paths:
              /people/{id}:
                parameters:
                  - name: id
                    in: path
                    required: true
                    schema:
                      type: string
                    examples:
                      INLINE_EXAMPLE:
                        value: person-1
        
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            name:
                              type: string
                        examples:
                          INLINE_EXAMPLE:
                            value:
                              name: John
        
                  responses:
                    '200':
                      description: OK
            """.trimIndent())

            val feature = OpenApiSpecification.fromYAML(openApiFile.readText(), openApiFile.canonicalPath).toFeature()
            val row = feature.scenarios.single().examples.single().rows.single { it.name == "INLINE_EXAMPLE" }

            assertThat(row.columnNames).doesNotHaveDuplicates()
            assertThat(row.getField("id")).isEqualTo("person-1")
            assertThat(row.getField("(REQUEST-BODY)").let(::parsedJsonValue)).isEqualTo("""{"name":"John"}""".let(::parsedJSONObject))
        }
    }

    @Nested
    inner class MultiPartFormDataExamples {
        @Test
        fun `should read typed and encoded multipart inline examples`() {
            val contractString = """
            openapi: 3.0.3
            info: { title: test, version: '1.0' }
            paths:
              /documents:
                post:
                  requestBody:
                    content:
                      multipart/form-data:
                        encoding:
                          metadata:
                            contentType: application/json
                          document:
                            contentType: application/octet-stream
                        schema:
                          type: object
                          required: [metadata, document]
                          properties:
                            metadata:
                              type: object
                              required: [id]
                              properties:
                                id: { type: integer }
                            document:
                              type: string
                              format: binary
                        examples:
                          selected:
                            value:
                              metadata: { id: 7 }
                              document: encoded-content
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          examples:
                            selected: { value: ok }
            """.trimIndent()

            val inlineStub = OpenApiSpecification.fromYAML(contractString, "")
                .toFeature()
                .inlineNamedStubs
                .single { it.name == "selected" }

            val parts = inlineStub.stub.request.multiPartFormData.associateBy { it.name }
            assertThat(parts.getValue("document").filename).isNull()
            assertThat(parts.keys).containsExactlyInAnyOrder("metadata", "document")
            assertThat(parts.getValue("metadata").contentType).isEqualTo("application/json")
            assertThat(parts.getValue("document").content).isEqualTo(StringValue("encoded-content"))
            assertThat(parts.getValue("document").contentType).isEqualTo("application/octet-stream")
            assertThat(parts.getValue("metadata").content).isEqualTo(JSONObjectValue(mapOf("id" to NumberValue(7))))
        }

        @Test
        fun `should resolve multipart inline example refs`() {
            val contractString = $$"""
            openapi: 3.0.3
            info: { title: test, version: '1.0' }
            paths:
              /documents:
                post:
                  requestBody:
                    content:
                      multipart/form-data:
                        schema:
                          type: object
                          properties:
                            document: { type: string }
                        examples:
                          selected:
                            $ref: '#/components/examples/selectedMultipart'
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          examples:
                            selected: { value: ok }
            components:
              examples:
                selectedMultipart:
                  value:
                    document: from-ref
            """.trimIndent()

            val inlineStub = OpenApiSpecification.fromYAML(contractString, "")
                .toFeature()
                .inlineNamedStubs
                .single { it.name == "selected" }

            assertThat(inlineStub.stub.request.multiPartFormData.single().content).isEqualTo(StringValue("from-ref"))
        }

        @Test
        fun `should ignore multipart inline examples when configured`() {
            val contractString = """
            openapi: 3.0.3
            info: { title: test, version: '1.0' }
            paths:
              /documents:
                post:
                  requestBody:
                    content:
                      multipart/form-data:
                        schema:
                          type: object
                          properties:
                            document: { type: string }
                        examples:
                          selected:
                            value:
                              document: ignored
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          examples:
                            selected: { value: ok }
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(
                openApiFilePath = "",
                yamlContent = contractString,
                specmaticConfig = SpecmaticConfigV1V2Common(ignoreInlineExamples = true),
            ).toFeature()

            assertThat(feature.inlineNamedStubs).isEmpty()
        }
    }

    @Suppress("SameParameterValue")
    private fun loadFixture(name: String): String = this::class.java.classLoader.getResource(name)!!.readText()

    companion object {
        data class AdditionalPropsCase(val name: String, val version: OpenApiVersion, val additionalProperties: Any?, val check: (Any?) -> Unit) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun openApiVersionsProviders(): List<OpenApiVersion> = OpenApiVersion.entries

        @JvmStatic
        fun additionalPropertiesTestCases(): Stream<AdditionalPropsCase> {
            fun casesFor(version: OpenApiVersion) = listOf(
                AdditionalPropsCase(
                    "unspecified ($version)",
                    version,
                    null,
                    { additionalProps -> assertThat(additionalProps).isNull() }
                ),
                AdditionalPropsCase(
                    "true ($version)",
                    version,
                    true,
                    { additionalProps -> assertThat(additionalProps).isInstanceOf(AnythingPattern::class.java) }
                ),
                AdditionalPropsCase(
                    "false ($version)",
                    version,
                    false,
                    { additionalProps -> assertThat(additionalProps).isNull() }
                ),
                AdditionalPropsCase(
                    "inline schema ($version)",
                    version,
                    mapOf("type" to "string", "pattern" to "^test.*"),
                    { additionalProps ->
                        assertThat(additionalProps).isInstanceOf(StringPattern::class.java)
                        additionalProps as StringPattern
                        assertThat(additionalProps.regex).isEqualTo("^test.*")
                    }
                ),
                AdditionalPropsCase(
                    "ref ($version)",
                    version,
                    mapOf("\$ref" to "#/components/schemas/EmailPattern"),
                    { additionalProps ->
                        assertThat(additionalProps).isInstanceOf(DeferredPattern::class.java); additionalProps as DeferredPattern
                        assertThat(additionalProps.pattern).isEqualTo("(EmailPattern)")
                    }
                )
            )

            return OpenApiVersion.entries.flatMap(::casesFor).stream()
        }
    }
}
