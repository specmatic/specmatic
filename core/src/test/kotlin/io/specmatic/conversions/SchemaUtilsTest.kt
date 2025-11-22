package io.specmatic.conversions

import io.swagger.v3.oas.models.media.JsonSchema
import io.swagger.v3.oas.models.media.Schema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SchemaUtilsTest {
    @Test
    fun `mergeResolvedSchema should prioritize refSchema values over resolvedSchema values on merge`() {
        val resolved = JsonSchema().apply {
            description = "Base Description"
            title = "Base Title"
            example = "Base Example"
        }

        val ref = JsonSchema().apply {
            description = "Override Description"
        }

        val result = SchemaUtils.mergeSchemas(resolved, ref)
        assertThat(result.description).isEqualTo("Override Description")
        assertThat(result.title).isEqualTo("Base Title")
        assertThat(result.example).isEqualTo("Base Example")
    }

    @Test
    fun `mergeResolvedSchema should not copy over the $ref field`() {
        val resolved = JsonSchema()
        val ref = JsonSchema().apply {
            `$ref` = "#/components/schemas/SomeRef"
        }

        val result = SchemaUtils.mergeSchemas(resolved, ref)
        assertThat(result.`$ref`).isNull()
    }

    @Test
    fun `mergeResolvedSchema should retain resolved schema's ref field if there was one`() {
        val resolved = JsonSchema().apply {
            `$ref` = "#/components/schemas/AnotherRef"
        }

        val ref = JsonSchema().apply {
            `$ref` = "#/components/schemas/SomeRef"
        }

        val result = SchemaUtils.mergeSchemas(resolved, ref)
        assertThat(result.`$ref`).isEqualTo("#/components/schemas/AnotherRef")
    }

    @Test
    fun `mergeResolvedSchema should merge extensions correctly`() {
        val resolved = JsonSchema().apply {
            addExtension("x-base-only", "value1")
            addExtension("x-conflict", "base-value")
        }

        val ref = JsonSchema().apply {
            addExtension("x-ref-only", "value2")
            addExtension("x-conflict", "ref-value")
        }

        val result = SchemaUtils.mergeSchemas(resolved, ref)
        val extensions = result.extensions
        assertThat(extensions).containsEntry("x-base-only", "value1")
        assertThat(extensions).containsEntry("x-ref-only", "value2")
        assertThat(extensions).containsEntry("x-conflict", "ref-value")
    }

    @Test
    fun `mergeResolvedSchema should deep clone mutable fields to prevent side effects`() {
        val originalList: MutableList<Any> = mutableListOf("A", "B")
        val resolved = JsonSchema().apply { enum = originalList }
        val ref = JsonSchema()

        @Suppress("UNCHECKED_CAST")
        val result = SchemaUtils.mergeSchemas(resolved, ref) as Schema<Any>

        result.enum.add("C")
        assertThat(resolved.enum).containsExactly("A", "B")
        assertThat(result.enum).containsExactly("A", "B", "C")
        assertThat(result.enum).isNotSameAs(resolved.enum)
    }

    @Test
    fun `cloneWithType should copy fields and update the type`() {
        val original = (JsonSchema() as Schema<*>).apply {
            type("number")
            description("An integer")
            minimum = BigDecimal("10")
        } as JsonSchema

        val result = SchemaUtils.cloneWithType(original, "string")
        assertThat(result.type).isEqualTo("string")
        assertThat(result.types).containsExactly("string")
        assertThat(result.description).isEqualTo("An integer")
        assertThat(result.minimum).isEqualTo(BigDecimal("10"))
    }
}
