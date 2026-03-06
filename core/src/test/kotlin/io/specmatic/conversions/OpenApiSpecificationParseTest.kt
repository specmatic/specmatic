package io.specmatic.conversions

import integration_tests.OpenApiVersion
import io.specmatic.core.pattern.AnythingPattern
import io.specmatic.core.pattern.BooleanPattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLTypeData
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.utilities.yamlMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.math.BigDecimal
import java.util.stream.Stream

class OpenApiSpecificationParseTest {
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
        assertThat(requestPattern.httpPathPattern?.path).isEqualTo("/orders/(orderId:string)")

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
        assertThat(requestPattern.httpPathPattern?.path).contains("(orderId:number)")

        val queryPattern = requestPattern.httpQueryParamPattern.queryPatterns["page"]
        assertThat(queryPattern).isInstanceOf(QueryParameterScalarPattern::class.java)
        assertThat((queryPattern as QueryParameterScalarPattern).pattern).isInstanceOf(NumberPattern::class.java)

        val headerPattern = requestPattern.headersPattern.pattern["X-Request-Id"]
        assertThat(headerPattern).isInstanceOf(NumberPattern::class.java)
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
        assertThat(requestPattern.httpPathPattern?.path).isEqualTo("/orders/(orderId:string)")
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
