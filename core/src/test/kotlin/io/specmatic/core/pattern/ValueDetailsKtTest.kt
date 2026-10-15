package io.specmatic.core.pattern

import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test

class ValueDetailsKtTest {

    @Test
    fun `should return singleLineDescription of valueDetails when messages list is not empty`() {
        val valueDetails = listOf(
            ValueDetails(
                messages = listOf("mutated to null"),
                breadCrumbData = listOf("price")
            )
        )

        val singleLineDescription = valueDetails.singleLineDescription()

        assertThat(singleLineDescription).isEqualTo("price mutated to null")
    }

    @Test
    fun `should return singleLineDescription of valueDetails as empty when messages list is empty`() {
        val valueDetails = listOf(
            ValueDetails(
                messages = emptyList(),
                breadCrumbData = listOf("price")
            )
        )

        val singleLineDescription = valueDetails.singleLineDescription()

        assertThat(singleLineDescription).isEqualTo("")
    }
}
