package io.specmatic.core.config.validation

import io.specmatic.core.config.SpecmaticConfigVersion
import kotlinx.serialization.Serializable

sealed interface ConfigValidationResult {
    data class Valid(val version: SpecmaticConfigVersion) : ConfigValidationResult
    data class Invalid(val version: SpecmaticConfigVersion?, val output: List<ConfigValidationOutput>) : ConfigValidationResult
}

@Serializable
data class ConfigValidationOutput(
    val valid: Boolean,
    val error: String? = null,
    val keywordLocation: String,
    val instanceLocation: String,
    val absoluteKeywordLocation: String? = null,
    val severity: ConfigValidationSeverity = ConfigValidationSeverity.ERROR,
    @kotlinx.serialization.Transient
    val metadata: ConfigValidationMetadata? = null,
)

@Serializable
enum class ConfigValidationSeverity {
    ERROR,
    WARNING,
}

@Serializable
data class ConfigValidationMetadata(
    val title: String? = null,
    val keyword: String? = null,
    val description: String? = null,
    val deprecated: Boolean? = null,
    val deprecationMessage: String? = null,
)
