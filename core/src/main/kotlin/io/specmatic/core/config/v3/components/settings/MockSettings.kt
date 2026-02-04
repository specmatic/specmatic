package io.specmatic.core.config.v3.components.settings

import io.specmatic.core.config.Switch

data class MockSettings(
    val generative: Boolean? = null,
    val delayInMilliseconds: Long? = null,
    val startTimeoutInMilliseconds: Long? = null,
    val hotReload: Switch? = null,
    val strictMode: Boolean? = null,
    val gracefulRestartTimeoutInMilliseconds: Long? = null,
    val allowExtensibleSchema: Boolean? = null,
    val lenientMode: Boolean? = null
) {
    fun merge(fallback: MockSettings?): MockSettings {
        if (fallback == null) return this
        return MockSettings(
            hotReload = this.hotReload ?: fallback.hotReload,
            generative = this.generative ?: fallback.generative,
            strictMode = this.strictMode ?: fallback.strictMode,
            lenientMode = this.lenientMode ?: fallback.lenientMode,
            delayInMilliseconds = this.delayInMilliseconds ?: fallback.delayInMilliseconds,
            allowExtensibleSchema = this.allowExtensibleSchema ?: fallback.allowExtensibleSchema,
            startTimeoutInMilliseconds = this.startTimeoutInMilliseconds ?: fallback.startTimeoutInMilliseconds,
            gracefulRestartTimeoutInMilliseconds = this.gracefulRestartTimeoutInMilliseconds ?: fallback.gracefulRestartTimeoutInMilliseconds,
        )
    }
}
