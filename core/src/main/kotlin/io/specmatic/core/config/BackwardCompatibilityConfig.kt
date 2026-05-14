package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class BackwardCompatibilityConfig(
    val baseBranch: TemplateOrValue<String>? = null,
    val targetPath: TemplateOrValue<String>? = null,
    val repoDirectory: TemplateOrValue<String>? = null,
    val strictMode: TemplateOrValue<Boolean>? = null
) {
    @get:JsonIgnore
    val baseBranchValue: String?
        get() = baseBranch?.resolve()

    @get:JsonIgnore
    val targetPathValue: String?
        get() = targetPath?.resolve()

    @get:JsonIgnore
    val repoDirectoryValue: String?
        get() = repoDirectory?.resolve()

    @get:JsonIgnore
    val strictModeValue: Boolean?
        get() = strictMode?.resolve()
}
