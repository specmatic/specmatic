package io.specmatic.core.value

import io.specmatic.core.Resolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class JSONObjectValueKtTest {
    @Test
    fun `when name exists it should generate a new name`() {
        val newName = UseExampleDeclarations().getNewName("name", listOf("name"))
        assertThat(newName).isEqualTo("name_")
    }

    @Test
    fun `when name does not exist it should return the same name`() {
        val newName = UseExampleDeclarations().getNewName("name", emptyList())
        assertThat(newName).isEqualTo("name")
    }

    @Test
    fun `format for rendering a JSON object in a snippet`() {
        val value = JSONObjectValue(mapOf("id" to NumberValue(10)))
        assertThat(value.valueErrorSnippet()).startsWith("JSON object ")
    }

    @Test
    fun `checkIfAllRootLevelKeysAreAttributeSelected should return an error with the key error check list`() {
        val value = JSONObjectValue(
            mapOf(
                "id" to NumberValue(10),
                "name" to StringValue("name")
            )
        )

        val result = value.checkIfAllRootLevelKeysAreAttributeSelected(
            attributeSelectedFields = setOf("id", "age"),
            resolver = Resolver()
        )

        assertThat(result.reportString()).contains("""Expected key named "age" was missing""")
        assertThat(result.reportString()).contains("""Key named "name" was unexpected""")
    }

    @Test
    fun `checkIfAllRootLevelKeysAreAttributeSelected should return Success if all the attribute selected keys are present`() {
        val value = JSONObjectValue(
            mapOf(
                "id" to NumberValue(10),
                "name" to StringValue("name")
            )
        )

        val result = value.checkIfAllRootLevelKeysAreAttributeSelected(
            attributeSelectedFields = setOf("id", "name"),
            resolver = Resolver()
        )

        assertThat(result.isSuccess()).isTrue()
    }

    @Nested
    inner class PatchValuesIfCompatibleFromTest {
        @Test
        fun `should patch values when types are compatible`() {
            val source = JSONObjectValue(mapOf("key1" to StringValue("newValue1"), "key2" to NumberValue(42)))
            val target = JSONObjectValue(mapOf("key1" to StringValue("oldValue1"), "key2" to NumberValue(10)))
            val result = target.patchValuesIfCompatibleFrom(source)

            assertEquals(StringValue("newValue1"), result["key1"])
            assertEquals(NumberValue(42), result["key2"])
        }

        @Test
        fun `should not patch values when types are incompatible`() {
            val source = JSONObjectValue(mapOf("key1" to NumberValue(42), "key2" to StringValue("newValue2")))
            val target = JSONObjectValue(mapOf("key1" to StringValue("oldValue1"), "key2" to NumberValue(10)))
            val result = target.patchValuesIfCompatibleFrom(source)

            assertEquals(StringValue("oldValue1"), result["key1"])
            assertEquals(NumberValue(10), result["key2"])
        }

        @Test
        fun `should not patch values for missing keys in target`() {
            val source = JSONObjectValue(mapOf("key1" to StringValue("newValue1"), "key3" to NumberValue(42)))
            val target = JSONObjectValue(mapOf("key1" to StringValue("oldValue1"), "key2" to NumberValue(10)))
            val result = target.patchValuesIfCompatibleFrom(source)

            assertEquals(StringValue("newValue1"), result["key1"])
            assertEquals(NumberValue(10), result["key2"])
            assertFalse(result.containsKey("key3"))
        }

        @Test
        fun `should not patch values for non-patchable keys`() {
            val source = JSONObjectValue(mapOf("key1" to StringValue("newValue1"), "key2" to NumberValue(42)))
            val target = JSONObjectValue(mapOf("key1" to StringValue("oldValue1"), "key2" to NumberValue(10)))
            val nonPatchableKeys = setOf("key1")
            val result = target.patchValuesIfCompatibleFrom(source, nonPatchableKeys)

            assertEquals(StringValue("oldValue1"), result["key1"])
            assertEquals(NumberValue(42), result["key2"])
        }

        @Test
        fun `should not patch values when source is empty`() {
            val source = JSONObjectValue(emptyMap())
            val target = JSONObjectValue(mapOf("key1" to StringValue("oldValue1"), "key2" to NumberValue(10)))
            val result = target.patchValuesIfCompatibleFrom(source)

            assertEquals(StringValue("oldValue1"), result["key1"])
            assertEquals(NumberValue(10), result["key2"])
        }

        @Test
        fun `should return empty map when target is empty`() {
            val source = JSONObjectValue(mapOf("key1" to StringValue("newValue1"), "key2" to NumberValue(42)))
            val target = JSONObjectValue(emptyMap())
            val result = target.patchValuesIfCompatibleFrom(source)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return empty map when both source and target are empty`() {
            val source = JSONObjectValue(emptyMap())
            val target = JSONObjectValue(emptyMap())
            val result = target.patchValuesIfCompatibleFrom(source)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should patch all values when nonPatchableKeys is empty`() {
            val source = JSONObjectValue(mapOf("key1" to StringValue("newValue1"), "key2" to NumberValue(42)))
            val target = JSONObjectValue(mapOf("key1" to StringValue("oldValue1"), "key2" to NumberValue(10)))
            val result = target.patchValuesIfCompatibleFrom(source)

            assertEquals(StringValue("newValue1"), result["key1"])
            assertEquals(NumberValue(42), result["key2"])
        }
    }
}