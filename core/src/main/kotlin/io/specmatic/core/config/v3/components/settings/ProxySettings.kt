package io.specmatic.core.config.v3.components.settings

data class ProxySettings(
    val recordRequests: Boolean? = null,
    val ignoreHeaders: List<String>? = null
)