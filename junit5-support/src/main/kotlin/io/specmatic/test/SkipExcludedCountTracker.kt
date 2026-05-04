package io.specmatic.test

import io.specmatic.core.utilities.Decision
import java.util.concurrent.atomic.AtomicInteger

data class SkipExcludedCounts(val skipped: Int = 0, val excluded: Int = 0)
class SkipExcludedCountTracker {
    private val skipped = AtomicInteger(0)
    private val excluded = AtomicInteger(0)

    fun record(decision: Decision<*, *>) {
        val skipDecision = decision as? Decision.Skip<*> ?: return
        if (skipDecision.reasoning.hasReason(TestSkipReason.EXCLUDED)) {
            excluded.incrementAndGet()
        } else {
            skipped.incrementAndGet()
        }
    }

    fun snapshot(): SkipExcludedCounts {
        return SkipExcludedCounts(skipped = skipped.get(), excluded = excluded.get())
    }
}
