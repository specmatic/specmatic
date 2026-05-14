package io.specmatic.core.config.v3.components.settings

import io.specmatic.core.config.v3.TemplateOrValue

data class ProxySettings(
    val recordRequests: TemplateOrValue<Long>? = null,
    val ignoreHeaders: TemplateOrValue<List<TemplateOrValue<String>>>? = null
)