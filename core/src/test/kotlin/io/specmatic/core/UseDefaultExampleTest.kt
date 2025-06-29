package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UseDefaultExampleTest {
    @Test
    fun `resolve scalar example with null example`() {
        val value = UseDefaultExample.resolveExample(null as String?, StringPattern(), Resolver())
        assertThat(value).isNull()
    }

    @Test
    fun `resolve scalar example with non-null example`() {
        val value = UseDefaultExample.resolveExample("example", StringPattern(), Resolver())
        assertThat(value?.toStringLiteral()).isEqualTo("example")
    }

    @Test
    fun `scalar example with non-null example does not match the given type`() {
        assertThatThrownBy { UseDefaultExample.resolveExample("example", NumberPattern(), Resolver()) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `scalar example with non-null example does not match the constraints`() {
        assertThatThrownBy { UseDefaultExample.resolveExample("example", StringPattern(maxLength = 1), Resolver()) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `resolve matching one of the given patterns with null example`() {
        val value = UseDefaultExample.resolveExample(null, listOf(StringPattern()), Resolver())
        assertThat(value).isNull()
    }

    @Test
    fun `resolve matching one of the given patterns with non-null example given patterns`() {
        val value = UseDefaultExample.resolveExample("example", listOf(StringPattern()), Resolver())
        assertThat(value?.toStringLiteral()).isEqualTo("example")
    }

    @Test
    fun `fails to match one of the given patterns with non-null example given patterns`() {
        assertThatThrownBy { UseDefaultExample.resolveExample("example", listOf(NumberPattern()), Resolver()) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `resolve matching one of the given patterns with non-null example given no patterns`() {
        assertThrows<ContractException> {
            UseDefaultExample.resolveExample("example", listOf(), Resolver())
        }
    }

    @Test
    fun `the default example for this key is not omit given no example`() {
        val boolean = UseDefaultExample.theDefaultExampleForThisKeyIsNotOmit(StringPattern())
        assertThat(boolean).isTrue()
    }

    @Test
    fun `the default example for this key is not omit given an example`() {
        val boolean = UseDefaultExample.theDefaultExampleForThisKeyIsNotOmit(StringPattern(example = "Hello World"))
        assertThat(boolean).isTrue()
    }

    @Test
    fun `the default example for this key is omit`() {
        val boolean = UseDefaultExample.theDefaultExampleForThisKeyIsNotOmit(StringPattern(example = "(omit)"))
        assertThat(boolean).isFalse()
    }

    @Test
    fun `resolve array example with null example`() {
        val value = UseDefaultExample.resolveExample(null as List<String?>?, StringPattern(), Resolver())
        assertThat(value).isNull()
    }

    @Test
    fun `resolve array example with non-null example`() {
        val value = UseDefaultExample.resolveExample(listOf("example"), StringPattern(), Resolver())
        assertThat(value).isEqualTo(JSONArrayValue(listOf(StringValue("example"))))
    }
}
