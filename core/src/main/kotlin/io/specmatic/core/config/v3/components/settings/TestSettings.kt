package io.specmatic.core.config.v3.components.settings

import io.specmatic.core.ResiliencyTestSuite

data class TestSettings(
    val resiliencyTests: ResiliencyTestSuite? = null,
    val timeoutInMilliseconds: Long? = null,
    val strictMode: Boolean? = null,
    val lenientMode: Boolean? = null,
    val parallelism: String? = null,
    val maxTestRequestCombinations: Int? = null,
    val junitReportDir: String? = null,
    val validateResponseValues: Boolean? = null,
    val maxTestCount: Int? = null,
) {
    fun merge(fallback: TestSettings?): TestSettings {
        if (fallback == null) return this
        return TestSettings(
            resiliencyTests = this.resiliencyTests ?: fallback.resiliencyTests,
            timeoutInMilliseconds = this.timeoutInMilliseconds ?: fallback.timeoutInMilliseconds,
            strictMode = this.strictMode ?: fallback.strictMode,
            lenientMode = this.lenientMode ?: fallback.lenientMode,
            parallelism = this.parallelism ?: fallback.parallelism,
            maxTestRequestCombinations = this.maxTestRequestCombinations ?: fallback.maxTestRequestCombinations,
            junitReportDir = this.junitReportDir ?: fallback.junitReportDir,
            validateResponseValues = this.validateResponseValues ?: fallback.validateResponseValues,
            maxTestCount = this.maxTestCount ?: fallback.maxTestCount,
        )
    }
}
