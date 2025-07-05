package io.specmatic.core.pattern

import io.specmatic.core.value.StringValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AllOfPatternTest {
    @Test
    fun `allOf should match when value satisfies all constituent patterns`() {
        val stringPattern = StringPattern()
        val exactPattern = ExactValuePattern(StringValue("test"))
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, exactPattern))
        val resolver = Resolver()
        
        val result = allOfPattern.matches(StringValue("test"), resolver)
        
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `allOf should not match when value fails any constituent pattern`() {
        val stringPattern = StringPattern()
        val exactPattern = ExactValuePattern(StringValue("expected"))
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, exactPattern))
        val resolver = Resolver()
        
        val result = allOfPattern.matches(StringValue("actual"), resolver)
        
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `allOf should not match when value satisfies some but not all patterns`() {
        val stringPattern = StringPattern()
        val numberPattern = NumberPattern()
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, numberPattern))
        val resolver = Resolver()
        
        val result = allOfPattern.matches(StringValue("test"), resolver)
        
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `allOf should generate a value that satisfies all patterns`() {
        val stringPattern = StringPattern()
        val exactPattern = ExactValuePattern(StringValue("test"))
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, exactPattern))
        val resolver = Resolver()
        
        val generatedValue = allOfPattern.generate(resolver)
        val matchResult = allOfPattern.matches(generatedValue, resolver)
        
        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `allOf typeName should combine constituent pattern names`() {
        val stringPattern = StringPattern()
        val numberPattern = NumberPattern()
        
        val allOfPattern = AllOfPattern(listOf(stringPattern, numberPattern))
        
        assertThat(allOfPattern.typeName).contains("string and number")
    }
}