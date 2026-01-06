package io.specmatic.core.jsonoperator.value

import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObjectValueOperatorTest {
    @Test
    fun `should be able to retrieve values at valid keys using pointers`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30, "city": "NYC"}""")
        val operator = ObjectValueOperator.from(value)

        assertThat(value.jsonObject.keys).allSatisfy { key ->
            val valueAtKey = operator.get("/$key").finalizeValue()
            assertThat(valueAtKey.value.getOrNull()).isEqualTo(value.jsonObject[key])
        }
    }

    @Test
    fun `should be able to update a value at specified valid key`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30, "city": "NYC"}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.update("/name", StringValue("Jane"))
        val valueAtKey = updatedOperator.value.get("/name").finalizeValue()
        assertThat(valueAtKey.value.getOrNull()).isEqualTo(StringValue("Jane"))
    }

    @Test
    fun `should fail update if the specified key does not exist`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30}""")
        val operator = ObjectValueOperator.from(value)
        val returnValue = operator.update("/country", StringValue("USA"))
        assertThat(returnValue).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `should be able to insert values for new keys using insert operation`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.insert("/country", StringValue("USA"))
        val valueAtKey = updatedOperator.value.get("/country").finalizeValue()
        assertThat(valueAtKey.value.getOrNull()).isEqualTo(StringValue("USA"))
    }

    @Test
    fun `should be able to insert values for existing keys using insert operation`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.insert("/name", StringValue("Jane"))
        val valueAtKey = updatedOperator.value.get("/name").finalizeValue()
        assertThat(valueAtKey.value.getOrNull()).isEqualTo(StringValue("Jane"))
    }

    @Test
    fun `should be able to delete values at specified valid key`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30, "city": "NYC"}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.delete("/age")
        val finalizedValue = updatedOperator.value.getOrThrow().finalize()
        val jsonObject = finalizedValue.value.jsonObject
        assertThat(jsonObject.containsKey("age")).isFalse()
        assertThat(jsonObject.containsKey("name")).isTrue()
        assertThat(jsonObject.containsKey("city")).isTrue()
    }

    @Test
    fun `should return failure when trying to delete values at non-existent key`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.delete("/country")
        assertThat(updatedOperator).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `should work when inserting into a key that doesn't exist with more segments remaining`() {
        val value = parsedJSONObject("""{"name": "John"}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.insert("/address/street/name", StringValue("Main St"))
        val updatedValue = updatedOperator.value.get("/address/street/name").finalizeValue()
        assertThat(updatedValue.value.getOrThrow()).isEqualTo(StringValue("Main St"))
    }

    @Test
    fun `should work with nested object updates`() {
        val value = parsedJSONObject("""{"person": {"name": "John", "age": 30}}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.update("/person/name", StringValue("Jane"))
        val valueAtKey = updatedOperator.value.get("/person/name").finalizeValue()
        assertThat(valueAtKey.value.getOrThrow()).isEqualTo(StringValue("Jane"))
    }

    @Test
    fun `should work with nested object deletes`() {
        val value = parsedJSONObject("""{"person": {"name": "John", "age": 30, "city": "NYC"}}""")
        val operator = ObjectValueOperator.from(value)
        val updatedOperator = operator.delete("/person/age")
        val updatedValue = updatedOperator.value.getOrThrow().get("/person").finalizeValue()
        val personObject = (updatedValue.value.getOrThrow() as JSONObjectValue).jsonObject
        assertThat(personObject.containsKey("age")).isFalse()
        assertThat(personObject.containsKey("name")).isTrue()
    }

    @Test
    fun `should be able to retrieve nested values using pointers`() {
        val value = parsedJSONObject("""{"person": {"name": "John", "address": {"city": "NYC"}}}""")
        val operator = ObjectValueOperator.from(value)
        val valueAtKey = operator.get("/person/address/city").finalizeValue()
        assertThat(valueAtKey.value.getOrThrow()).isEqualTo(StringValue("NYC"))
    }

    @Test
    fun `should be able to operate with case-insensitivity keys when caseInsensitive flag is enabled`() {
        val value = parsedJSONObject("""{"name": "John", "age": 30, "city": "NYC"}""")
        val operator = ObjectValueOperator.from(value).copy(caseInsensitive = true)

        assertThat(value.jsonObject.keys).allSatisfy { key ->
            val valueAtKey = operator.get("/${key.uppercase()}").finalizeValue()
            assertThat(valueAtKey.value.getOrNull()).isEqualTo(value.jsonObject[key])
        }

        assertThat(value.jsonObject.keys).allSatisfy { key ->
            val updatedOperator = operator.update("/${key.uppercase()}", StringValue("TODO")).value
            val valueAtKey = updatedOperator.get("/$key").finalizeValue()
            assertThat(valueAtKey.value.getOrNull()).isEqualTo(StringValue("TODO"))
        }

        assertThat(value.jsonObject.keys).allSatisfy { key ->
            val updatedOperator = operator.delete("/${key.uppercase()}").value.getOrThrow()
            val valueAtKey = updatedOperator.get("/$key").finalizeValue()
            assertThat(valueAtKey.value.getOrNull()).isNull()
        }
    }

    @Test
    fun `should retain casing for any original and new keys even in caseInsensitive mode`() {
        val value = parsedJSONObject("""{"Name": "John", "aGe": 30, "CiTy": "NYC"}""")
        val operator = ObjectValueOperator.from(value).copy(caseInsensitive = true)
        val updatedOperator = operator.insert("/NewKey", StringValue("TODO")).value
        val finalObj = updatedOperator.finalize().value as JSONObjectValue
        assertThat(finalObj.jsonObject.keys).containsExactlyInAnyOrder("Name", "aGe", "CiTy", "NewKey")
    }
}
