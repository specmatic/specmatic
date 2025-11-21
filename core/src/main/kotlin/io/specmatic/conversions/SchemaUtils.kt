package io.specmatic.conversions

import io.swagger.v3.oas.models.media.Schema
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object SchemaUtils {
    fun mergeResolvedSchema(resolvedSchema: Schema<*>, refSchema: Schema<*>): Schema<*> {
        val clone = createNewInstance(resolvedSchema)
        for (field in Schema::class.java.declaredFields) {
            if (shouldSkipField(field) || field.name == "\$ref") continue
            field.isAccessible = true
            copyOrMergeField(field, clone, resolvedSchema, refSchema)
        }

        clone.`$ref` = resolvedSchema.`$ref`
        return clone
    }

    fun cloneWithType(schema: Schema<*>, targetType: String): Schema<*> {
        val clone = createNewInstance(schema)
        for (field in Schema::class.java.declaredFields) {
            if (shouldSkipField(field)) continue
            field.isAccessible = true
            val originalValue = field.get(schema)
            if (originalValue != null) field.set(clone, deepCloneValue(originalValue))
        }

        clone.type = targetType
        clone.types = setOf(targetType)
        return clone
    }

    private fun createNewInstance(original: Schema<*>): Schema<*> {
        return original::class.java.getDeclaredConstructor().newInstance()
    }

    private fun shouldSkipField(field: Field): Boolean {
        return Modifier.isStatic(field.modifiers) || Modifier.isFinal(field.modifiers)
    }

    private fun copyOrMergeField(field: Field, target: Schema<*>, resolved: Schema<*>, ref: Schema<*>) {
        val resolvedValue = field.get(resolved)
        val refValue = field.get(ref)

        if (field.name == "extensions") {
            val mergedExtensions = mergeExtensions(resolvedValue as? Map<*, *>, refValue as? Map<*, *>)
            if (mergedExtensions != null) field.set(target, mergedExtensions)
            return
        }

        val valueToSet = refValue ?: resolvedValue
        if (valueToSet != null) field.set(target, deepCloneValue(valueToSet))
    }

    private fun mergeExtensions(base: Map<*, *>?, override: Map<*, *>?): MutableMap<*, *>? {
        if (base == null && override == null) return null
        val merged = LinkedHashMap(base ?: emptyMap())
        if (override != null) merged.putAll(override)
        return merged.ifEmpty { null }
    }

    private fun deepCloneValue(value: Any): Any {
        return when (value) {
            is MutableList<*> -> ArrayList(value)
            is MutableMap<*, *> -> LinkedHashMap(value)
            is MutableSet<*> -> LinkedHashSet(value)
            else -> value
        }
    }
}
