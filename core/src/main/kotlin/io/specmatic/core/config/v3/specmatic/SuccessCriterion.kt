package io.specmatic.core.config.v3.specmatic

import io.specmatic.core.SuccessCriteria
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.wrapOrNull

class SuccessCriterion(val minCoveragePercentage: Int? = null, val maxMissedOperationsInSpec: Int? = null, val enforce: Boolean? = null) {
    fun toSuccessCriteria(): SuccessCriteria {
        return SuccessCriteria(minCoveragePercentage.wrapOrNull(), maxMissedOperationsInSpec.wrapOrNull(), enforce.wrapOrNull())
    }

    companion object {
        fun from(successCriteria: SuccessCriteria): SuccessCriterion {
            return SuccessCriterion(
                successCriteria.minThresholdPercentage.resolveOrNull(),
                successCriteria.maxMissedEndpointsInSpec.resolveOrNull(),
                successCriteria.enforce.resolveOrNull())
        }
    }
}
