package io.specmatic.core.config.v3.specmatic

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.ReportConfiguration
import io.specmatic.core.SuccessCriteria

data class Governance(val report: Report? = null, val successCriteria: SuccessCriterion? = null): ReportConfiguration {
    override fun successCriteria(): SuccessCriteria {
        return this.successCriteria?.toSuccessCriteria() ?: SuccessCriteria.default
    }

    override fun excludedOpenAPIEndpoints(): List<String> {
        return emptyList()
    }
}
