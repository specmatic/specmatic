package io.specmatic.core.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.pattern.ContractException
import io.zenwave360.jsonrefparser.`$RefParser`
import java.io.File

private const val SPECMATIC_CONFIG_VERSION = "version"

private val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

fun File.toSpecmaticConfig(): SpecmaticConfig {
    val configTree = resolveTemplates(objectMapper.readTree(this.readText()))
    return when (configTree.getVersion()) {
        SpecmaticConfigVersion.VERSION_1 -> {
            objectMapper.treeToValue(configTree, SpecmaticConfigV1::class.java).transform(this)
        }

        SpecmaticConfigVersion.VERSION_2 -> {
            objectMapper.treeToValue(configTree, SpecmaticConfigV2::class.java).transform(this)
        }

        SpecmaticConfigVersion.VERSION_3 -> {
            val resolvedValue = runCatching {
                `$RefParser`(this).parse().dereference().getRefs().schema()
            }.getOrElse { e ->
                throw ContractException("Failed to resolve references in Specmatic Config", exceptionCause = e)
            }

            val resolvedConfigTree = resolveTemplates(objectMapper.convertValue(resolvedValue, JsonNode::class.java))
            objectMapper.treeToValue(resolvedConfigTree, SpecmaticConfigV3::class.java).transform(this)
        }

        else -> {
            throw ContractException("Unsupported Specmatic config version")
        }
    }
}

private fun JsonNode.getVersion(): SpecmaticConfigVersion? {
    val versionNode = this[SPECMATIC_CONFIG_VERSION]
    val version = when {
        versionNode == null -> null
        versionNode.isInt || versionNode.isLong -> versionNode.asInt()
        versionNode.isTextual -> versionNode.asText().toIntOrNull()
        else -> null
    }
    if (version == null || version == 0) return SpecmaticConfigVersion.VERSION_1
    return SpecmaticConfigVersion.getByValue(version)
}

private fun resolveTemplates(node: JsonNode): JsonNode {
    return when {
        node.isObject ->
            node.fields().asSequence().fold(objectMapper.nodeFactory.objectNode()) { acc, entry ->
                acc.set<JsonNode>(entry.key, resolveTemplates(entry.value))
                acc
            }

        node.isArray ->
            node.elements().asSequence().fold(objectMapper.nodeFactory.arrayNode()) { acc, element ->
                acc.add(resolveTemplates(element))
                acc
            }

        node.isTextual -> resolveTemplateValue(node.asText()) ?: node
        else -> node
    }
}

private fun resolveTemplateValue(value: String): JsonNode? {
    // First, handle the existing behaviour where the entire value is a single template.
    parseTemplate(value.removePrefix("$"))?.let { template ->
        val resolved = resolveTemplateValueFromEnvOrDefault(template)
        return parseResolvedTemplateValue(resolved)
    }

    // Next, handle embedded templates like: "start-{VAR:default}-end".
    val interpolated = interpolateTemplates(value) ?: return null
    return objectMapper.nodeFactory.textNode(interpolated)
}

private data class TemplateDefinition(val keys: List<String>, val defaultValue: String)

private fun parseTemplate(value: String): TemplateDefinition? {
    if (!value.startsWith("{") || !value.endsWith("}")) return null
    val separatorIndex = value.indexOf(':')
    if (separatorIndex <= 1) return null

    val keyExpression = value.substring(1, separatorIndex)
    val keys = keyExpression.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    if (keys.isEmpty()) return null

    val defaultValue = value.substring(separatorIndex + 1, value.length - 1)
    return TemplateDefinition(keys, defaultValue)
}

private fun resolveTemplateValueFromEnvOrDefault(template: TemplateDefinition): String {
    return template.keys.asSequence()
        .mapNotNull { key -> System.getenv(key) ?: System.getProperty(key) ?: template.defaultValue }
        .firstOrNull()
        ?: template.defaultValue
}

private val TEMPLATE_REGEX = Regex("\\{([^:{}]+):([^}]*)}")

private fun interpolateTemplates(original: String): String? {
    val matches = TEMPLATE_REGEX.findAll(original).toList()
    if (matches.isEmpty()) return null

    var result = original
    // Replace from right to left to avoid messing up indices.
    for (match in matches.asReversed()) {
        val keyExpression = match.groupValues[1]
        val defaultValue = match.groupValues[2]

        val keys = keyExpression.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val resolved = keys.asSequence()
            .mapNotNull { key -> System.getenv(key) ?: System.getProperty(key) }
            .firstOrNull()
            ?: defaultValue

        result = result.replaceRange(match.range, resolved)
    }

    return result
}

private fun parseResolvedTemplateValue(value: String): JsonNode {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("\"") && trimmed.endsWith("\"") ->
            parseJsonStringValue(value) ?: objectMapper.nodeFactory.textNode(value)

        trimmed.startsWith("{") || trimmed.startsWith("[") ->
            parseStructuredValue(value) ?: objectMapper.nodeFactory.textNode(value)

        else -> objectMapper.nodeFactory.textNode(value)
    }
}

private fun parseStructuredValue(value: String): JsonNode? {
    return try {
        objectMapper.readTree(value).takeIf { it.isObject || it.isArray }
    } catch (_: Exception) {
        null
    }
}

private fun parseJsonStringValue(value: String): JsonNode? {
    return try {
        val parsed = objectMapper.readTree(value)
        if (parsed.isTextual) objectMapper.nodeFactory.textNode(parsed.asText()) else null
    } catch (_: Exception) {
        null
    }
}

fun String.getVersion(): SpecmaticConfigVersion? {
    val configTree = resolveTemplates(objectMapper.readTree(this))
    return configTree.getVersion()
}
