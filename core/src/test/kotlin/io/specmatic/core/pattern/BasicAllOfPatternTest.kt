package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class BasicAllOfPatternTest {
    
    @Test
    fun `allOf should create pattern correctly`() {
        val stringPattern = StringPattern()
        val exactPattern = ExactValuePattern(StringValue("test"))
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, exactPattern))
        
        // Just test that the pattern was created
        assertThat(allOfPattern.pattern).hasSize(2)
        assertThat(allOfPattern.typeName).isNotNull()
    }

    @Test
    fun `allOf should match when all patterns match`() {
        val stringPattern = StringPattern()
        val exactPattern = ExactValuePattern(StringValue("test"))
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, exactPattern))
        val resolver = Resolver()
        
        // Should match when value satisfies both patterns
        val result = allOfPattern.matches(StringValue("test"), resolver)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `allOf should fail when any pattern fails`() {
        val stringPattern = StringPattern()
        val exactPattern = ExactValuePattern(StringValue("expected"))
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, exactPattern))
        val resolver = Resolver()
        
        // Should fail when value doesn't satisfy exact pattern
        val result = allOfPattern.matches(StringValue("actual"), resolver)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }
}