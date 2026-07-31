package io.specmatic.core.config

import java.io.File

data class BackwardCompatibilityConfig(
    val baseBranch: String? = null,
    val targetPath: String? = null,
    val repoDirectory: String? = null,
    val strictMode: Boolean? = null
) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): BackwardCompatibilityConfig {
        return copy(
            targetPath = targetPath?.let { mapper.child("targetPath").map(it, configDirectory) },
            repoDirectory = repoDirectory?.let { mapper.child("repoDirectory").map(it, configDirectory) }
        )
    }
}
