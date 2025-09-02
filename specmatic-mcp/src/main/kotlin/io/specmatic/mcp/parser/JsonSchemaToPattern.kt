package io.specmatic.mcp.parser

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.pattern.AdditionalProperties
import io.specmatic.core.pattern.BooleanPattern
import io.specmatic.core.pattern.EmailPattern
import io.specmatic.core.pattern.EnumPattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.NullPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedPattern
import io.specmatic.core.pattern.parsedValue
import java.math.BigDecimal
import java.util.stream.IntStream.IntMapMultiConsumer

class JsonSchemaToPattern(
    private val schema: Map<String, Any>
) {
    constructor(schemaNode: JsonNode) : this(schemaNode.toMap())

    fun pattern(): Map<String, Pattern> {
        return patternFrom(schema, schema)
    }

    private fun patternFrom(schema: Map<String, Any>, rootSchema: Map<String, Any>): Map<String, Pattern> {
        val type = rootSchema["type"]
        if (type != "object") {
            throw IllegalArgumentException("Root schema must be an object type")
        }
        val properties = schema["properties"]
        val requiredList = schema["required"] as? List<*> ?: emptyList<Any>()
        val requiredSet = requiredList.filterIsInstance<String>().toSet()
        val result = mutableMapOf<String, Pattern>()

        if (properties is Map<*, *>) {
            for ((key, value) in properties) {
                if (key is String && value is Map<*, *>) {
                    val propertySchema = value as? Map<String, Any> ?: continue
                    val isRequired = requiredSet.contains(key)
                    val patternValue = createPatternFromSchema(propertySchema, rootSchema)

                    if (isRequired) {
                        result[key] = patternValue
                    } else {
                        result["$key?"] = patternValue
                    }
                }
            }
        }
        return result
    }

    private fun createPatternFromSchema(schema: Map<String, Any>, rootSchema: Map<String, Any>): Pattern {
        val ref = schema["\$ref"] as? String
        if (ref != null) {
            val resolvedSchema = resolveRef(ref, rootSchema)
            return createPatternFromSchema(resolvedSchema, rootSchema)
        }

        val propertyType = schema["type"]
        return when (propertyType) {
            "string" -> {
                when {
                    schema["format"] == "email" -> {
                        EmailPattern(
                            minLength = (schema["minLength"] as? Int),
                            maxLength = (schema["maxLength"] as? Int),
                        )
                    }
                    schema.containsKey("enum") -> {
                        val enumValues = (schema["enum"] as List<*>).filterIsInstance<String>()
                        EnumPattern(values = enumValues.map { parsedValue(it) })
                    }
                    else -> {
                        StringPattern(
                            minLength = (schema["minLength"] as? Int),
                            maxLength = (schema["maxLength"] as? Int),
                            regex = (schema["pattern"] as? String),
                        )
                    }
                }
            }
            "integer", "number" -> {
                NumberPattern(
                    minLength = (schema["minLength"] as? Int) ?: 1,
                    maxLength = (schema["maxLength"] as? Int) ?: Int.MAX_VALUE,
                    minimum = schema["minimum"]?.let { BigDecimal(it.toString()) },
                    maximum = schema["maximum"]?.let { BigDecimal(it.toString()) }
                )
            }
            "boolean" -> BooleanPattern()
            "array" -> {
                val itemsSchema = schema["items"]
                if (itemsSchema is Map<*, *>) {
                    val itemPattern = createPatternFromSchema(itemsSchema as Map<String, Any>, rootSchema)
                    ListPattern(itemPattern)
                } else {
                    ListPattern(parsedPattern("any"))
                }
            }
            "object" -> {
                JSONObjectPattern(
                    patternFrom(schema, rootSchema),
                    minProperties = (schema["minProperties"] as? Int),
                    maxProperties = (schema["maxProperties"] as? Int)
                )
            }
            else -> NullPattern
        }
    }

    private fun resolveRef(ref: String, rootSchema: Map<String, Any>): Map<String, Any> {
        if (ref.startsWith("#/")) {
            val path = ref.substring(2).split("/")
            var current: Any = rootSchema

            for (segment in path) {
                current = when (current) {
                    is Map<*, *> -> current[segment] ?: throw IllegalArgumentException("Reference path not found: $ref")
                    else -> throw IllegalArgumentException("Invalid reference path: $ref")
                }
            }

            return current as? Map<String, Any>
                ?: throw IllegalArgumentException("Reference does not point to a valid schema: $ref")
        }

        throw IllegalArgumentException("Unsupported reference format: $ref")
    }

}

fun JsonNode.toMap(): Map<String, Any> {
    return ObjectMapper().convertValue(
        this,
        object : TypeReference<Map<String, Any>>() {}
    )
}
