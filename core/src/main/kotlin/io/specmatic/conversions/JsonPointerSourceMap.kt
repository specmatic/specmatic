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
    val nodeKind: String
)

// TODO: Support JSON input (snakeyaml parses JSON-as-YAML, validate behaviour).
// TODO: Resolve $ref pointers to their target locations.
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
        out[pointer] = locationOf(node)
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

    private fun locationOf(node: Node): YamlNodeLocation {
        val mark = node.startMark
        val kind = when (node) {
            is MappingNode -> "mapping"
            is SequenceNode -> "sequence"
            is ScalarNode -> "scalar"
            is AnchorNode -> "anchor"
            else -> error("Unexpected YAML node type: ${node::class.java.name}")
        }
        return YamlNodeLocation(mark.line + 1, mark.column + 1, kind)
    }

    private fun escape(token: String): String =
        token.replace("~", "~0").replace("/", "~1")
}
