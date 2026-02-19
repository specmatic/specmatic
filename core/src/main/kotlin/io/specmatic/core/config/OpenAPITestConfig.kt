package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
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
    private fun writeConfig(): Map<String, Any> = mapper.convertValue(this, stringMapType)

    companion object {
        private val mapper = ObjectMapper().registerKotlinModule()
        private val stringMapType = mapper.typeFactory.constructType(object : TypeReference<Map<String, Any>>() {})

        fun from(config: Map<String, Any>): OpenAPITestConfig {
            return try {
                readConfig(config).getOrThrow()
            } catch (t: Throwable) {
                throw IllegalArgumentException("Invalid config provided in openapi test: ${t.message}")
            }
        }

        fun updateWithBaseUrl(data: Map<String, Any>, baseUrl: String, resiliencyTestsConfig: ResiliencyTestsConfig): Map<String, Any> {
            val config = readConfig(data).getOrElse { OpenAPITestConfig(baseUrl = "") }
            val updatedConfig = config.copy(baseUrl = baseUrl, resiliencyTests = resiliencyTestsConfig)
            return updatedConfig.writeConfig()
        }

        private fun readConfig(data: Map<String, Any>): Result<OpenAPITestConfig> {
            return runCatching {
                mapper.convertValue(data, OpenAPITestConfig::class.java)
            }
        }
    }
}

