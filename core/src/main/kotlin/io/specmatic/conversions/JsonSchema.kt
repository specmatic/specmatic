package io.specmatic.conversions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.toValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value
import io.swagger.v3.oas.models.media.Schema

enum class JsonSchemaType {
    // Primitives
    STRING, INTEGER, NUMBER, BOOLEAN, ENUMERABLE, CONST, NULL,

    // Formats
    EMAIL, PASSWORD, UUID, DATE, DATE_TIME, BINARY, BYTE_ARRAY,

    // Structural
    ARRAY, OBJECT, XML_ARRAY, XML_OBJECT,

    // Composite
    ALL_OF, ONE_OF, ANY_OF, MULTI_TYPE,

    // Special
    REFERENCE,

    // Fallback
    ANY_VALUE, UNRECOGNIZABLE
}

open class JsonSchema(open val schema: Schema<*>, open val type: JsonSchemaType) {
    val isNullable: Boolean
        get() {
            if (schema.nullable != null) return schema.nullable
            val types = schema.types ?: setOfNotNull(schema.type)
            return types.contains(NULL_TYPE)
        }

    val firstExampleAsJsonNode: JsonNode?
        get() = schema.examples?.firstOrNull()?.toJsonNode() ?: schema.example?.toJsonNode()

    val firstExampleString: String?
        get() {
            val example = schema.examples?.firstNotNullOfOrNull { it } ?: schema.example ?: return null
            return example.let(::toValue).let(Value::toUnformattedString)
        }

    companion object {
        private val jsonMapper = ObjectMapper().registerKotlinModule()
        const val NULL_TYPE = "null"

        fun from(schema: Schema<*>): JsonSchema {
            if (schema.`$ref` != null) return ReferenceJsonSchema(schema, schema.`$ref`)
            if (schema.enum != null) return JsonSchema(schema, JsonSchemaType.ENUMERABLE)

            return when (schema) {
                // Primitives
                is io.swagger.v3.oas.models.media.StringSchema -> JsonSchema(schema, JsonSchemaType.STRING)
                is io.swagger.v3.oas.models.media.IntegerSchema -> JsonSchema(schema, JsonSchemaType.INTEGER)
                is io.swagger.v3.oas.models.media.NumberSchema -> JsonSchema(schema, JsonSchemaType.NUMBER)
                is io.swagger.v3.oas.models.media.BooleanSchema -> JsonSchema(schema, JsonSchemaType.BOOLEAN)

                // Formats
                is io.swagger.v3.oas.models.media.EmailSchema -> JsonSchema(schema, JsonSchemaType.EMAIL)
                is io.swagger.v3.oas.models.media.PasswordSchema -> JsonSchema(schema, JsonSchemaType.PASSWORD)
                is io.swagger.v3.oas.models.media.UUIDSchema -> JsonSchema(schema, JsonSchemaType.UUID)
                is io.swagger.v3.oas.models.media.DateSchema -> JsonSchema(schema, JsonSchemaType.DATE)
                is io.swagger.v3.oas.models.media.DateTimeSchema -> JsonSchema(schema, JsonSchemaType.DATE_TIME)
                is io.swagger.v3.oas.models.media.BinarySchema -> JsonSchema(schema, JsonSchemaType.BINARY)
                is io.swagger.v3.oas.models.media.ByteArraySchema -> JsonSchema(schema, JsonSchemaType.BYTE_ARRAY)

                // Structural
                is io.swagger.v3.oas.models.media.ObjectSchema,
                is io.swagger.v3.oas.models.media.MapSchema -> {
                    resolveXmlOrSelf(schema, JsonSchemaType.OBJECT, JsonSchemaType.XML_OBJECT)
                }
                is io.swagger.v3.oas.models.media.ArraySchema -> {
                    resolveXmlOrSelf(schema, JsonSchemaType.ARRAY, JsonSchemaType.XML_ARRAY)
                }

                // Composite
                is io.swagger.v3.oas.models.media.JsonSchema -> fromJsonSchema(schema)
                is io.swagger.v3.oas.models.media.ComposedSchema -> {
                    when {
                        schema.allOf != null -> JsonSchema(schema, JsonSchemaType.ALL_OF)
                        schema.oneOf != null -> JsonSchema(schema, JsonSchemaType.ONE_OF)
                        schema.anyOf != null -> JsonSchema(schema, JsonSchemaType.ANY_OF)
                        else -> throw ContractException("Invalid Composed Schema, must have oneOf, allOf or anyOf defined")
                    }
                }
                else -> fromUnspecified(schema, emptySet())
            }
        }

        private fun fromJsonSchema(schema: io.swagger.v3.oas.models.media.JsonSchema): JsonSchema {
            if (schema.`$ref` != null) return ReferenceJsonSchema(schema, schema.`$ref`)
            if (schema.allOf != null) return JsonSchema(schema, JsonSchemaType.ALL_OF)
            if (schema.oneOf != null) return JsonSchema(schema, JsonSchemaType.ONE_OF)
            if (schema.anyOf != null) return JsonSchema(schema, JsonSchemaType.ANY_OF)
            if (schema.const != null) return ConstValueSchema(schema, toValue(schema.const))

            val declaredTypes = schema.types ?: setOfNotNull(schema.type)
            val effectiveTypes = declaredTypes.filter { it != NULL_TYPE }

            if (effectiveTypes.size > 1) return MultiTypeJsonSchema(schema, effectiveTypes.toList())
            if (schema.enum != null) return JsonSchema(schema, JsonSchemaType.ENUMERABLE)

            return when (effectiveTypes.firstOrNull()) {
                // Primitives
                "string" -> when (schema.format) {
                    "email" -> JsonSchema(schema, JsonSchemaType.EMAIL)
                    "password" -> JsonSchema(schema, JsonSchemaType.PASSWORD)
                    "uuid" -> JsonSchema(schema, JsonSchemaType.UUID)
                    "date" -> JsonSchema(schema, JsonSchemaType.DATE)
                    "date-time" -> JsonSchema(schema, JsonSchemaType.DATE_TIME)
                    "binary" -> JsonSchema(schema, JsonSchemaType.BINARY)
                    "byte" -> JsonSchema(schema, JsonSchemaType.BYTE_ARRAY)
                    else -> JsonSchema(schema, JsonSchemaType.STRING)
                }
                "integer" -> JsonSchema(schema, JsonSchemaType.INTEGER)
                "number" -> JsonSchema(schema, JsonSchemaType.NUMBER)
                "boolean" -> JsonSchema(schema, JsonSchemaType.BOOLEAN)

                // Structural
                "array" -> resolveXmlOrSelf(schema, JsonSchemaType.ARRAY, JsonSchemaType.XML_ARRAY)
                "object" -> resolveXmlOrSelf(schema, JsonSchemaType.OBJECT, JsonSchemaType.XML_OBJECT)
                else -> fromUnspecified(schema, declaredTypes)
            }
        }

        private fun resolveXmlOrSelf(schema: Schema<*>, default: JsonSchemaType, xmlType: JsonSchemaType): JsonSchema {
            val type = if (schema.xml?.name != null) xmlType else default
            return JsonSchema(schema, type)
        }

        private fun fromUnspecified(schema: Schema<*>, types: Set<String>): JsonSchema {
            val isSchemaNullable = schema.nullable == true && schema.additionalProperties == null && schema.`$ref` == null
            val schemaHasNullableType = types.contains(NULL_TYPE)
            if (isSchemaNullable || schemaHasNullableType) return JsonSchema(Schema<Any>(), JsonSchemaType.NULL)

            val hasProperties = !schema.properties.isNullOrEmpty()
            val hasAdditionalProps = schema.additionalProperties != null && schema.additionalProperties != false
            if (hasProperties || hasAdditionalProps) return JsonSchema(schema, JsonSchemaType.OBJECT)

            return when {
                // TODO: Add explicit extension while pre-processing to guarantee a const: null
                schema is io.swagger.v3.oas.models.media.JsonSchema -> ConstValueSchema(schema, NullValue)
                else -> {
                    logger.log("Unrecognized schema type: ${schema::class}, defaulting to AnyValue")
                    JsonSchema(schema, JsonSchemaType.ANY_VALUE)
                }
            }
        }

        private fun Any.toJsonNode(): JsonNode {
            return jsonMapper.valueToTree(this)
        }
    }
}

class ReferenceJsonSchema(schema: Schema<*>, val ref: String) : JsonSchema(schema, JsonSchemaType.REFERENCE)
class ConstValueSchema(schema: Schema<*>, val value: Value) : JsonSchema(schema, JsonSchemaType.CONST)
class MultiTypeJsonSchema(override val schema: io.swagger.v3.oas.models.media.JsonSchema, val types: List<String>) : JsonSchema(schema, JsonSchemaType.MULTI_TYPE)

fun Schema<*>.classify(): JsonSchema = JsonSchema.from(this)
fun Schema<*>.classifyXml(): JsonSchema {
    val classified = JsonSchema.from(this)
    return when (classified.type) {
        JsonSchemaType.OBJECT -> JsonSchema(classified.schema, JsonSchemaType.XML_OBJECT)
        JsonSchemaType.ARRAY -> JsonSchema(classified.schema, JsonSchemaType.XML_ARRAY)
        else -> classified
    }
}
