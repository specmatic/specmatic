package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class TestSettings(
    @field:JsonAlias("resiliencyTests")
    val schemaResiliencyTests: TemplateOrValue<ResiliencyTestSuite>? = null,
    val timeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val strictMode: TemplateOrValue<Boolean>? = null,
    val lenientMode: TemplateOrValue<Boolean>? = null,
    val parallelism: TemplateOrValue<String>? = null,
    val maxTestRequestCombinations: TemplateOrValue<Int>? = null,
    val junitReportDir: TemplateOrValue<String>? = null,
    val validateResponseValues: TemplateOrValue<Boolean>? = null,
    val maxTestCount: TemplateOrValue<Int>? = null,
) {
    @JsonIgnore
    fun getSchemaResiliencyTests(): ResiliencyTestSuite? {
        return schemaResiliencyTests?.resolve()
    }

    @JsonIgnore
    fun getTimeoutInMilliseconds(): Long? {
        return timeoutInMilliseconds?.resolve()
    }

    @JsonIgnore
    fun getStrictMode(): Boolean? {
        return strictMode?.resolve()
    }

    @JsonIgnore
    fun getLenientMode(): Boolean? {
        return lenientMode?.resolve()
    }

    @JsonIgnore
    fun getParallelism(): String? {
        return parallelism?.resolve()
    }

    @JsonIgnore
    fun getMaxTestRequestCombinations(): Int? {
        return maxTestRequestCombinations?.resolve()
    }

    @JsonIgnore
    fun getJunitReportDir(): String? {
        return junitReportDir?.resolve()
    }

    @JsonIgnore
    fun getValidateResponseValues(): Boolean? {
        return validateResponseValues?.resolve()
    }

    @JsonIgnore
    fun getMaxTestCount(): Int? {
        return maxTestCount?.resolve()
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
            validateResponseValues = this.validateResponseValues ?: fallback.validateResponseValues,
            maxTestCount = this.maxTestCount ?: fallback.maxTestCount,
        )
    }
}
