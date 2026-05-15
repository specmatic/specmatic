package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class MockSettings(
    val generative: TemplateOrValue<Boolean>? = null,
    val delayInMilliseconds: TemplateOrValue<Long>? = null,
    val startTimeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val hotReload: TemplateOrValue<Boolean>? = null,
    val strictMode: TemplateOrValue<Boolean>? = null,
    val gracefulRestartTimeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val lenientMode: TemplateOrValue<Boolean>? = null
) {
    @JsonIgnore
    fun getGenerative(): Boolean? {
        return generative?.resolve()
    }

    @JsonIgnore
    fun getDelayInMilliseconds(): Long? {
        return delayInMilliseconds?.resolve()
    }

    @JsonIgnore
    fun getStartTimeoutInMilliseconds(): Long? {
        return startTimeoutInMilliseconds?.resolve()
    }

    @JsonIgnore
    fun getHotReload(): Boolean? {
        return hotReload?.resolve()
    }

    @JsonIgnore
    fun getStrictMode(): Boolean? {
        return strictMode?.resolve()
    }

    @JsonIgnore
    fun getGracefulRestartTimeoutInMilliseconds(): Long? {
        return gracefulRestartTimeoutInMilliseconds?.resolve()
    }

    @JsonIgnore
    fun getLenientMode(): Boolean? {
        return lenientMode?.resolve()
    }

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
