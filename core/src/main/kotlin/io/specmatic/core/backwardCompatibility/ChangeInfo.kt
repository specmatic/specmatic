package io.specmatic.core.backwardCompatibility

data class LineRange(val startLine: Int, val endLine: Int) {
    fun overlaps(other: LineRange): Boolean = startLine <= other.endLine && other.startLine <= endLine
}

data class FileHunks(
    val filePath: String,
    val oldHunks: List<LineRange>,
    val newHunks: List<LineRange>
)

data class OperationAnchor(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int = startLine
)

enum class OperationChangeKind { ADDED, REMOVED, MODIFIED, UNCHANGED }

data class OperationChange(
    val identifier: String,
    val kind: OperationChangeKind,
    val anchor: OperationAnchor?,
    val overlapsHunk: Boolean,
    val semanticallyDiffers: Boolean
)

data class ChangeInfo(
    val physical: FileHunks?,
    val logical: List<OperationChange>
)
