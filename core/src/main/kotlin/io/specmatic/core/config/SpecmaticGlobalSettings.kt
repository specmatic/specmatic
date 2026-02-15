package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.exc.InvalidFormatException

data class SpecmaticGlobalSettings(
    @field:JsonDeserialize(using = ExampleTemplateStringDeserializer::class)
    private val specExamplesDirectoryTemplate: String? = null,
    @field:JsonDeserialize(using = ExampleTemplateStringDeserializer::class)
    private val sharedExamplesDirectoryTemplate: List<String>? = null
) {
    @JsonIgnore
    fun getSpecExampleDirTemplate(): String {
        if (specExamplesDirectoryTemplate != null) return specExamplesDirectoryTemplate
        return DEFAULT_SPEC_EXAMPLE_DIR_TEMPLATE
    }

    @JsonIgnore
    fun getSharedExampleDirTemplates(): List<String> {
        if (sharedExamplesDirectoryTemplate != null) return sharedExamplesDirectoryTemplate
        return defaultSharedExamplesDirTemplate
    }

    companion object {
        private const val DEFAULT_SPEC_EXAMPLE_DIR_TEMPLATE = "<SPEC_FILE_NAME>_examples"
        private val defaultSharedExamplesDirTemplate = listOf("<SPEC_EACH_PARENT>_common", "common")
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
