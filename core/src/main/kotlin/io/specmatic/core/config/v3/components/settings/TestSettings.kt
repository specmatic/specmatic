package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.TemplatableValue

data class TestSettings(
    @field:JsonAlias("resiliencyTests")
    val schemaResiliencyTests: ResiliencyTestSuite? = null,
    val timeoutInMilliseconds: TemplatableValue<Long>? = null,
    val strictMode: TemplatableValue<Boolean>? = null,
    val lenientMode: TemplatableValue<Boolean>? = null,
    val parallelism: TemplatableValue<String>? = null,
    val maxTestRequestCombinations: TemplatableValue<Int>? = null,
    val junitReportDir: TemplatableValue<String>? = null,
    val validateResponseValues: TemplatableValue<Boolean>? = null,
    val maxTestCount: TemplatableValue<Int>? = null,
) {
    fun merge(fallback: TestSettings?): TestSettings {
        if (fallback == null) return this
        return TestSettings(
            schemaResiliencyTests = this.schemaResiliencyTests ?: fallback.schemaResiliencyTests,
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
