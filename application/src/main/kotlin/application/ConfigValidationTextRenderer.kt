package application

import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationResult
import io.specmatic.core.config.validation.ConfigValidationSeverity
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.AnchorNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.SequenceNode

class ConfigValidationTextRenderer {
    fun render(fileName: String, source: String, result: ConfigValidationResult): String {
        return when (result) {
            is ConfigValidationResult.Valid -> "Configuration is valid: $fileName"
            is ConfigValidationResult.Invalid -> renderInvalid(fileName, source, result.output)
        }
    }

    private fun renderInvalid(fileName: String, source: String, outputs: List<ConfigValidationOutput>): String {
        val root = runCatching { Yaml().compose(source.reader()) }.getOrNull()
        return buildList {
            add("Configuration is invalid: $fileName")
            add("")
            outputs.forEachIndexed { index, output ->
                if (index > 0) {
                    add("")
                    add("")
                }
                add(renderDiagnostic(fileName, root, output))
            }
            add("")
            add(summary(outputs))
        }.joinToString("\n").trimEnd()
    }

    private fun renderDiagnostic(fileName: String, root: Node?, output: ConfigValidationOutput, depth: Int = 0): String {
        val mark = root?.locate(output.instanceLocation)?.markFor(output)
        val message = output.error ?: "Configuration validation failed."
        val indentation = "  ".repeat(depth)
        val location = if (mark == null) {
            "$fileName:${output.instanceLocation.ifBlank { "/" }}"
        } else {
            "$fileName:${mark.line + 1}:${mark.column + 1}\n${snippet(mark)}"
        }

        val current = buildList {
            add(location)
            if (mark != null) add("")
            add("  $message")
            help(output)?.let { add("  $it") }
        }.joinToString("\n").indent(indentation)

        return buildList {
            add(current)
            if (output.details.isNotEmpty()) {
                add("")
                add("${indentation}Validation details:")
                output.details.forEach { detail ->
                    add(renderDiagnostic(fileName, root, detail, depth + 1))
                }
            }
        }.joinToString("\n")
    }

    private fun String.indent(indentation: String): String = lines().joinToString("\n") {
        if (it.isBlank()) it else indentation + it
    }

    private fun snippet(mark: Mark): String {
        val lines = mark._snippet.trimEnd().lines()
        val lineNumber = mark.line + 1
        val gutter = lineNumber.toString()

        return lines.mapIndexed { index, line ->
            if (index == 0) "$gutter | $line" else "${" ".repeat(gutter.length)} | $line"
        }.joinToString("\n")
    }

    private fun help(output: ConfigValidationOutput): String? {
        val metadata = output.metadata ?: return null
        if (output.severity == ConfigValidationSeverity.WARNING) {
            return metadata.deprecationMessage?.let { "Deprecated: $it" }
        }

        return listOfNotNull(
            metadata.title?.takeIf { it.isNotBlank() && it != output.error },
            metadata.description?.takeIf { it.isNotBlank() && it != output.error },
        ).joinToString(" — ").ifBlank { null }
    }

    private fun summary(outputs: List<ConfigValidationOutput>): String {
        val errors = outputs.count { it.severity == ConfigValidationSeverity.ERROR }
        val warnings = outputs.count { it.severity == ConfigValidationSeverity.WARNING }
        return listOfNotNull(
            "$errors error${if (errors == 1) "" else "s"}".takeIf { errors > 0 },
            "$warnings warning${if (warnings == 1) "" else "s"}".takeIf { warnings > 0 },
        ).joinToString(", ")
    }

    private fun Node.locate(pointer: String): LocatedNode? {
        var current = unwrap()
        var located = LocatedNode(current)
        if (pointer.isBlank()) return located

        pointer.removePrefix("/").split("/").map(::unescape).forEach { segment ->
            located = when (current) {
                is MappingNode -> {
                    val entry = current.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == segment } ?: return null
                    current = entry.valueNode.unwrap()
                    LocatedNode(current, entry.keyNode)
                }

                is SequenceNode -> {
                    current = current.value.getOrNull(segment.toIntOrNull() ?: return null)?.unwrap() ?: return null
                    LocatedNode(current)
                }

                else -> return null
            }
        }

        return located
    }

    private fun Node.unwrap(): Node {
        return if (this is AnchorNode) realNode.unwrap() else this
    }

    private fun unescape(value: String): String {
        return value.replace("~1", "/").replace("~0", "~")
    }
}

private data class LocatedNode(val value: Node, val key: Node? = null) {
    fun markFor(output: ConfigValidationOutput): Mark {
        return if (key != null && isPropertyDiagnostic(output)) key.startMark else value.startMark
    }

    private fun isPropertyDiagnostic(output: ConfigValidationOutput): Boolean {
        val keyword = output.metadata?.keyword ?: output.keywordLocation.substringAfterLast('/')
        return output.metadata?.deprecated == true || keyword in PROPERTY_KEYWORDS
    }

    private companion object {
        val PROPERTY_KEYWORDS = setOf(
            "additionalProperties",
            "unevaluatedProperties",
            "propertyNames",
        )
    }
}
