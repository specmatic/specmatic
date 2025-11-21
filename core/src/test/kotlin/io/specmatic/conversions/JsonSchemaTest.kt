package io.specmatic.conversions

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.StringValue
import io.swagger.v3.oas.models.media.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JsonSchemaTest {
    @Nested
    inner class OpenApi30Tests {
        @Test
        fun `should classify specific primitive schemas`() {
            assertClassification(StringSchema(), JsonSchemaType.STRING)
            assertClassification(IntegerSchema(), JsonSchemaType.INTEGER)
            assertClassification(NumberSchema(), JsonSchemaType.NUMBER)
            assertClassification(BooleanSchema(), JsonSchemaType.BOOLEAN)
        }

        @Test
        fun `should classify specific format schemas`() {
            assertClassification(EmailSchema(), JsonSchemaType.EMAIL)
            assertClassification(PasswordSchema(), JsonSchemaType.PASSWORD)
            assertClassification(UUIDSchema(), JsonSchemaType.UUID)
            assertClassification(DateSchema(), JsonSchemaType.DATE)
            assertClassification(DateTimeSchema(), JsonSchemaType.DATE_TIME)
            assertClassification(BinarySchema(), JsonSchemaType.BINARY)
            assertClassification(ByteArraySchema(), JsonSchemaType.BYTE_ARRAY)
        }

        @Test
        fun `should classify structural schemas`() {
            assertClassification(ObjectSchema(), JsonSchemaType.OBJECT)
            assertClassification(MapSchema(), JsonSchemaType.OBJECT)
            assertClassification(ArraySchema(), JsonSchemaType.ARRAY)
        }

        @Test
        fun `should identify nullable correctly using the nullable property`() {
            val schema = StringSchema().apply { nullable = true }
            val jsonSchema = JsonSchema.from(schema)

            assertThat(jsonSchema.type).isEqualTo(JsonSchemaType.STRING)
            assertThat(jsonSchema.isNullable).isTrue
        }

        @Test
        fun `should classify ComposedSchema correctly`() {
            val oneOfSchema = ComposedSchema().apply { oneOf = listOf(StringSchema()) }
            assertClassification(oneOfSchema, JsonSchemaType.ONE_OF)

            val allOfSchema = ComposedSchema().apply { allOf = listOf(StringSchema()) }
            assertClassification(allOfSchema, JsonSchemaType.ALL_OF)

            val anyOfSchema = ComposedSchema().apply { anyOf = listOf(StringSchema()) }
            assertClassification(anyOfSchema, JsonSchemaType.ANY_OF)
        }

        @Test
        fun `should throw exception for invalid ComposedSchema`() {
            val invalidSchema = ComposedSchema()
            assertThatThrownBy { JsonSchema.from(invalidSchema) }
                .isInstanceOf(ContractException::class.java)
                .hasMessageContaining("Invalid Composed Schema")
        }
    }

    @Nested
    inner class OpenApi31Tests {
        @Test
        fun `should classify generic JsonSchema by type string`() {
            assertGenericClassification("string", JsonSchemaType.STRING)
            assertGenericClassification("integer", JsonSchemaType.INTEGER)
            assertGenericClassification("boolean", JsonSchemaType.BOOLEAN)
            assertGenericClassification("number", JsonSchemaType.NUMBER)
        }

        @Test
        fun `should classify const values as ConstSchema`() {
            assertConst(100, NumberValue(100))
            assertConst(99.99.toLong(), NumberValue(99.99.toLong()))
            assertConst("ConstString", StringValue("ConstString"))
            assertConst(true, BooleanValue(true))
            assertConst(false, BooleanValue(false))
            assertConst(null, NullValue)
        }

        @Test
        fun `should classify generic JsonSchema with formats`() {
            val schema = JsonSchema().apply {
                type = "string"
                format = "email"
            }
            assertClassification(schema, JsonSchemaType.EMAIL)
        }

        @Test
        fun `should identify nullable correctly using types list`() {
            val schema = JsonSchema().apply {
                types = setOf("string", "null")
            }
            val result = JsonSchema.from(schema)

            assertThat(result.type).isEqualTo(JsonSchemaType.STRING)
            assertThat(result.isNullable).isTrue
        }

        @Test
        fun `should handle single type in types list`() {
            val schema = JsonSchema().apply {
                types = setOf("integer")
            }
            assertClassification(schema, JsonSchemaType.INTEGER)
        }

        @Test
        fun `should detect MultiTypeJsonSchema when multiple non-null types exist`() {
            val schema = JsonSchema().apply {
                types = setOf("string", "integer")
            }
            val result = JsonSchema.from(schema)

            assertThat(result).isInstanceOf(MultiTypeJsonSchema::class.java)
            assertThat(result.type).isEqualTo(JsonSchemaType.MULTI_TYPE)

            val multiType = result as MultiTypeJsonSchema
            assertThat(multiType.types).containsExactlyInAnyOrder("string", "integer")
        }

        @Test
        fun `should classify generic JsonSchema structures`() {
            assertGenericClassification("object", JsonSchemaType.OBJECT)
            assertGenericClassification("array", JsonSchemaType.ARRAY)
        }

        @Test
        fun `should classify generic JsonSchema composites`() {
            val oneOf = JsonSchema().apply { oneOf = listOf(Schema<Any>()) }
            assertClassification(oneOf, JsonSchemaType.ONE_OF)

            val allOf = JsonSchema().apply { allOf = listOf(Schema<Any>()) }
            assertClassification(allOf, JsonSchemaType.ALL_OF)

            val anyOf = JsonSchema().apply { anyOf = listOf(Schema<Any>()) }
            assertClassification(anyOf, JsonSchemaType.ANY_OF)
        }

        @Test
        @Disabled // TODO: Fix this test by adding pre-process to spec and expecting a extension if const is explicitly null
        fun `should fallback to ANY_VALUE for unrecognisable schemas`() {
            assertClassification(JsonSchema(), JsonSchemaType.ANY_VALUE)
        }
    }

    @Nested
    inner class SharedBehaviorTests {
        @Test
        fun `should classify Reference schemas regardless of type`() {
            val refSchema = Schema<Any>().apply { `$ref` = "#/components/schemas/User" }
            val result = JsonSchema.from(refSchema)

            assertThat(result).isInstanceOf(ReferenceJsonSchema::class.java)
            assertThat(result.type).isEqualTo(JsonSchemaType.REFERENCE)
            assertThat((result as ReferenceJsonSchema).ref).isEqualTo("#/components/schemas/User")
        }

        @Test
        fun `should classify Enum schemas`() {
            val enumSchema = StringSchema().apply { enum = listOf("A", "B") }
            assertClassification(enumSchema, JsonSchemaType.ENUMERABLE)

            // Generic version
            val genericEnum = JsonSchema().apply { enum = listOf("A", "B") }
            assertClassification(genericEnum, JsonSchemaType.ENUMERABLE)
        }

        @Test
        fun `should classify XML structures correctly using classifyXml extension`() {
            val objectSchema = ObjectSchema().apply {
                xml = XML().apply { name = "root" }
            }

            val result = objectSchema.classifyXml()
            assertThat(result.type).isEqualTo(JsonSchemaType.XML_OBJECT)
        }

        @Test
        fun `should use XML variant even if xml properties are missing`() {
            val objectSchema = ObjectSchema()
            val result = objectSchema.classifyXml()
            assertThat(result.type).isEqualTo(JsonSchemaType.XML_OBJECT)
        }

        @Test
        fun `should fallback to ANY_VALUE for unrecognisable schemas`() {
            assertClassification(Schema<Any>(), JsonSchemaType.ANY_VALUE)
        }

        @Test
        fun `should infer NULL type for nullable schema with no other properties`() {
            val schema = Schema<Any>().apply { nullable = true }
            assertClassification(schema, JsonSchemaType.NULL)
        }
    }

    private fun assertConst(const: Any?, expected: ScalarValue) {
        val schema = JsonSchema().apply { this.const = const }
        val classification = JsonSchema.from(schema)

        assertThat(classification.type).isEqualTo(JsonSchemaType.CONST)
        assertThat((classification as ConstValueSchema).value).isEqualTo(expected)
    }

    private fun assertClassification(schema: Schema<*>, expectedType: JsonSchemaType) {
        val result = JsonSchema.from(schema)
        assertThat(result.type).isEqualTo(expectedType)
    }

    private fun assertGenericClassification(typeString: String, expectedType: JsonSchemaType) {
        val schema = JsonSchema().apply { type = typeString }
        assertClassification(schema, expectedType)
    }
}
