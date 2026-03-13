package io.specmatic.core.utilities

data class SegmentCounts(val staticSegments: Int = 0, val staticChars: Int = 0, val mixedSegments: Int = 0, val dynamicSegments: Int = 0) {
    operator fun plus(other: SegmentCounts): SegmentCounts {
        return SegmentCounts(
            staticSegments = staticSegments + other.staticSegments,
            staticChars = staticChars + other.staticChars,
            mixedSegments = mixedSegments + other.mixedSegments,
            dynamicSegments = dynamicSegments + other.dynamicSegments,
        )
    }

    // Equivalent to compareByDescending { dynamicSegments }.thenByAscending { staticSegments }.thenByAscending { mixedSegments }.thenByAscending { staticChars }
    fun generalityScore(): Int =
        (dynamicSegments.coerceAtMost(255) shl 24) or
        ((255 - staticSegments.coerceAtMost(255)) shl 16) or
        ((255 - mixedSegments.coerceAtMost(255)) shl 8) or
        (255 - staticChars.coerceAtMost(255))

    // Equivalent to compareByDescending { staticSegments }.thenByDescending { mixedSegments }.thenByDescending { staticChars }.thenByAscending { dynamicSegments }
    fun specificityScore(): Int =
        (staticSegments.coerceAtMost(255) shl 24) or
        (mixedSegments.coerceAtMost(255) shl 16) or
        (staticChars.coerceAtMost(255) shl 8) or
        (255 - dynamicSegments.coerceAtMost(255))

    companion object {
        fun segmentCounts(segment: String, regex: Regex): SegmentCounts {
            val matches = regex.findAll(segment).toList()
            val dynamicChars = matches.sumOf { it.value.length }
            val staticChars = segment.length - dynamicChars
            return when {
                matches.isEmpty() -> SegmentCounts(staticSegments = 1, staticChars = segment.length)
                dynamicChars == segment.length -> SegmentCounts(dynamicSegments = matches.size)
                else -> SegmentCounts(mixedSegments = 1, dynamicSegments = matches.size, staticChars = staticChars)
            }
        }
    }
}
