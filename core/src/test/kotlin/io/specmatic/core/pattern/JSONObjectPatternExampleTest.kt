package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class JSONObjectPatternExampleTest {

    @Test
    fun `should use example when generating JSONObjectValue`() {
        val example = mapOf(
            "name" to "John Doe",
            "age" to 30
        )
        
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "name" to StringPattern(),
                "age" to NumberPattern()
            ),
            example = example
        )
        
        val resolver = Resolver()
        val generated = pattern.generate(resolver)
        
        assertThat(generated.jsonObject["name"]?.toStringLiteral()).isEqualTo("John Doe")
        assertThat(generated.jsonObject["age"]?.toStringLiteral()).isEqualTo("30")
    }
    
    @Test
    fun `should use example even with optional fields`() {
        val example = mapOf(
            "name" to "Jane Smith",
            "age" to 25,
            "email" to "jane@example.com"
        )
        
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "name" to StringPattern(),
                "age?" to NumberPattern(),
                "email?" to StringPattern()
            ),
            example = example
        )
        
        val resolver = Resolver()
        val generated = pattern.generate(resolver)
        
        assertThat(generated.jsonObject["name"]?.toStringLiteral()).isEqualTo("Jane Smith")
        assertThat(generated.jsonObject["age"]?.toStringLiteral()).isEqualTo("25")
        assertThat(generated.jsonObject["email"]?.toStringLiteral()).isEqualTo("jane@example.com")
    }
}