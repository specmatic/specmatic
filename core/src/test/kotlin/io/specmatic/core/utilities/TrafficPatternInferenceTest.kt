package io.specmatic.core.utilities

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.fold.UnknownValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrafficPatternInferenceTest {
    @Test
    fun `rejects value shapes that do not participate in visitor traversal`() {
        val exception = assertThrows<ContractException> {
            UnknownValue().toPatternDeclaration("RequestBody")
        }

        assertThat(exception.message).contains("Cannot infer an OpenAPI pattern from UnknownValue")
    }
}
