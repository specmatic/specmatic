package io.specmatic.core.backwardCompatibility

import org.eclipse.jgit.patch.Patch
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

object GitHunkParser {
    fun parse(filePath: String, unifiedDiff: String): FileHunks {
        if (unifiedDiff.isBlank()) return FileHunks(filePath = filePath, oldHunks = emptyList(), newHunks = emptyList())

        val patch = Patch()
        patch.parse(ByteArrayInputStream(unifiedDiff.toByteArray(StandardCharsets.UTF_8)))

        val oldHunks = mutableListOf<LineRange>()
        val newHunks = mutableListOf<LineRange>()

        patch.files.forEach { fileHeader ->
            fileHeader.hunks.forEach { hunk ->
                oldHunks.add(toRange(hunk.oldImage.startLine, hunk.oldImage.lineCount))
                newHunks.add(toRange(hunk.newStartLine, hunk.newLineCount))
            }
        }

        return FileHunks(filePath = filePath, oldHunks = oldHunks, newHunks = newHunks)
    }

    private fun toRange(start: Int, count: Int): LineRange {
        if (count == 0) return LineRange(start, start)
        return LineRange(start, start + count - 1)
    }
}
