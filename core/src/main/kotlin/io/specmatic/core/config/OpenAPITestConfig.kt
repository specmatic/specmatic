package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.ResiliencyTestsConfig

data class OpenAPITestConfig(
    @JsonProperty("baseUrl")
    val baseUrl: String,
    @JsonProperty("resiliencyTests")
    val resiliencyTests: ResiliencyTestsConfig? = null,
    @JsonProperty("examples")
    val examples: List<String>? = null
) {

    companion object {
        fun from(config: Map<String, Any>): OpenAPITestConfig {
            try {
                return ObjectMapper().registerKotlinModule().convertValue(
                    config,
                    OpenAPITestConfig::class.java
                )
            } catch (t: Throwable) {
                throw IllegalArgumentException("Invalid config provided in openapi test: ${t.message}")
            }
        }
    }
}

