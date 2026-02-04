package io.specmatic.core.config

data class BackwardCompatibilityConfig(
    val baseBranch: String? = null,
    val targetPath: String? = null,
    val repoDirectory: String? = null,
    val strictMode: Boolean? = null
)
