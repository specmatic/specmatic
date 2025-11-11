package io.specmatic.core.jsonoperator.value

import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.jsonoperator.value.ArrayValueOperator.Companion.ALL_ELEMENTS
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.unwrapOrContractException
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArrayValueOperatorTest {
    @Test
    fun `should be able to retrieve values at valid indexed using pointers`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)

        assertThat(value.list.indices).allSatisfy { index ->
            val valueAtIndex = operator.get("/$index").finalizeValue()
            assertThat(valueAtIndex.unwrapOrContractException().getOrNull()).isEqualTo(value.list[index])
        }
    }

    @Test
    fun `should be able to update an value at specified valid index`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)

        val updatedOperator = operator.update("/2", StringValue("Second"))
        val valueAtIndex = updatedOperator.value.get("/2").finalizeValue()

        assertThat(valueAtIndex.value.getOrNull()).isEqualTo(StringValue("Second"))
    }

    @Test
    fun `should fail update if the specified index is out of bounds`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)
        val returnValue = operator.update("/5", StringValue("Fifth"))
        assertThat(returnValue).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `should be able to append values to an array using insert operation`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)

        val updatedOperator = operator.insert("/5", StringValue("Fifth"))
        val valueAtIndex = updatedOperator.value.get("/5").finalizeValue()

        assertThat(valueAtIndex.value.getOrNull()).isEqualTo(StringValue("Fifth"))
    }

    @Test
    fun `should be able to append values to an array using insert operation with APPEND special index`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)

        val appendIndex = ArrayValueOperator.APPEND.toString()
        val updatedOperator = operator.insert("/$appendIndex", StringValue("Fifth"))
        val valueAtIndex = updatedOperator.value.get("/5").finalizeValue()

        assertThat(valueAtIndex.value.getOrNull()).isEqualTo(StringValue("Fifth"))
    }

    @Test
    fun `should be able to prepend values to an array using insert operation with PREPEND special index`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)

        val appendIndex = ArrayValueOperator.PREPEND.toString()
        val updatedOperator = operator.insert("/$appendIndex", StringValue("First"))
        val valueAtIndex = updatedOperator.value.get("/0").finalizeValue()

        assertThat(valueAtIndex.value.getOrNull()).isEqualTo(StringValue("First"))
    }

    @Test
    fun `should be able to delete values at specified valid index`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)

        val updatedOperator = operator.delete("/2")
        val valueAtIndex = updatedOperator.value.getOrThrow().get("/2").finalizeValue()

        assertThat(valueAtIndex.value.getOrThrow().toStringLiteral()).isEqualTo("3")
    }

    @Test
    fun `should return failure when trying delete values at un-bounded index`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)
        val updatedOperator = operator.delete("/5")
        assertThat(updatedOperator).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `should apply underlying operation to all elements when ALL_ELEMENTS index is used`() {
        val value = parsedJSONArray("[0, 1, 2, 3, 4]")
        val operator = ArrayValueOperator.from(value)

        val updatedOperator = operator.update("/${ALL_ELEMENTS}", StringValue("NEW"))
        val updatedValue = updatedOperator.value.finalize().value as JSONArrayValue

        assertThat(updatedValue.list).allSatisfy { item ->
            assertThat(item).isEqualTo(StringValue("NEW"))
        }
    }

    @Test
    fun `should work when inserting into a index that doesn't exist with more segments remaining`() {
        val value = parsedJSONArray("[0]")
        val operator = ArrayValueOperator.from(value)

        val updatedOperator = operator.insert("/1/nestedArray/0/nestedKey", StringValue("Value"))
        val updatedValue = updatedOperator.value.get("/1/nestedArray/0/nestedKey").finalizeValue()

        assertThat(updatedValue.value.getOrThrow()).isEqualTo(StringValue("Value"))
    }
}
