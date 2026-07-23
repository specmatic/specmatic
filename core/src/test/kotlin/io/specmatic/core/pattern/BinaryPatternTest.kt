package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.Result.Success
import io.specmatic.core.value.BinaryValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class BinaryPatternTest {

    @Test
    fun `matches raw binary values`() {
        assertThat(BinaryPattern().matches(BinaryValue(byteArrayOf(1, 2, 3)), Resolver()))
            .isInstanceOf(Success::class.java)
    }

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
}
