package io.specmatic.core.backwardCompatibility

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import java.io.File

data class OpenApiOperationLocation(val anchor: OperationAnchor, val lineRange: LineRange)

object OpenApiOperationAnchors {
    private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    fun anchorsFor(specFile: File): Map<Pair<String, String>, OpenApiOperationLocation> {
        if (!specFile.exists() || !specFile.isFile) return emptyMap()
        return anchorsFor(specFile.readText())
    }

    fun anchorsFor(yamlOrJsonText: String): Map<Pair<String, String>, OpenApiOperationLocation> {
        val root = try {
            Yaml().compose(yamlOrJsonText.reader()) ?: return emptyMap()
        } catch (e: Throwable) {
            return emptyMap()
        }

        val rootMap = root as? MappingNode ?: return emptyMap()
        val paths = mappingValueOf(rootMap, "paths") as? MappingNode ?: return emptyMap()

        val raw = mutableListOf<Triple<Pair<String, String>, OperationAnchor, Int>>()
        for (tuple in paths.value) {
            val key = (tuple.keyNode as? ScalarNode)?.value ?: continue
            val pathItem = tuple.valueNode as? MappingNode ?: continue
            for (resolvedPathItem in resolvePathItem(pathItem, rootMap)) {
                for (opTuple in resolvedPathItem.value) {
                    val methodKey = (opTuple.keyNode as? ScalarNode)?.value?.lowercase() ?: continue
                    if (methodKey !in HTTP_METHODS) continue
                    val keyNode = opTuple.keyNode
                    val anchor = anchorOf(keyNode) ?: continue
                    val tentativeEnd = endLineOf(opTuple.valueNode) ?: anchor.startLine
                    raw.add(Triple(key to methodKey.uppercase(), anchor, tentativeEnd))
                }
            }
        }

        val sortedByStart = raw.sortedBy { it.second.startLine }
        val result = mutableMapOf<Pair<String, String>, OpenApiOperationLocation>()
        for ((index, entry) in sortedByStart.withIndex()) {
            val (key, anchor, tentativeEnd) = entry
            val nextStart = sortedByStart.getOrNull(index + 1)?.second?.startLine?.takeIf { it > anchor.startLine }
            val cappedEnd = if (nextStart != null) minOf(tentativeEnd, nextStart - 1) else tentativeEnd
            val end = maxOf(cappedEnd, anchor.startLine)
            result[key] = OpenApiOperationLocation(
                anchor = OperationAnchor(startLine = anchor.startLine, startColumn = anchor.startColumn, endLine = end),
                lineRange = LineRange(anchor.startLine, end)
            )
        }
        return result
    }

    private fun mappingValueOf(node: MappingNode, key: String): Node? {
        return node.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode
    }

    private fun resolvePathItem(pathItem: MappingNode, rootMap: MappingNode): List<MappingNode> {
        val ref = (mappingValueOf(pathItem, "\$ref") as? ScalarNode)?.value
        if (ref.isNullOrBlank() || !ref.startsWith("#/")) return listOf(pathItem)

        return listOfNotNull(resolveLocalRef(rootMap, ref) as? MappingNode)
    }

    private fun resolveLocalRef(rootMap: MappingNode, ref: String): Node? {
        return ref.removePrefix("#/")
            .split("/")
            .fold(rootMap as Node?) { current, token ->
                val mapping = current as? MappingNode ?: return@fold null
                mappingValueOf(mapping, token.decodeJsonPointerToken())
            }
    }

    private fun String.decodeJsonPointerToken(): String {
        return replace("~1", "/").replace("~0", "~")
    }

    private fun anchorOf(node: Node): OperationAnchor? {
        val mark = node.startMark ?: return null
        return OperationAnchor(startLine = mark.line + 1, startColumn = mark.column + 1)
    }

    private fun endLineOf(node: Node): Int? {
        val mark = node.endMark ?: return null
        val oneBased = mark.line + 1
        // snakeyaml's end mark for a block node lands at column 0 of the line after the last content line.
        // Pull it back to the actual last content line.
        return if (mark.column == 0 && oneBased > 1) oneBased - 1 else oneBased
    }
}
