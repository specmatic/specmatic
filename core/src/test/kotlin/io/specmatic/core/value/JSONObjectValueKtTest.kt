package io.specmatic.core.value

import io.specmatic.core.Resolver
import org.assertj.core.api.Assertions.assertThat
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

        assertThat(result.reportString()).contains("""Expected property "age" was missing""")
        assertThat(result.reportString()).contains("""Property "name" was unexpected""")
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
}