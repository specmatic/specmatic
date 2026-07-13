package io.specmatic.core

const val DEFAULT_SWAGGER_SPEC_YAML_PATH = "/swagger/v1/swagger.yaml"

sealed interface ApplicationApiSource {
    val url: String
    val isExplicitlyConfigured: Boolean

    data class Actuator @JvmOverloads constructor(
        override val url: String,
        override val isExplicitlyConfigured: Boolean = true,
    ) : ApplicationApiSource

    data class Swagger @JvmOverloads constructor(
        override val url: String,
        override val isExplicitlyConfigured: Boolean = true,
    ) : ApplicationApiSource

    data class SwaggerUi @JvmOverloads constructor(
        override val url: String,
        override val isExplicitlyConfigured: Boolean = true,
    ) : ApplicationApiSource
}
