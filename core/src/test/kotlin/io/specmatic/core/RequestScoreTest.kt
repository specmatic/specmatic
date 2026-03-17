package io.specmatic.core

import io.specmatic.core.utilities.SegmentCounts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger

internal class RequestScoreTest {
    @Test
    fun `combinedScore should add all component scores as BigInteger`() {
        val pathComparator = SegmentCounts(
            staticSegments = Int.MAX_VALUE,
            mixedSegments = Int.MAX_VALUE,
            staticChars = Int.MAX_VALUE,
            dynamicSegments = Int.MAX_VALUE
        ).toSpecificityComparator()

        val requestScore = RequestScore(
            pathScore = pathComparator,
            queryScore = Int.MAX_VALUE,
            headerScore = Int.MAX_VALUE,
            bodyScore = Int.MAX_VALUE
        )

        assertThat(requestScore.combinedScore).isEqualTo(
            pathComparator.score
                .add(BigInteger.valueOf(Int.MAX_VALUE.toLong()))
                .add(BigInteger.valueOf(Int.MAX_VALUE.toLong()))
                .add(BigInteger.valueOf(Int.MAX_VALUE.toLong()))
        )
    }
}
