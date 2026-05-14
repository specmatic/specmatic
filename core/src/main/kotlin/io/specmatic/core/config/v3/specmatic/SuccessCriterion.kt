package io.specmatic.core.config.v3.specmatic

import io.specmatic.core.SuccessCriteria
import io.specmatic.core.config.v3.TemplateOrValue

class SuccessCriterion(val minCoveragePercentage: TemplateOrValue<Int>? = null, val maxMissedOperationsInSpec: TemplateOrValue<Int>? = null, val enforce: TemplateOrValue<Boolean>? = null) {
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
