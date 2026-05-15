package io.specmatic.core.config.v3.specmatic

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.SuccessCriteria
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

class SuccessCriterion(val minCoveragePercentage: TemplateOrValue<Int>? = null, val maxMissedOperationsInSpec: TemplateOrValue<Int>? = null, val enforce: TemplateOrValue<Boolean>? = null) {
    @JsonIgnore
    fun getMinCoveragePercentage(): Int? {
        return minCoveragePercentage?.resolve()
    }

    @JsonIgnore
    fun getMaxMissedOperationsInSpec(): Int? {
        return maxMissedOperationsInSpec?.resolve()
    }

    @JsonIgnore
    fun getEnforce(): Boolean? {
        return enforce?.resolve()
    }

    fun toSuccessCriteria(): SuccessCriteria {
        return SuccessCriteria(minCoveragePercentage, maxMissedOperationsInSpec, enforce)
    }

    companion object {
        fun from(successCriteria: SuccessCriteria): SuccessCriterion {
            return SuccessCriterion(
                minCoveragePercentage = successCriteria.minThresholdPercentage,
                maxMissedOperationsInSpec = successCriteria.maxMissedEndpointsInSpec,
                enforce = successCriteria.enforce
            )
        }
    }
}
