package application.backwardCompatibility

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Feature
import io.specmatic.core.Scenario
import io.specmatic.core.backwardCompatibility.FileHunks
import io.specmatic.core.backwardCompatibility.OpenApiOperationAnchors
import io.specmatic.core.backwardCompatibility.OperationChange
import io.specmatic.core.backwardCompatibility.OperationChangeKind
import io.specmatic.core.pattern.Pattern
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.File
import kotlin.collections.ArrayDeque

object OpenApiLogicalChanges {
    private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    fun compute(
        newSpecText: String,
        oldFeature: Feature?,
        newFeature: Feature,
        physical: FileHunks?,
        changedReferencedFiles: Set<String> = emptySet()
    ): List<OperationChange> {
        val anchors = OpenApiOperationAnchors.anchorsFor(newSpecText)
        val operationsReferencingChangedFiles = operationsReferencingChangedFiles(newSpecText, changedReferencedFiles)
        val oldByKey = oldFeature?.scenarios.orEmpty().groupBy { convertPathParameterStyle(it.path) to it.method.uppercase() }
        val newByKey = newFeature.scenarios.groupBy { convertPathParameterStyle(it.path) to it.method.uppercase() }
        val oldSignatures = operationSignatures(oldFeature)
        val newSignatures = operationSignatures(newFeature)
        val allKeys = (oldByKey.keys + newByKey.keys).toSortedSet(compareBy({ it.first }, { it.second }))

        return allKeys.map { (path, method) ->
            val identifier = "$method $path"
            val oldScenarios = oldByKey[path to method].orEmpty()
            val newScenarios = newByKey[path to method].orEmpty()
            val location = anchors[path to method]
            val anchor = location?.anchor

            val overlapsHunk = location != null && physical?.newHunks?.any { it.overlaps(location.lineRange) } == true
            val semanticallyDiffers =
                oldSignatures[path to method].orEmpty() != newSignatures[path to method].orEmpty() ||
                    (path to method) in operationsReferencingChangedFiles

            val kind = when {
                oldScenarios.isEmpty() -> OperationChangeKind.ADDED
                newScenarios.isEmpty() -> OperationChangeKind.REMOVED
                overlapsHunk || semanticallyDiffers -> OperationChangeKind.MODIFIED
                else -> OperationChangeKind.UNCHANGED
            }

            OperationChange(
                identifier = identifier,
                kind = kind,
                anchor = anchor,
                overlapsHunk = overlapsHunk,
                semanticallyDiffers = semanticallyDiffers
            )
        }
    }

    private fun operationSignatures(feature: Feature?): Map<Pair<String, String>, Set<String>> {
        if (feature == null) return emptyMap()

        val scenarioSignatures = feature.scenarios
            .groupBy { convertPathParameterStyle(it.path) to it.method.uppercase() }
            .mapValues { (_, scenarios) -> signatures(scenarios) }

        return scenarioSignatures
    }

    private fun signatures(scenarios: List<Scenario>): Set<String> {
        return scenarios.map { scenario ->
            buildString {
                append(scenario.method); append('|')
                append(scenario.path); append('|')
                append(scenario.requestContentType ?: ""); append('|')
                append(scenario.status); append('|')
                append(scenario.responseContentType ?: ""); append('|')
                append(scenario.name); append('|')
                append(stableString(scenario.operationMetadata)); append('|')
                append(stableString(scenario.httpRequestPattern)); append('|')
                append(stableString(scenario.httpResponsePattern)); append('|')
                append(referencedPatternsSignature(scenario))
            }
        }.toSet()
    }

    private fun referencedPatternsSignature(scenario: Scenario): String {
        val pending = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        val referenced = linkedMapOf<String, String>()

        fun enqueueFrom(text: String) {
            scenario.patterns.keys.sorted().forEach { patternName ->
                if (patternName in text && seen.add(patternName)) pending.add(patternName)
            }
        }

        enqueueFrom(stableString(scenario.httpRequestPattern))
        enqueueFrom(stableString(scenario.httpResponsePattern))

        while (pending.isNotEmpty()) {
            val patternName = pending.removeFirst()
            val pattern: Pattern = scenario.patterns[patternName] ?: continue
            val signature = stableString(pattern)
            referenced[patternName] = signature
            enqueueFrom(signature)
        }

        return referenced.entries.joinToString(separator = ";") { (key, value) -> "$key=$value" }
    }

    private fun operationsReferencingChangedFiles(
        newSpecText: String,
        changedReferencedFiles: Set<String>
    ): Set<Pair<String, String>> {
        if (changedReferencedFiles.isEmpty()) return emptySet()

        val rootMap = try {
            Yaml().compose(newSpecText.reader()) as? MappingNode ?: return emptySet()
        } catch (e: Throwable) {
            return emptySet()
        }

        val paths = mappingValueOf(rootMap, "paths") as? MappingNode ?: return emptySet()
        val operations = mutableSetOf<Pair<String, String>>()

        for (pathTuple in paths.value) {
            val path = (pathTuple.keyNode as? ScalarNode)?.value ?: continue
            val pathItem = pathTuple.valueNode as? MappingNode ?: continue
            for (resolvedPathItem in resolvePathItem(pathItem, rootMap)) {
                for (operationTuple in resolvedPathItem.value) {
                    val method = (operationTuple.keyNode as? ScalarNode)?.value?.lowercase() ?: continue
                    if (method !in HTTP_METHODS) continue

                    if (nodeReferencesChangedFile(operationTuple.valueNode, rootMap, changedReferencedFiles)) {
                        operations.add(path to method.uppercase())
                    }
                }
            }
        }

        return operations
    }

    private fun nodeReferencesChangedFile(
        node: Node,
        rootMap: MappingNode,
        changedReferencedFiles: Set<String>,
        seenLocalRefs: MutableSet<String> = mutableSetOf()
    ): Boolean {
        return when (node) {
            is MappingNode -> node.value.any { tuple ->
                val key = (tuple.keyNode as? ScalarNode)?.value
                val value = tuple.valueNode
                if (key == "\$ref") {
                    val ref = (value as? ScalarNode)?.value.orEmpty()
                    if (refMatchesChangedFile(ref, changedReferencedFiles)) return@any true
                    if (ref.startsWith("#/") && seenLocalRefs.add(ref)) {
                        val resolved = resolveLocalRef(rootMap, ref)
                        resolved != null && nodeReferencesChangedFile(resolved, rootMap, changedReferencedFiles, seenLocalRefs)
                    } else {
                        false
                    }
                } else {
                    nodeReferencesChangedFile(value, rootMap, changedReferencedFiles, seenLocalRefs)
                }
            }
            is SequenceNode -> node.value.any {
                nodeReferencesChangedFile(it, rootMap, changedReferencedFiles, seenLocalRefs)
            }
            else -> false
        }
    }

    private fun refMatchesChangedFile(ref: String, changedReferencedFiles: Set<String>): Boolean {
        val refPath = ref.substringBefore("#").substringBefore("?")
        val refFileName = File(refPath).name
        if (refFileName.isBlank()) return false

        return changedReferencedFiles.any { changedFile ->
            File(changedFile).name == refFileName
        }
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

    private fun mappingValueOf(node: MappingNode, key: String): Node? {
        return node.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode
    }

    private fun String.decodeJsonPointerToken(): String {
        return replace("~1", "/").replace("~0", "~")
    }

    private fun stableString(value: Any?): String {
        return when (value) {
            null -> ""
            is Map<*, *> -> value.entries
                .sortedBy { it.key.toString() }
                .joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
                    "${stableString(key)}=${stableString(entryValue)}"
                }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { stableString(it) }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stableString(it) }
            else -> value.toString()
        }
    }
}
