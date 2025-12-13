package io.specmatic.conversions

import integration_tests.OpenApiVersion
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLTypeData
import io.specmatic.core.pattern.resolvedHop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.math.BigDecimal

class OpenApiSpecificationParseTest {
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
            >> components.schemas.EnumPattern
            Failed to parse enum. One or more enum values were parsed as null
            This often happens in OpenAPI 3.0.x when enum values have mixed or invalid types and the parser implicitly coerces those values to null
            Please check the enum schema and entries or mark then schema as nullable if this was intentional
            """.trimIndent())
        } else {
            assertThat(exception.report()).isEqualToIgnoringWhitespace("""
            >> components.schemas.EnumPattern
            Failed to parse enum values, please check the schema and entries:
            One or more enum values do not match the specified type, Found types: number, string
            """.trimIndent())
        }
    }

    companion object {
        @JvmStatic
        fun openApiVersionsProviders(): List<OpenApiVersion> = OpenApiVersion.entries
    }
}
