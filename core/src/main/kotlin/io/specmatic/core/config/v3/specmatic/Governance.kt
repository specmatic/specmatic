package io.specmatic.core.config.v3.specmatic

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.specmatic.core.ReportConfiguration
import io.specmatic.core.SuccessCriteria
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class Governance(val report: TemplateOrValue<Report>? = null, @field:JsonProperty("successCriteria") val successCriterion: TemplateOrValue<SuccessCriterion>? = null) : ReportConfiguration {
    @JsonIgnore
    override fun getSuccessCriteria(): SuccessCriteria {
        return this.successCriterion?.resolve()?.toSuccessCriteria() ?: SuccessCriteria.default
    }

    override fun excludedOpenAPIEndpoints(): List<String> {
        return emptyList()
    }
}
