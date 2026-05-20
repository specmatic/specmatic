package io.specmatic.core.config

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.pattern.ContractException
import java.io.File

private const val SPECMATIC_CONFIG_VERSION = "version"

private val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

internal fun File.toResolvedSpecmaticConfigTree(): JsonNode {
    return resolveTemplates(objectMapper.readTree(this.readText()))
}

internal fun File.toResolvedSpecmaticConfigMap(): Map<String, Any> {
    return objectMapper.treeToValue(
        toResolvedSpecmaticConfigTree(),
        object : TypeReference<Map<String, Any>>() {}
    )
}

fun File.toSpecmaticConfig(): SpecmaticConfig {
    val configTree = toResolvedSpecmaticConfigTree()
    return configTree.toSpecmaticConfig(this)
}

fun File.toTemplateAwareSpecmaticConfig(): SpecmaticConfig {
    val rawConfigTree = objectMapper.readTree(this.readText())
    val configTree = resolveTemplates(rawConfigTree, ::isTemplatableConfigPath)
    val templateMetadata = ConfigTemplateMetadata.from(rawConfigTree)
    return configTree.toSpecmaticConfig(this, templateMetadata)
}

private fun JsonNode.toSpecmaticConfig(
    file: File,
    templateMetadata: ConfigTemplateMetadata = ConfigTemplateMetadata.empty(),
): SpecmaticConfig {
    return when (getVersion()) {
        SpecmaticConfigVersion.VERSION_1 -> {
            objectMapper.treeToValue(this, SpecmaticConfigV1::class.java)
                .transform(file)
                .copy(configTemplateMetadata = templateMetadata)
        }

        SpecmaticConfigVersion.VERSION_2 -> {
            objectMapper.treeToValue(this, SpecmaticConfigV2::class.java)
                .transform(file)
                .copy(configTemplateMetadata = templateMetadata)
        }

        SpecmaticConfigVersion.VERSION_3 -> {
            objectMapper.treeToValue(this, SpecmaticConfigV3::class.java)
                .copy(configTemplateMetadata = templateMetadata.useSourcePathsAsV3TargetPaths())
                .transform(file)
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

internal fun resolveTemplates(node: JsonNode): JsonNode {
    return resolveTemplates(node = node, shouldPreserveTemplateAt = { false })
}

private fun resolveTemplates(
    node: JsonNode,
    shouldPreserveTemplateAt: (List<String>) -> Boolean,
    path: List<String> = emptyList(),
): JsonNode {
    return when {
        node.isObject ->
            node.fields().asSequence().fold(objectMapper.nodeFactory.objectNode()) { acc, entry ->
                acc.set<JsonNode>(entry.key, resolveTemplates(entry.value, shouldPreserveTemplateAt, path + entry.key))
                acc
            }

        node.isArray ->
            node.elements().asSequence().withIndex().fold(objectMapper.nodeFactory.arrayNode()) { acc, element ->
                acc.add(resolveTemplates(element.value, shouldPreserveTemplateAt, path + element.index.toString()))
                acc
            }

        node.isTextual && shouldPreserveTemplateAt(path) -> node
        node.isTextual -> resolveTemplateValue(node.asText()) ?: node
        else -> node
    }
}

private fun isTemplatableConfigPath(path: List<String>): Boolean {
    return isContractConfigPath(path) || isStubConfigurationPath(path)
}

private fun isContractConfigPath(path: List<String>): Boolean {
    if (path.firstOrNull() != "contracts") return false
    val configIndex = path.indexOf("config")
    if (configIndex < 3) return false
    return path.getOrNull(configIndex - 2) in setOf("provides", "consumes")
}

private fun isStubConfigurationPath(path: List<String>): Boolean {
    if (path.size != 2 || path.first() != "stub") return false
    return path.last() in setOf(
        "generative",
        "delayInMilliseconds",
        "dictionary",
        "includeMandatoryAndRequestedKeysInResponse",
        "startTimeoutInMilliseconds",
        "hotReload",
        "strictMode",
        "baseUrl",
        "customImplicitStubBase",
        "filter",
        "gracefulRestartTimeoutInMilliseconds",
        "lenientMode",
    )
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
        .map { key -> System.getenv(key) ?: System.getProperty(key) ?: template.defaultValue }
        .firstOrNull()
        ?: template.defaultValue
}

private fun interpolateTemplates(original: String): String? {
    val matches = ConfigTemplateUtils.VARIABLE_TOKEN_REGEX.findAll(original).toList()
    if (matches.isEmpty()) return null

    var result = original
    // Replace from right to left to avoid messing up indices.
    for (match in matches.asReversed()) {
        val keyExpression = match.groupValues[1]
        val defaultValue = match.groupValues[2]
        val keys = keyExpression.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val resolved = keys.firstNotNullOfOrNull { key -> System.getenv(key) ?: System.getProperty(key) } ?: defaultValue
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
