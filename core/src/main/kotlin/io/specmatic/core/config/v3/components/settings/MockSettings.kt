package io.specmatic.core.config.v3.components.settings

import io.specmatic.core.config.v3.TemplateOrValue

data class MockSettings(
    val generative: TemplateOrValue<Boolean>? = null,
    val delayInMilliseconds: TemplateOrValue<Long>? = null,
    val startTimeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val hotReload: TemplateOrValue<Boolean>? = null,
    val strictMode: TemplateOrValue<Boolean>? = null,
    val gracefulRestartTimeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val lenientMode: TemplateOrValue<Boolean>? = null
) {
    fun merge(fallback: MockSettings?): MockSettings {
        if (fallback == null) return this
        return MockSettings(
            hotReload = this.hotReload ?: fallback.hotReload,
            generative = this.generative ?: fallback.generative,
            strictMode = this.strictMode ?: fallback.strictMode,
            lenientMode = this.lenientMode ?: fallback.lenientMode,
            delayInMilliseconds = this.delayInMilliseconds ?: fallback.delayInMilliseconds,
            startTimeoutInMilliseconds = this.startTimeoutInMilliseconds ?: fallback.startTimeoutInMilliseconds,
            gracefulRestartTimeoutInMilliseconds = this.gracefulRestartTimeoutInMilliseconds ?: fallback.gracefulRestartTimeoutInMilliseconds,
        )
    }
}
