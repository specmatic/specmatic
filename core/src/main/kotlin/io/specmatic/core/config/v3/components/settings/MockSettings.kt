package io.specmatic.core.config.v3.components.settings

data class MockSettings(
    val generative: Boolean? = null,
    val delayInMilliseconds: Long? = null,
    val startTimeoutInMilliseconds: Long? = null,
    val hotReload: Boolean? = null,
    val strictMode: Boolean? = null,
    val gracefulRestartTimeoutInMilliseconds: Long? = null
)
