package io.specmatic.conversions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.toValue
import io.specmatic.core.value.Value
import io.swagger.v3.oas.models.media.Schema

sealed interface JsonSchema {
    val schema: Schema<*>

    val isNullable: Boolean
        get() {
            if (schema.nullable != null) return schema.nullable
            val types = schema.types ?: setOfNotNull(schema.type)
            return types.contains(NULL_TYPE)
        }

    val firstExampleAsJsonNode: JsonNode?
        get() {
            return schema.examples?.firstOrNull()?.toJsonNode() ?: schema.example?.toJsonNode()
        }

    val firstExampleString: String?
        get() {
            val example = schema.examples?.firstOrNull() ?: schema.example ?: return null
            return example.let(::toValue).let(Value::toUnformattedString)
        }

    companion object {
        private val jsonMapper = ObjectMapper().registerKotlinModule()
        const val NULL_TYPE = "null"

        fun from(schema: Schema<*>): JsonSchema {
            if (schema.`$ref` != null) return ReferenceSchema(schema, schema.`$ref`)
            if (schema.enum != null) return EnumerableSchema(schema)
            return when (schema) {
                // PRIMITIVES
                is io.swagger.v3.oas.models.media.StringSchema -> StringSchema(schema)
                is io.swagger.v3.oas.models.media.IntegerSchema -> IntegerSchema(schema)
                is io.swagger.v3.oas.models.media.NumberSchema -> NumberSchema(schema)
                is io.swagger.v3.oas.models.media.BooleanSchema -> BooleanSchema(schema)

                // FORMATS
                is io.swagger.v3.oas.models.media.EmailSchema -> EmailSchema(schema)
                is io.swagger.v3.oas.models.media.PasswordSchema -> PasswordSchema(schema)
                is io.swagger.v3.oas.models.media.UUIDSchema -> UUIDSchema(schema)
                is io.swagger.v3.oas.models.media.DateSchema -> DateSchema(schema)
                is io.swagger.v3.oas.models.media.DateTimeSchema -> DateTimeSchema(schema)
                is io.swagger.v3.oas.models.media.BinarySchema -> BinarySchema(schema)
                is io.swagger.v3.oas.models.media.ByteArraySchema -> ByteArraySchema(schema)

                // STRUCTURAL
                is io.swagger.v3.oas.models.media.ObjectSchema, is io.swagger.v3.oas.models.media.MapSchema -> {
                    if (schema.xml?.name != null) XmlObjectSchema(schema) else ObjectSchema(schema)
                }
                is io.swagger.v3.oas.models.media.ArraySchema -> {
                    if (schema.xml?.name != null) XmlArraySchema(schema) else ArraySchema(schema)
                }

                // COMPOSITE
                is io.swagger.v3.oas.models.media.JsonSchema -> fromJsonSchema(schema)
                is io.swagger.v3.oas.models.media.ComposedSchema -> {
                    if (schema.allOf != null) return AllOfSchema(schema)
                    if (schema.oneOf != null) return OneOfSchema(schema)
                    if (schema.anyOf != null) return AnyOfSchema(schema)
                    throw ContractException("Invalid Composed Schema, must have oneOf, allOf or anyOf defined")
                }

                else -> fromUnspecified(schema, emptySet())
            }
        }

        fun fromJsonSchema(schema: io.swagger.v3.oas.models.media.JsonSchema): JsonSchema {
            if (schema.`$ref` != null) return ReferenceSchema(schema, schema.`$ref`)
            if (schema.allOf != null) return AllOfSchema(schema)
            if (schema.oneOf != null) return OneOfSchema(schema)
            if (schema.anyOf != null) return AnyOfSchema(schema)

            val declaredTypes = schema.types ?: setOfNotNull(schema.type)
            val effectiveTypes = declaredTypes.filter { it != NULL_TYPE }

            if (effectiveTypes.size > 1) return MultiTypeSchema(schema, effectiveTypes.toList())
            if (schema.enum != null) return EnumerableSchema(schema)

            return when (effectiveTypes.firstOrNull()) {
                // PRIMITIVES
                "string" -> when (schema.format) {
                    "email" -> EmailSchema(schema)
                    "password" -> PasswordSchema(schema)
                    "uuid" -> UUIDSchema(schema)
                    "date" -> DateSchema(schema)
                    "date-time" -> DateTimeSchema(schema)
                    "binary" -> BinarySchema(schema)
                    "byte" -> ByteArraySchema(schema)
                    else -> StringSchema(schema)
                }
                "integer" -> IntegerSchema(schema)
                "number" -> NumberSchema(schema)
                "boolean" -> BooleanSchema(schema)

                // STRUCTURAL
                "array" -> if (schema.xml?.name != null) XmlArraySchema(schema) else ArraySchema(schema)
                "object" -> if (schema.xml?.name != null) XmlObjectSchema(schema) else ObjectSchema(schema)
                else -> fromUnspecified(schema, declaredTypes)
            }
        }

        fun Schema<*>.classify(): JsonSchema = from(this)

        fun Schema<*>.classifyXml(): JsonSchema = when (val type = from(this)) {
            is ObjectSchema -> XmlObjectSchema(type.schema)
            is ArraySchema -> XmlArraySchema(type.schema)
            else -> type
        }

        private fun fromUnspecified(schema: Schema<*>, types: Set<String>): JsonSchema {
            val isSchemaNullable = schema.nullable == true && schema.additionalProperties == null && schema.`$ref` == null
            val schemaHasNullableType = types.contains(NULL_TYPE)
            if (isSchemaNullable || schemaHasNullableType) return NullSchema

            val hasProperties = !schema.properties.isNullOrEmpty()
            val hasAdditionalProps = schema.additionalProperties != null && schema.additionalProperties != false
            if (hasProperties || hasAdditionalProps) return ObjectSchema(schema)

            return if (schema.javaClass.simpleName == "Schema") AnyValueSchema(schema)
            else UnrecognizableSchema(schema)
        }

        private fun Any.toJsonNode(): JsonNode {
            return this as? JsonNode ?: jsonMapper.valueToTree(this)
        }
    }
}

// Reference
data class ReferenceSchema(override val schema: Schema<*>, val ref: String) : JsonSchema

// Primitive
object NullSchema : JsonSchema { override val schema = Schema<Any>() }
data class StringSchema(override val schema: Schema<*>) : JsonSchema
data class IntegerSchema(override val schema: Schema<*>) : JsonSchema
data class NumberSchema(override val schema: Schema<*>) : JsonSchema
data class BooleanSchema(override val schema: Schema<*>) : JsonSchema
data class EnumerableSchema(override val schema: Schema<*>) : JsonSchema

// Formats
data class EmailSchema(override val schema: Schema<*>) : JsonSchema
data class PasswordSchema(override val schema: Schema<*>) : JsonSchema
data class UUIDSchema(override val schema: Schema<*>) : JsonSchema
data class DateSchema(override val schema: Schema<*>) : JsonSchema
data class DateTimeSchema(override val schema: Schema<*>) : JsonSchema
data class BinarySchema(override val schema: Schema<*>) : JsonSchema
data class ByteArraySchema(override val schema: Schema<*>) : JsonSchema

// Structural
data class ArraySchema(override val schema: Schema<*>) : JsonSchema
data class ObjectSchema(override val schema: Schema<*>) : JsonSchema
data class XmlObjectSchema(override val schema: Schema<*>) : JsonSchema
data class XmlArraySchema(override val schema: Schema<*>) : JsonSchema

// Composite
data class AllOfSchema(override val schema: Schema<*>) : JsonSchema
data class OneOfSchema(override val schema: Schema<*>) : JsonSchema
data class AnyOfSchema(override val schema: Schema<*>) : JsonSchema
data class MultiTypeSchema(override val schema: io.swagger.v3.oas.models.media.JsonSchema, val types: List<String>) : JsonSchema

// Fallbacks
data class AnyValueSchema(override val schema: Schema<*>) : JsonSchema
data class UnrecognizableSchema(override val schema: Schema<*>) : JsonSchema
