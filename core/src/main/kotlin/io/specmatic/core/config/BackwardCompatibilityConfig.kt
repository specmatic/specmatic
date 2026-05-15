package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.wrapOrNull

data class BackwardCompatibilityConfig(
    val baseBranch: TemplateOrValue<String>? = null,
    val targetPath: TemplateOrValue<String>? = null,
    val repoDirectory: TemplateOrValue<String>? = null,
    val strictMode: TemplateOrValue<Boolean>? = null
) {
    @get:JsonIgnore
    val resolvedBaseBranch: String?
        get() = baseBranch.resolveOrNull()

    @get:JsonIgnore
    val resolvedTargetPath: String?
        get() = targetPath.resolveOrNull()

    @get:JsonIgnore
    val resolvedRepoDirectory: String?
        get() = repoDirectory.resolveOrNull()

    @get:JsonIgnore
    val resolvedStrictMode: Boolean?
        get() = strictMode.resolveOrNull()

    companion object {
        fun from(
            baseBranch: String? = null,
            targetPath: String? = null,
            repoDirectory: String? = null,
            strictMode: Boolean? = null
        ): BackwardCompatibilityConfig = BackwardCompatibilityConfig(
            baseBranch = baseBranch.wrapOrNull(),
            targetPath = targetPath.wrapOrNull(),
            repoDirectory = repoDirectory.wrapOrNull(),
            strictMode = strictMode.wrapOrNull()
        )
    }
}
