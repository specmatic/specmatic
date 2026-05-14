package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ObjectNode
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.v3.components.settings.GeneralSettings
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.ProxySettings
import io.specmatic.core.config.v3.components.settings.TestSettings

@JsonDeserialize(using = Settings.Companion.SettingsDeserializer::class)
sealed interface Settings {
    companion object {
        class SettingsDeserializer : JsonDeserializer<Settings>() {
            private val concreteKeys = setOf("general", "test", "mock", "proxy", "backwardCompatibility")
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Settings {
                val node = p.codec.readTree<ObjectNode>(p)
                val hasConcreteKey = node.fieldNames().asSequence().any { it in concreteKeys }
                return if (hasConcreteKey) {
                    ctxt.readTreeAsValue(node, ConcreteSettings::class.java)
                } else {
                    ctxt.readTreeAsValue(node, ContextDependentSettings::class.java)
                }
            }
        }
    }
}

@JsonDeserialize
data class ContextDependentSettings(@get:JsonAnyGetter val rawValue: TemplateOrValue<Map<String, TemplateOrValue<Any>>> ) : Settings {
    companion object {
        @JvmStatic
        @JsonCreator
        fun create(@JsonAnySetter raw: Map<String, TemplateOrValue<Any>>): ContextDependentSettings = ContextDependentSettings(TemplateOrValue.Value(raw))
    }
}

@JsonDeserialize
data class ConcreteSettings(
    val general: TemplateOrValue<GeneralSettings>? = null,
    val test: TemplateOrValue<TestSettings>? = null,
    val mock: TemplateOrValue<MockSettings>? = null,
    val proxy: TemplateOrValue<ProxySettings>? = null,
    val backwardCompatibility: TemplateOrValue<BackwardCompatibilityConfig>? = null,
): Settings
