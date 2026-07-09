package io.specmatic.core

const val DEFAULT_SWAGGER_SPEC_YAML_PATH = "/swagger/v1/swagger.yaml"

sealed interface ApplicationApiSource {
    val url: String

    data class Actuator(override val url: String) : ApplicationApiSource
    data class Swagger(override val url: String) : ApplicationApiSource
    data class SwaggerUi(override val url: String) : ApplicationApiSource
}
