package io.specmatic.conversions

import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.models.media.JsonSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.reference.OpenAPI31Traverser

object SchemaUtils {
    private val openAPI31Traverser = OpenAPI31Traverser(null)

    fun refHasSiblings(schema: Schema<*>): Boolean {
        val props = Json31.mapper().convertValue(schema, MutableMap::class.java)
        props.remove("\$ref")
        return props.isNotEmpty()
    }

    fun mergeResolvedIfJsonSchema(resolvedSchema: Schema<*>, refSchema: Schema<*>): Schema<*> {
        if (resolvedSchema !is JsonSchema) return resolvedSchema
        if (!refHasSiblings(refSchema)) return resolvedSchema
        return mergeSchemas(resolvedSchema, refSchema)
    }

    fun mergeSchemas(resolvedSchema: JsonSchema, refSchema: Schema<*>): Schema<*> {
        val clone = openAPI31Traverser.deepcopy(resolvedSchema, Schema::class.java)
        openAPI31Traverser.mergeSchemas(refSchema, clone)
        clone.`$ref` = resolvedSchema.`$ref`
        clone.extensions = resolvedSchema.extensions.orEmpty().plus(refSchema.extensions.orEmpty())
        return clone
    }

    fun cloneWithType(schema: JsonSchema, targetType: String): Schema<*> {
        val clone = openAPI31Traverser.deepcopy(schema, Schema::class.java)
        clone.type = targetType
        clone.types = setOf(targetType)
        return clone
    }
}
