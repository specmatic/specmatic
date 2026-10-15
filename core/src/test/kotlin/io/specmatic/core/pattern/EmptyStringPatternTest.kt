package io.specmatic.core.pattern

import org.junit.jupiter.api.Test
import io.specmatic.core.value.EmptyString
import io.specmatic.shouldMatch

internal class EmptyStringPatternTest {
    @Test
    fun `should match empty strings gracefully`() {
        EmptyString shouldMatch EmptyStringPattern
    }
}
