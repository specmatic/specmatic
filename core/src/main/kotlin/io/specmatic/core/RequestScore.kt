package io.specmatic.core

import io.specmatic.core.utilities.SegmentCounts
import io.specmatic.core.utilities.SegmentsCountComparator
import java.math.BigInteger

data class RequestScore(
    val pathScore: SegmentsCountComparator,
    val queryScore: Int = 0,
    val headerScore: Int = 0,
    val bodyScore: Int = 0
) : Comparable<RequestScore> {
    val combinedScore: BigInteger by lazy {
        pathScore.score
        .add(BigInteger.valueOf(queryScore.toLong()))
        .add(BigInteger.valueOf(headerScore.toLong()))
        .add(BigInteger.valueOf(bodyScore.toLong()))
    }

    override fun compareTo(other: RequestScore): Int = compareValuesBy(this, other,
        { it.pathScore },
        { it.queryScore },
        { it.headerScore },
        { it.bodyScore }
    )

    companion object {
        fun RequestScore?.orEmpty(pathComp: SegmentsCountComparator = SegmentCounts().toSpecificityComparator()): RequestScore {
            return this ?: RequestScore(pathComp)
        }
    }
}
