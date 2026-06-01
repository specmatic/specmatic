package io.specmatic.conversions

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.nodes.AnchorNode
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode

enum class YamlNodeKind { MAPPING, SEQUENCE, SCALAR, ANCHOR }

data class YamlNodeLocation(
    val line: Int,
    val column: Int,
    val nodeKind: YamlNodeKind,
    val refTarget: String? = null,
    val rawRef: String? = null
)

class JsonPointerSourceMap(private val yaml: String) {
    fun build(): Map<String, YamlNodeLocation> {
        val root = Yaml().compose(yaml.reader()) ?: return emptyMap()
        val out = mutableMapOf<String, YamlNodeLocation>()
        walk(root, "", root.startMark, out)
        return out
    }

    private fun walk(node: Node, pointer: String, mark: Mark, out: MutableMap<String, YamlNodeLocation>) {
        out[pointer] = locationOf(node, mark, rawRefOf(node))
        when (node) {
            is MappingNode -> for (tuple in node.value) {
                val keyNode = tuple.keyNode
                if (keyNode !is ScalarNode) continue
                walk(tuple.valueNode, "$pointer/${escape(keyNode.value)}", keyNode.startMark, out)
            }
            is SequenceNode -> node.value.forEachIndexed { i, child ->
                walk(child, "$pointer/$i", child.startMark, out)
            }
            is ScalarNode -> Unit
            is AnchorNode -> walk(node.realNode, pointer, mark, out)
            else -> error("Unexpected YAML node type: ${node::class.java.name}")
        }
    }

    private fun rawRefOf(node: Node): String? {
        if (node !is MappingNode) return null
        for (tuple in node.value) {
            val keyNode = tuple.keyNode
            val valueNode = tuple.valueNode
            if (keyNode is ScalarNode && keyNode.value == $$"$ref" && valueNode is ScalarNode) {
                return valueNode.value
            }
        }
        return null
    }

    private fun internalRefTargetOf(rawRef: String?): String? {
        if (rawRef == null) return null
        if (rawRef.startsWith("#/")) return rawRef.removePrefix("#")
        if (rawRef == "#") return ""
        return null
    }

    private fun locationOf(node: Node, mark: Mark, rawRef: String?): YamlNodeLocation {
        val kind = when (node) {
            is MappingNode -> YamlNodeKind.MAPPING
            is SequenceNode -> YamlNodeKind.SEQUENCE
            is ScalarNode -> YamlNodeKind.SCALAR
            is AnchorNode -> YamlNodeKind.ANCHOR
            else -> error("Unexpected YAML node type: ${node::class.java.name}")
        }
        return YamlNodeLocation(mark.line + 1, mark.column + 1, kind, internalRefTargetOf(rawRef), rawRef)
    }

    private fun escape(token: String): String =
        token.replace("~", "~0").replace("/", "~1")
}
