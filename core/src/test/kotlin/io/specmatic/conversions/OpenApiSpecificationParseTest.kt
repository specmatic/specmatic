package io.specmatic.conversions

import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLTypeData
import io.specmatic.core.pattern.resolvedHop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
}
