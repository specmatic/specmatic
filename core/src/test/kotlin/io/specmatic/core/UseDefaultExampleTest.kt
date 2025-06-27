package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaExampleUtilsTests {

    @Test
    fun `resolve scalar example with null example`() {
        val value = resolveExample(null as String?, StringPattern(), Resolver())
        assertThat(value).isNull()
    }

    @Test
    fun `resolve scalar example with non-null example`() {
        val value = resolveExample("example", StringPattern(), Resolver())
        assertThat(value?.toStringLiteral()).isEqualTo("example")
    }

    @Test
    fun `scalar example with non-null example does not match the given type`() {
        assertThatThrownBy { resolveExample("example", NumberPattern(), Resolver()) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `scalar example with non-null example does not match the constraints`() {
        assertThatThrownBy { resolveExample("example", StringPattern(maxLength = 1), Resolver()) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `resolve matching one of the given patterns with null example`() {
        val value = resolveExample(null, listOf(StringPattern()), Resolver())
        assertThat(value).isNull()
    }

    @Test
    fun `resolve matching one of the given patterns with non-null example given patterns`() {
        val value = resolveExample("example", listOf(StringPattern()), Resolver())
        assertThat(value?.toStringLiteral()).isEqualTo("example")
    }

    @Test
    fun `fails to match one of the given patterns with non-null example given patterns`() {
        assertThatThrownBy { resolveExample("example", listOf(NumberPattern()), Resolver()) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `resolve matching one of the given patterns with non-null example given no patterns`() {
        assertThrows<ContractException> {
            resolveExample("example", listOf(), Resolver())
        }
    }

    @Test
    fun `the default example for this key is not omit given no example`() {
        val boolean = theDefaultExampleForThisKeyIsNotOmit(StringPattern())
        assertThat(boolean).isTrue()
    }

    @Test
    fun `the default example for this key is not omit given an example`() {
        val boolean = theDefaultExampleForThisKeyIsNotOmit(StringPattern(example = "Hello World"))
        assertThat(boolean).isTrue()
    }

    @Test
    fun `the default example for this key is omit`() {
        val boolean = theDefaultExampleForThisKeyIsNotOmit(StringPattern(example = "(omit)"))
        assertThat(boolean).isFalse()
    }

    @Test
    fun `resolve array example with null example`() {
        val value = resolveExample(null as List<String?>?, StringPattern(), Resolver())
        assertThat(value).isNull()
    }

    @Test
    fun `resolve array example with non-null example`() {
        val value = resolveExample(listOf("example"), StringPattern(), Resolver())
        assertThat(value).isEqualTo(JSONArrayValue(listOf(StringValue("example"))))
    }

    @Test
    fun `should not resolve example when pattern has been mutated to generate negative tests`() {
        val patterns = NumberPattern(example = "10").negativeBasedOn(Row(), Resolver())
        assertThat(patterns.toList()).allSatisfy { pattern ->
            val value = pattern.value.generate(Resolver())
            assertThat(value).isNotEqualTo(NumberValue(10))
        }
    }

    @Test
    fun `should prefer value from dictionary before resolving example when generating via resolver`() {
        val dictionary = Dictionary.fromYaml("(number): 123")
        val pattern = NumberPattern(example = "456")
        val generatedValue = Resolver(dictionary = dictionary).generate(pattern)

        assertThat(generatedValue).isEqualTo(NumberValue(123))
    }
}
