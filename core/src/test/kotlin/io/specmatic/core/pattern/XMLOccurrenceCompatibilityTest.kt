package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XMLOccurrenceCompatibilityTest {
    @Test
    fun `legacy multiple encompasses one optional and multiple occurrences`() {
        val multiple = XMLPattern("""<root><A specmatic_occurs="multiple">(string)</A></root>""")
        for (attribute in listOf("", "specmatic_occurs=\"optional\"", "specmatic_occurs=\"multiple\"")) {
            val other = XMLPattern("""<root><A $attribute>(string)</A></root>""")
            val result = multiple.encompasses(other, Resolver(), Resolver())
            assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
        }
    }
    @Test
    fun `negative boundary above the largest machine integer does not overflow`() {
        val pattern = XMLParticlePattern(XMLPattern("<A/>"), XMLOccurrenceRange(0, Int.MAX_VALUE))
        val negative = pattern.negativeBasedOn(Row(), Resolver()).first().value
        assertThat(pattern.encompasses(negative, Resolver(), Resolver()).isSuccess()).isFalse()
    }

}
