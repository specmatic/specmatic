package io.specmatic.core.config.v3.specmatic

class SuccessCriterion(
    val minCoveragePercentage: Int? = null,
    val maxMissedOperationsInSpec: Int? = null,
    val enforce: Boolean? = null
)
