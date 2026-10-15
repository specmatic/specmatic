package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HasValueTest {
    @Test
    fun `it should be able to be distincted in a list`() {
        val hasDuplicates = listOf(HasValue(StringPattern()), HasValue(StringPattern()), HasValue(NumberPattern()), HasValue(NumberPattern()))
        val distinct = hasDuplicates.distinct()

        assertThat(distinct).hasSize(2)
        assertThat(distinct[0]).isEqualTo(HasValue(StringPattern()))
        assertThat(distinct[1]).isEqualTo(HasValue(NumberPattern()))
    }
}
