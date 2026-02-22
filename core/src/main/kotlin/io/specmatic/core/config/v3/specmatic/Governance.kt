package io.specmatic.core.config.v3.specmatic

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.specmatic.core.ReportConfiguration
import io.specmatic.core.SuccessCriteria

data class Governance(val report: Report? = null, @field:JsonProperty("successCriteria") val successCriterion: SuccessCriterion? = null) : ReportConfiguration {
    @JsonIgnore
    override fun getSuccessCriteria(): SuccessCriteria {
        return this.successCriterion?.toSuccessCriteria() ?: SuccessCriteria.default
    }

    override fun excludedOpenAPIEndpoints(): List<String> {
        return emptyList()
    }
}
