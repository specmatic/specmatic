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
    @JsonIgnore
    fun getBaseBranchValue(): String? {
        return baseBranch?.resolve()
    }

    @JsonIgnore
    fun getTargetPathValue(): String? {
        return targetPath?.resolve()
    }

    @JsonIgnore
    fun getRepoDirectoryValue(): String? {
        return repoDirectory?.resolve()
    }

    @JsonIgnore
    fun getStrictModeValue(): Boolean? {
        return strictMode?.resolve()
    }
}
