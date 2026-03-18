package io.specmatic.core.utilities

import java.math.BigInteger

sealed interface SegmentsCountComparator : Comparable<SegmentsCountComparator> {
    val segmentCounts: SegmentCounts
    val score: BigInteger

    data class SpecificityComparator(override val segmentCounts: SegmentCounts): SegmentsCountComparator {
        override val score: BigInteger get() {
            return packToBigInteger(
                first = segmentCounts.staticSegments.toUInt(),
                second = segmentCounts.mixedSegments.toUInt(),
                third = segmentCounts.staticChars.toUInt(),
                fourth = inverse(segmentCounts.dynamicSegments)
            )
        }

        override fun compareTo(other: SegmentsCountComparator): Int {
            return compareByDescending<SegmentCounts> { it.staticSegments }
                .thenByDescending { it.mixedSegments }
                .thenByDescending { it.staticChars }
                .thenBy { it.dynamicSegments }
                .compare(other.segmentCounts, segmentCounts)
        }

        fun isMoreSpecificThan(other: SegmentsCountComparator): Boolean = compareTo(other) > 0
    }

    data class GeneralityComparator(override val segmentCounts: SegmentCounts): SegmentsCountComparator {
        override val score: BigInteger get() {
            return packToBigInteger(
                first = segmentCounts.dynamicSegments.toUInt(),
                second = inverse(segmentCounts.staticSegments),
                third = inverse(segmentCounts.mixedSegments),
                fourth = inverse(segmentCounts.staticChars)
            )
        }

        override fun compareTo(other: SegmentsCountComparator): Int {
            return compareByDescending<SegmentCounts> { it.dynamicSegments }
                .thenBy { it.staticSegments }
                .thenBy { it.mixedSegments }
                .thenBy { it.staticChars }
                .compare(other.segmentCounts, segmentCounts)
        }
    }

    companion object {
        private fun inverse(value: Int): UInt = UInt.MAX_VALUE - value.toUInt()

        private fun packToBigInteger(first: UInt, second: UInt, third: UInt, fourth: UInt): BigInteger {
            val high = (first.toULong() shl 32) or second.toULong()
            val low = (third.toULong() shl 32) or fourth.toULong()
            return BigInteger(high.toString()).shiftLeft(64).or(BigInteger(low.toString()))
        }
    }
}

data class SegmentCounts(val staticSegments: Int = 0, val staticChars: Int = 0, val mixedSegments: Int = 0, val dynamicSegments: Int = 0) {
    operator fun plus(other: SegmentCounts): SegmentCounts {
        return SegmentCounts(
            staticSegments = staticSegments + other.staticSegments,
            staticChars = staticChars + other.staticChars,
            mixedSegments = mixedSegments + other.mixedSegments,
            dynamicSegments = dynamicSegments + other.dynamicSegments,
        )
    }

    fun toSpecificityComparator(): SegmentsCountComparator.SpecificityComparator = SegmentsCountComparator.SpecificityComparator(this)

    fun toGeneralityComparator(): SegmentsCountComparator.GeneralityComparator = SegmentsCountComparator.GeneralityComparator(this)

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
