package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.toXMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XMLPatternOccurrenceTest {
    private val resolver = Resolver()
    private fun element(min: Int, max: Int?) = XMLPattern("<Item>(string)</Item>").let {
        it.copy(pattern = it.pattern.copy(occurrences = XMLOccurrenceRange(min, max)))
    }

    @Test
    fun `occurrence validation does not add cardinality to payload breadcrumbs`() {
        val parent = XMLPattern("<Root/>").let { it.copy(pattern = it.pattern.copy(nodes = listOf(element(1, 1)))) }
        val result = parent.matches(toXMLNode("<Root><Item><Unexpected/></Item></Root>"), resolver)

        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).doesNotContain("occurs 1..1")
    }

    @Test
    fun `direct element children retain boundaries through both variant APIs`() {
        val parent = XMLPattern("<Root/>").let { it.copy(pattern = it.pattern.copy(nodes = listOf(element(2, 8)))) }
        val variants = parent.newBasedOn(resolver).toList() + parent.newBasedOn(Row(), resolver).map { it.value as XMLPattern }.toList()
        assertThat(variants).isNotEmpty
        assertThat(variants.map { (it.pattern.nodes.single() as XMLPattern).pattern.occurrences })
            .contains(XMLOccurrenceRange(2, 2), XMLOccurrenceRange(5, 5), XMLOccurrenceRange(8, 8))
        for (variant in variants) {
            val value = variant.generate(resolver)
            assertThat(parent.matches(value, resolver).isSuccess()).isTrue()
        }
    }

    @Test
    fun `direct element repetitions leave required following elements available`() {
        val parent = XMLPattern("<Root/>").let { it.copy(pattern = it.pattern.copy(nodes = listOf(element(0, 3), element(1, 1)))) }
        for (count in 0..5) {
            val value = toXMLNode("<Root>${"<Item>value</Item>".repeat(count)}</Root>")
            assertThat(parent.matches(value, resolver).isSuccess()).describedAs("count %s", count).isEqualTo(count in 1..4)
        }
    }

    @Test
    fun `scalar type references retain the element around row and negative variants`() {
        val scalarResolver = Resolver(newPatterns = mapOf("(Scalar)" to StringPattern()))
        val reference = XMLPattern("""<Item specmatic_type="Scalar"/>""").let {
            it.copy(pattern = it.pattern.copy(occurrences = XMLOccurrenceRange(2, 3)))
        }
        val variants = reference.newBasedOn(Row(), scalarResolver).map { it.value }.toList() +
            reference.negativeBasedOn(Row(), scalarResolver).map { it.value }.toList()
        assertThat(variants).isNotEmpty
        assertThat(variants).allSatisfy { assertThat(it).isInstanceOf(XMLPattern::class.java) }
    }

    @Test
    fun `element ranges encompass equivalent legacy occurrence syntax`() {
        val legacy = XMLPattern("""<Item specmatic_occurs="multiple">(string)</Item>""")
        val numeric = element(0, null)
        assertThat(numeric.encompasses(legacy, resolver, resolver).isSuccess()).isTrue()
        assertThat(legacy.encompasses(numeric, resolver, resolver).isSuccess()).isTrue()
        assertThat(element(2, 3).encompasses(element(1, 3), resolver, resolver).isSuccess()).isFalse()
    }
}
