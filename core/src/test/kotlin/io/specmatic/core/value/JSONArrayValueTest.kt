package io.specmatic.core.value

import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class JSONArrayValueTest {
    @Test
    fun `deepPattern should return the correct pattern`() {
        val array = JSONArrayValue(
            listOf(
                JSONObjectValue(
                    mapOf(
                        "id" to NumberValue(10),
                        "name" to StringValue("name")
                    )
                )
            )
        )

        val pattern = array.deepPattern()

        assertThat(pattern).isInstanceOf(ListPattern::class.java)
        pattern as ListPattern

        val objectPattern = pattern.pattern
        assertThat(objectPattern).isInstanceOf(JSONObjectPattern::class.java)
        objectPattern as JSONObjectPattern

        assertThat(objectPattern.pattern).isEqualTo(
            mapOf(
                "id" to NumberPattern(),
                "name" to StringPattern()
            )
        )
    }
}
