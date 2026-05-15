package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class ProxySettings(
    val recordRequests: TemplateOrValue<Long>? = null,
    val ignoreHeaders: TemplateOrValue<List<TemplateOrValue<String>>>? = null
) {
    @JsonIgnore
    fun getRecordRequests(): Long? {
        return recordRequests?.resolve()
    }

    @JsonIgnore
    fun getIgnoreHeaders(): List<String>? {
        return ignoreHeaders?.resolveFully()
    }
}
