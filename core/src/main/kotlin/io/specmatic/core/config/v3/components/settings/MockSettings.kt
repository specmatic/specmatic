package io.specmatic.core.config.v3.components.settings

import io.specmatic.core.TemplatableValue

data class MockSettings(
    val generative: TemplatableValue<Boolean>? = null,
    val delayInMilliseconds: TemplatableValue<Long>? = null,
    val startTimeoutInMilliseconds: TemplatableValue<Long>? = null,
    val hotReload: TemplatableValue<Boolean>? = null,
    val strictMode: TemplatableValue<Boolean>? = null,
    val gracefulRestartTimeoutInMilliseconds: TemplatableValue<Long>? = null,
    val lenientMode: TemplatableValue<Boolean>? = null
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
