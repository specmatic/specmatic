package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.config.ConfigPathMapper
import java.io.File

data class TestSettings(
    @field:JsonAlias("resiliencyTests")
    val schemaResiliencyTests: ResiliencyTestSuite? = null,
    val timeoutInMilliseconds: Long? = null,
    val strictMode: Boolean? = null,
    val lenientMode: Boolean? = null,
    val parallelism: String? = null,
    val maxTestRequestCombinations: Int? = null,
    val junitReportDir: String? = null,
    val maxTestCount: Int? = null,
) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): TestSettings {
        return copy(
            junitReportDir = junitReportDir?.let {
                mapper.child("junitReportDir").map(it, configDirectory)
            }
        )
    }

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
            maxTestCount = this.maxTestCount ?: fallback.maxTestCount,
        )
    }
}
