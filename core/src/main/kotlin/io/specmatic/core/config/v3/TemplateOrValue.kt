package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import io.specmatic.core.config.ConfigTemplateUtils

@JsonDeserialize(using = TemplateOrValueDeserializer::class)
sealed interface TemplateOrValue<out T : Any> {
    data class Value<out T : Any>(@get:JsonValue val value: T) : TemplateOrValue<T>
    data class Template(@get:JsonValue val template: String) : TemplateOrValue<Nothing> {
        init {
            ConfigTemplateUtils.validateTemplate(template).getOrElse { error ->
                throw IllegalArgumentException($$"Invalid template syntax: $$template. $${error.message}. Expected token like {VAR:default} or ${VAR:default}", error)
            }
        }
    }

    @JsonIgnore
    fun getUnsafe(): T = when (this) {
        is Value -> this.value
        else -> throw IllegalStateException("Template has not been resolved cannot return concrete value")
    }

    @JsonIgnore
    fun getOrNull(): T? = when (this) {
        is Value -> this.value
        else -> null
    }
}

private class TemplateOrValueDeserializer(private val valueType: JavaType? = null) : StdDeserializer<TemplateOrValue<*>>(TemplateOrValue::class.java), ContextualDeserializer {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TemplateOrValue<*> {
        val node = p.codec.readTree<JsonNode>(p)
        if (node.isTextual && looksLikeTemplate(node.asText())) {
            return TemplateOrValue.Template(node.asText())
        }

        val targetType = valueType ?: ctxt.constructType(Any::class.java)
        val value = p.codec.readValue<Any>(p.codec.treeAsTokens(node), targetType)
        return TemplateOrValue.Value(value)
    }

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): JsonDeserializer<*> {
        val contextualType = property?.type ?: ctxt.contextualType
        return TemplateOrValueDeserializer(contextualType?.containedTypeOrUnknown(0))
    }

    private fun looksLikeTemplate(text: String): Boolean {
        if (!text.contains('{') || !text.contains('}')) return false
        return Regex("""\$?\{[^}]*}""").containsMatchIn(text)
    }
}
