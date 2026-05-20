package io.specmatic.core.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.specmatic.core.TemplatableValue
import io.specmatic.core.config.v3.SpecmaticConfigV3

data class ConfigTemplatePath(val segments: List<String>)

data class SourceTemplateExpression(
    val path: ConfigTemplatePath,
    val value: TemplatableValue<String>,
)

data class TargetTemplateExpression(
    val path: ConfigTemplatePath,
    val value: TemplatableValue<String>,
)

data class ConfigTemplateMetadata(
    val sourceTemplates: List<SourceTemplateExpression> = emptyList(),
    val targetTemplates: List<TargetTemplateExpression> = emptyList(),
) {
    fun isEmpty(): Boolean = sourceTemplates.isEmpty() && targetTemplates.isEmpty()

    fun transferToV3(): ConfigTemplateMetadata {
        return copy(targetTemplates = emptyList())
    }

    fun sourceTemplateAt(path: List<String>): TemplatableValue<String>? {
        return sourceTemplates.firstOrNull { template -> template.path.segments == path }?.value
    }

    fun targetTemplateAt(path: List<String>): TemplatableValue<String>? {
        return targetTemplates.firstOrNull { template -> template.path.segments == path }?.value
    }

    fun transferTemplate(
        sourcePath: List<String>,
        targetPath: List<String>,
        expressionTransform: (String) -> String = { it },
        valueTransform: (String) -> String = { it },
    ): ConfigTemplateMetadata {
        val source = sourceTemplateAt(sourcePath) ?: return this
        val target = TargetTemplateExpression(
            path = ConfigTemplatePath(targetPath),
            value = TemplatableValue(
                value = valueTransform(source.value),
                template = source.template?.let(expressionTransform),
            )
        )

        return copy(targetTemplates = targetTemplates.replace(target))
    }

    fun transferTemplatesUnder(
        sourcePrefix: List<String>,
        targetPrefix: List<String>,
        suffixTransform: (List<String>) -> List<String>? = { it },
        expressionTransform: (List<String>, String) -> String = { _, expression -> expression },
        valueTransform: (List<String>, String) -> String = { _, value -> value },
    ): ConfigTemplateMetadata {
        return sourceTemplates
            .filter { source -> source.path.segments.startsWith(sourcePrefix) }
            .fold(this) { metadata, source ->
                val sourcePath = source.path.segments
                val suffix = sourcePath.drop(sourcePrefix.size)
                val targetSuffix = suffixTransform(suffix) ?: return@fold metadata
                metadata.transferTemplate(
                    sourcePath = sourcePath,
                    targetPath = targetPrefix + targetSuffix,
                    expressionTransform = { expression -> expressionTransform(suffix, expression) },
                    valueTransform = { value -> valueTransform(suffix, value) },
                )
            }
    }

    fun useSourcePathsAsV3TargetPaths(): ConfigTemplateMetadata {
        return copy(
            targetTemplates = sourceTemplates.map { source ->
                TargetTemplateExpression(path = source.path, value = source.value)
            }
        )
    }

    fun applyToV3(configTree: JsonNode) {
        targetTemplates.forEach { targetTemplate ->
            configTree.replaceScalarAt(
                path = targetTemplate.path.segments,
                expectedValue = targetTemplate.value.value,
                replacement = targetTemplate.value.template ?: return@forEach,
            )
        }
    }

    private class TemplateCollector {
        fun collect(node: JsonNode): ConfigTemplateMetadata {
            val sourceTemplates = mutableListOf<SourceTemplateExpression>()
            collectFrom(node, emptyList(), sourceTemplates)
            return ConfigTemplateMetadata(sourceTemplates = sourceTemplates)
        }

        private fun collectFrom(
            node: JsonNode,
            path: List<String>,
            sourceTemplates: MutableList<SourceTemplateExpression>,
        ) {
            when {
                node.isObject -> node.properties().asSequence().forEach { entry ->
                    collectFrom(entry.value, path + entry.key, sourceTemplates)
                }

                node.isArray -> node.elements().asSequence().forEachIndexed { index, element ->
                    collectFrom(element, path + index.toString(), sourceTemplates)
                }

                node.isTextual && ConfigTemplateUtils.isConfigTemplate(node.asText()) -> {
                    val rawText = node.asText()
                    val resolvedText = ConfigTemplateUtils.resolveTemplateValue(node).scalarValueText() ?: return
                    sourceTemplates.add(
                        SourceTemplateExpression(
                            path = ConfigTemplatePath(path),
                            value = TemplatableValue(value = resolvedText, template = rawText),
                        )
                    )
                }
            }
        }
    }

    companion object {
        fun empty(): ConfigTemplateMetadata = ConfigTemplateMetadata()

        fun from(rawConfigTree: JsonNode): ConfigTemplateMetadata {
            return TemplateCollector().collect(rawConfigTree)
        }
    }
}

private fun List<TargetTemplateExpression>.replace(target: TargetTemplateExpression): List<TargetTemplateExpression> {
    return filterNot { existing -> existing.path == target.path } + target
}

private fun List<String>.startsWith(prefix: List<String>): Boolean {
    return size >= prefix.size && take(prefix.size) == prefix
}

private fun JsonNode.replaceScalarAt(path: List<String>, expectedValue: String, replacement: String) {
    val parent = nodeAt(path.dropLast(1)) ?: return
    val leaf = path.lastOrNull() ?: return

    when (parent) {
        is ObjectNode -> {
            val current = parent.get(leaf) ?: return
            if (current.scalarValueText() == expectedValue) parent.put(leaf, replacement)
        }
        is ArrayNode -> {
            val index = leaf.toIntOrNull() ?: return
            val current = parent.get(index) ?: return
            if (current.scalarValueText() == expectedValue) parent.set(index, TextNode.valueOf(replacement))
        }
    }
}

private fun JsonNode.nodeAt(path: List<String>): JsonNode? {
    return path.fold(this as JsonNode?) { node, segment ->
        when {
            node == null -> null
            node.isArray -> segment.toIntOrNull()?.let(node::get)
            else -> node.get(segment)
        }
    }
}

private fun JsonNode.isScalarValue(): Boolean {
    return isTextual || isNumber || isBoolean
}

private fun JsonNode.scalarValueText(): String? {
    return when {
        isScalarValue() -> asText()
        else -> null
    }
}

fun SpecmaticVersionedConfig.writeYamlPreservingConfigTemplates(objectMapper: ObjectMapper): String {
    val configYaml = objectMapper.writeValueAsString(this)
    val configTree = objectMapper.readTree(configYaml)
    val templateMetadata = (this as? SpecmaticConfigV3)?.configTemplateMetadata ?: return configYaml
    templateMetadata.applyToV3(configTree)
    return objectMapper.writeValueAsString(configTree)
}
