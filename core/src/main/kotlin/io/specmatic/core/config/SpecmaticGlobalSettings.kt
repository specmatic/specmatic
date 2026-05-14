package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class SpecmaticGlobalSettings(
    @field:JsonDeserialize(using = ExampleTemplateStringDeserializer::class)
    val specExamplesDirectoryTemplate: TemplateOrValue<String>? = null,
    @field:JsonDeserialize(contentUsing = ExampleTemplateStringDeserializer::class)
    val sharedExamplesDirectoryTemplate: TemplateOrValue<List<TemplateOrValue<String>>>? = null
) {
    @JsonIgnore
    fun getSpecExampleDirTemplate(): String {
        if (specExamplesDirectoryTemplate != null) return specExamplesDirectoryTemplate.resolve()
        return DEFAULT_SPEC_EXAMPLE_DIR_TEMPLATE
    }

    @JsonIgnore
    fun getSharedExampleDirTemplates(): List<String> {
        if (sharedExamplesDirectoryTemplate != null) return sharedExamplesDirectoryTemplate.resolveFully()
        return defaultSharedExamplesDirTemplate
    }

    companion object {
        private const val DEFAULT_SPEC_EXAMPLE_DIR_TEMPLATE = "<SPEC_FILE_NAME>_examples"
        private val defaultSharedExamplesDirTemplate = listOf("<SPEC_EACH_PARENT>_common", "common")

        fun from(
            specExamplesDirectoryTemplate: String? = null,
            sharedExamplesDirectoryTemplate: List<String>? = null
        ) = SpecmaticGlobalSettings(
            specExamplesDirectoryTemplate = specExamplesDirectoryTemplate?.let(::wrapTemplateValue),
            sharedExamplesDirectoryTemplate = sharedExamplesDirectoryTemplate?.let(::wrapTemplateList)
        )

        private fun <T : Any> wrapTemplateValue(value: T): TemplateOrValue<T> = TemplateOrValue.Value(value)
        private fun <T : Any> wrapTemplateList(values: List<T>): TemplateOrValue<List<TemplateOrValue<T>>> = wrapTemplateValue(values.map(::wrapTemplateValue))
    }
}

class ExampleTemplateStringDeserializer : JsonDeserializer<String>() {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): String {
        val rawValue = parser.valueAsString ?: return ""
        val template = extractTemplate(rawValue)
        validateTemplate(template, parser)
        return template
    }

    private fun extractTemplate(raw: String): String {
        val start = raw.indexOf(':')
        val end = raw.lastIndexOf('}')
        return if (start != -1 && end != -1 && start < end) {
            raw.substring(start + 1, end)
        } else {
            raw
        }
    }

    private fun validateTemplate(template: String, parser: JsonParser) {
        val tokenRegex = Regex("<[^>]+>")
        val foundTokens = tokenRegex.findAll(template).map { it.value }.toSet()
        val invalidTokens = foundTokens - allowedTokens
        if (invalidTokens.isNotEmpty()) {
            throw InvalidFormatException(
                parser,
                "Invalid template variable(s): $invalidTokens, Allowed variables are $allowedTokens}",
                template,
                String::class.java
            )
        }
    }

    companion object {
        private val allowedTokens = setOf("<SPEC_FILE_NAME>", "<SPEC_EACH_PARENT>")
    }
}
