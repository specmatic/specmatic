package io.specmatic.core.config.v3.specmatic

import io.specmatic.core.SuccessCriteria

class SuccessCriterion(val minCoveragePercentage: Int? = null, val maxMissedOperationsInSpec: Int? = null, val enforce: Boolean? = null) {
    fun toSuccessCriteria(): SuccessCriteria {
        return SuccessCriteria(minCoveragePercentage, maxMissedOperationsInSpec, enforce)
    }

    companion object {
        fun from(successCriteria: SuccessCriteria): SuccessCriterion {
            return SuccessCriterion(
                successCriteria.minThresholdPercentage,
                successCriteria.maxMissedEndpointsInSpec,
                successCriteria.enforce)
        }
    }
}
