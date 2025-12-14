package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class BinaryPatternTest {

    @Test
    @Tag(GENERATION)
    fun `negative patterns should be generated`() {
        val result = BinaryPattern().negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null",
            "number",
            "boolean"
        )
    }

    @Test
    fun `should use provided example during generation`() {
        val example = "sample-bytes"
        val pattern = BinaryPattern(example = example)

        val generated = pattern.generate(Resolver())

        assertThat(generated.toStringLiteral()).isEqualTo(example)
    }
}
