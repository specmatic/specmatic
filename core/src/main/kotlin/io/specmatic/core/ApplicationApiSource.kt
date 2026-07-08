package io.specmatic.core

sealed interface ApplicationApiSource {
    val url: String

    data class Actuator(override val url: String) : ApplicationApiSource
    data class Swagger(override val url: String) : ApplicationApiSource
    data class SwaggerUi(override val url: String) : ApplicationApiSource
}
