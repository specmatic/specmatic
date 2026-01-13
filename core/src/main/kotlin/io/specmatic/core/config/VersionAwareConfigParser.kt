package io.specmatic.core.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.readEnvVarOrProperty
import java.io.File

private const val SPECMATIC_CONFIG_VERSION = "version"

private val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

fun File.toSpecmaticConfig(): SpecmaticConfig {
    val configTree = resolveTemplates(objectMapper.readTree(this.readText()))
    return when (configTree.getVersion()) {
        SpecmaticConfigVersion.VERSION_1 -> {
            objectMapper.treeToValue(configTree, SpecmaticConfigV1::class.java).transform()
        }

        SpecmaticConfigVersion.VERSION_2 -> {
            objectMapper.treeToValue(configTree, SpecmaticConfigV2::class.java).transform()
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
    val template = parseTemplate(value) ?: return null
    val resolved = readEnvVarOrProperty(template.key, template.key) ?: template.defaultValue
    return objectMapper.nodeFactory.textNode(resolved)
}

private data class TemplateDefinition(val key: String, val defaultValue: String)

private fun parseTemplate(value: String): TemplateDefinition? {
    if (!value.startsWith("{") || !value.endsWith("}")) return null
    val separatorIndex = value.indexOf(':')
    if (separatorIndex <= 1) return null
    val key = value.substring(1, separatorIndex)
    val defaultValue = value.substring(separatorIndex + 1, value.length - 1)
    return if (key.isBlank()) null else TemplateDefinition(key, defaultValue)
}

fun String.getVersion(): SpecmaticConfigVersion? {
    val configTree = resolveTemplates(objectMapper.readTree(this))
    return configTree.getVersion()
}
