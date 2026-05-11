package application.backwardCompatibility

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.Feature
import io.specmatic.core.Scenario
import io.specmatic.core.backwardCompatibility.FileHunks
import io.specmatic.core.backwardCompatibility.OpenApiOperationAnchors
import io.specmatic.core.backwardCompatibility.OperationChange
import io.specmatic.core.backwardCompatibility.OperationChangeKind

object OpenApiLogicalChanges {
    fun compute(
        newSpecText: String,
        oldFeature: Feature?,
        newFeature: Feature,
        physical: FileHunks?
    ): List<OperationChange> {
        val anchors = OpenApiOperationAnchors.anchorsFor(newSpecText)
        val oldByKey = oldFeature?.scenarios.orEmpty().groupBy { convertPathParameterStyle(it.path) to it.method.uppercase() }
        val newByKey = newFeature.scenarios.groupBy { convertPathParameterStyle(it.path) to it.method.uppercase() }
        val allKeys = (oldByKey.keys + newByKey.keys).toSortedSet(compareBy({ it.first }, { it.second }))

        return allKeys.map { (path, method) ->
            val identifier = "$method $path"
            val oldScenarios = oldByKey[path to method].orEmpty()
            val newScenarios = newByKey[path to method].orEmpty()
            val location = anchors[path to method]
            val anchor = location?.anchor

            val overlapsHunk = location != null && physical?.newHunks?.any { it.overlaps(location.lineRange) } == true
            val semanticallyDiffers = signatures(oldScenarios) != signatures(newScenarios)

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

    private fun signatures(scenarios: List<Scenario>): Set<String> {
        return scenarios.map { s ->
            buildString {
                append(s.method); append('|')
                append(s.path); append('|')
                append(s.requestContentType ?: ""); append('|')
                append(s.status); append('|')
                append(s.responseContentType ?: "")
            }
        }.toSet()
    }
}
