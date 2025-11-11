package io.specmatic.core.jsonoperator.value

import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ValueOperatorTest {
    @Test
    fun `should be able to retrieve value when no segments are remaining`() {
        val value = StringValue("test")
        val operator = ValueOperator(value)
        val result = operator.get(emptyList()).finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(value)
    }

    @Test
    fun `should fail to retrieve value when segments are remaining`() {
        val value = StringValue("test")
        val operator = ValueOperator(value)
        val result = operator.get("/key")
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("Unexpected remaining segments")
    }

    @Test
    fun `should be able to update value when no segments are remaining`() {
        val originalValue = StringValue("old")
        val operator = ValueOperator(originalValue)
        val newValue = StringValue("new")
        val updatedOperator = operator.update(emptyList(), newValue)
        val result = updatedOperator.value.finalize()
        assertThat(result.value).isEqualTo(newValue)
    }

    @Test
    fun `should fail to update value when segments are remaining`() {
        val value = StringValue("test")
        val operator = ValueOperator(value)
        val result = operator.update("/key", StringValue("new"))
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("Unexpected remaining segments")
    }

    @Test
    fun `should be able to insert value when no segments are remaining`() {
        val originalValue = NumberValue(42)
        val operator = ValueOperator(originalValue)
        val newValue = NumberValue(100)
        val updatedOperator = operator.insert(emptyList(), newValue)
        val result = updatedOperator.value.finalize()
        assertThat(result.value).isEqualTo(newValue)
    }

    @Test
    fun `should fail to insert value when segments are remaining`() {
        val value = StringValue("test")
        val operator = ValueOperator(value)
        val result = operator.insert("/key", StringValue("new"))
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("Unexpected remaining segments")
    }

    @Test
    fun `should be able to delete value when no segments are remaining`() {
        val value = StringValue("test")
        val operator = ValueOperator(value)
        val result = operator.delete(emptyList())
        assertThat(result).isInstanceOf(HasValue::class.java)
        val optionalResult = (result as HasValue).value
        assertThat(optionalResult).isEqualTo(Optional.None)
    }

    @Test
    fun `should fail to delete value when segments are remaining`() {
        val value = StringValue("test")
        val operator = ValueOperator(value)
        val result = operator.delete("/key")
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("Unexpected remaining segments")
    }

    @Test
    fun `should finalize and return the wrapped value`() {
        val value = BooleanValue(true)
        val operator = ValueOperator(value)
        val result = operator.finalize()
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(value)
    }

    @Test
    fun `should work with different primitive value types`() {
        val testCases = listOf(
            StringValue("hello"),
            NumberValue(123),
            BooleanValue(false),
            NullValue,
        )

        testCases.forEach { testValue ->
            val operator = ValueOperator(testValue)
            val result = operator.get(emptyList()).finalizeValue()
            assertThat(result.value.getOrNull()).isEqualTo(testValue)
        }
    }

    @Test
    fun `should replace value on update regardless of type`() {
        val originalValue = StringValue("text")
        val operator = ValueOperator(originalValue)
        val newValue = NumberValue(999)
        val updatedOperator = operator.update(emptyList(), newValue)
        val result = updatedOperator.value.finalize()
        assertThat(result.value).isEqualTo(newValue)
    }
}
