package io.specmatic.core

import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONArrayValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResolverTest {
    @Test
    fun `generateList should respect minItems constraint`() {
        val pattern = ListPattern(pattern = StringPattern(), minItems = 3)
        
        repeat(10) {
            val result = Resolver().generateList(pattern) as JSONArrayValue
            assertThat(result.list.size).isGreaterThanOrEqualTo(3)
        }
    }

    @Test
    fun `generateList should respect maxItems constraint`() {
        val pattern = ListPattern(pattern = StringPattern(), maxItems = 4)
        
        repeat(10) {
            val result = Resolver().generateList(pattern) as JSONArrayValue
            assertThat(result.list.size).isLessThanOrEqualTo(4)
        }
    }

    @Test
    fun `generateList should respect both minItems and maxItems constraints`() {
        val pattern = ListPattern(pattern = StringPattern(), minItems = 2, maxItems = 5)
        
        repeat(10) {
            val result = Resolver().generateList(pattern) as JSONArrayValue
            assertThat(result.list.size).isGreaterThanOrEqualTo(2)
            assertThat(result.list.size).isLessThanOrEqualTo(5)
        }
    }
}
