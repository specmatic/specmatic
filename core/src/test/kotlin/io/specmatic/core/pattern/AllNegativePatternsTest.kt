package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.NumberValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AllNegativePatternsTest {
    @Test
    fun `should return negative patterns for each key`() {
        val patternMap = mapOf("key" to StringPattern())
        val resolver = Resolver()
        val row = Row()

        val negativePatterns: List<Map<String, Pattern>> =
            AllNegativePatterns().negativeBasedOn(patternMap, row, resolver).map { it.value }.toList()

        assertThat(negativePatterns).containsExactlyInAnyOrder(
            mapOf("key" to NumberPattern()),
            mapOf("key" to BooleanPattern()),
            mapOf("key" to NullPattern)
        )
    }

    @Test
    fun `negative patterns for multiple keys`() {
        val patternMap = mapOf("key1" to NumberPattern(), "key2" to StringPattern())
        val resolver = Resolver()
        val row = Row()

        val negativePatterns: List<Map<String, Pattern>> =
            AllNegativePatterns().negativeBasedOn(patternMap, row, resolver).map { it.value }.toList()

        assertThat(negativePatterns).containsExactlyInAnyOrder(
            mapOf("key1" to BooleanPattern(), "key2" to StringPattern()),
            mapOf("key1" to StringPattern(), "key2" to StringPattern()),
            mapOf("key1" to NullPattern, "key2" to StringPattern()),
            mapOf("key1" to NumberPattern(), "key2" to BooleanPattern()),
            mapOf("key1" to NumberPattern(), "key2" to NumberPattern()),
            mapOf("key1" to NumberPattern(), "key2" to NullPattern),
        )
    }
}