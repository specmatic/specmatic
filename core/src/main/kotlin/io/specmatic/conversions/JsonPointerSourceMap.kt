package io.specmatic.conversions

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.AnchorNode
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode

data class YamlNodeLocation(
    val line: Int,
    val column: Int,
    val nodeKind: String,
    val refTarget: String? = null
)

// TODO: Support JSON input (snakeyaml parses JSON-as-YAML, validate behaviour).
// TODO: Resolve external $refs (other files, URLs); currently only internal "#/..." refs are linked.
// TODO: Follow YAML anchors/aliases to a canonical location instead of indirecting through AnchorNode.
// TODO: Handle merge keys (<<) for composed objects.
class JsonPointerSourceMap(private val yaml: String) {
    fun build(): Map<String, YamlNodeLocation> {
        val root = Yaml().compose(yaml.reader()) ?: return emptyMap()
        val out = mutableMapOf<String, YamlNodeLocation>()
        walk(root, "", out)
        return out
    }

    private fun walk(node: Node, pointer: String, out: MutableMap<String, YamlNodeLocation>) {
        out[pointer] = locationOf(node, refTargetOf(node))
        when (node) {
            is MappingNode -> for (tuple in node.value) {
                val keyNode = tuple.keyNode
                if (keyNode !is ScalarNode) continue
                walk(tuple.valueNode, "$pointer/${escape(keyNode.value)}", out)
            }
            is SequenceNode -> node.value.forEachIndexed { i, child ->
                walk(child, "$pointer/$i", out)
            }
            is ScalarNode -> Unit
            is AnchorNode -> walk(node.realNode, pointer, out)
            else -> error("Unexpected YAML node type: ${node::class.java.name}")
        }
    }

    private fun refTargetOf(node: Node): String? {
        if (node !is MappingNode) return null
        for (tuple in node.value) {
            val keyNode = tuple.keyNode
            val valueNode = tuple.valueNode
            if (keyNode is ScalarNode && keyNode.value == $$"$ref" && valueNode is ScalarNode) {
                val ref = valueNode.value
                if (ref.startsWith("#/")) return ref.removePrefix("#")
                if (ref == "#") return ""
                return null
            }
        }
        return null
    }

    private fun locationOf(node: Node, refTarget: String?): YamlNodeLocation {
        val mark = node.startMark
        val kind = when (node) {
            is MappingNode -> "mapping"
            is SequenceNode -> "sequence"
            is ScalarNode -> "scalar"
            is AnchorNode -> "anchor"
            else -> error("Unexpected YAML node type: ${node::class.java.name}")
        }
        return YamlNodeLocation(mark.line + 1, mark.column + 1, kind, refTarget)
    }

    private fun escape(token: String): String =
        token.replace("~", "~0").replace("/", "~1")
}
