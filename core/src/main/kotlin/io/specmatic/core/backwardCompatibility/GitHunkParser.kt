package io.specmatic.core.backwardCompatibility

object GitHunkParser {
    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    fun parse(filePath: String, unifiedDiff: String): FileHunks {
        val oldHunks = mutableListOf<LineRange>()
        val newHunks = mutableListOf<LineRange>()
        unifiedDiff.lineSequence().forEach { line ->
            val match = HUNK_HEADER.matchEntire(line) ?: return@forEach
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifEmpty { "1" }.toInt()
            oldHunks.add(toRange(oldStart, oldCount))
            newHunks.add(toRange(newStart, newCount))
        }
        return FileHunks(filePath = filePath, oldHunks = oldHunks, newHunks = newHunks)
    }

    private fun toRange(start: Int, count: Int): LineRange {
        if (count == 0) return LineRange(start, start)
        return LineRange(start, start + count - 1)
    }
}
