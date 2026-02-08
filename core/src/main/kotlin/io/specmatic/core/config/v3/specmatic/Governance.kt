package io.specmatic.core.config.v3.specmatic

import io.specmatic.core.ReportConfiguration
import io.specmatic.core.SuccessCriteria

data class Governance(val report: Report? = null, val successCriteria: SuccessCriterion? = null): ReportConfiguration {
    override fun getSuccessCriteria(): SuccessCriteria {
        return this.successCriteria?.toSuccessCriteria() ?: SuccessCriteria.default
    }

    override fun excludedOpenAPIEndpoints(): List<String> {
        return emptyList()
    }
}
