package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

data class OpenAPIMockConfig(
    @JsonProperty("baseUrl")
    val baseUrl: String,
    @JsonProperty("examples")
    val examples: List<String>? = null
) {
    private fun writeConfig(): Map<String, Any> = mapper.convertValue(this, stringMapType)

    companion object {
        private val mapper = ObjectMapper().registerKotlinModule()
        private val stringMapType = mapper.typeFactory.constructType(object : TypeReference<Map<String, Any>>() {})

        fun from(config: Map<String, Any>): OpenAPIMockConfig {
            return try {
                readConfig(config).getOrThrow()
            } catch(t: Throwable) {
                throw IllegalArgumentException("Invalid config provided in openapi mock: ${t.message}")
            }
        }

        fun updateWithPort(data: Map<String, Any>, port: Int): Map<String, Any> {
            val config = readConfig(data).getOrElse { OpenAPIMockConfig(baseUrl = "") }
            val updatedConfig = config.copy(baseUrl = "http//0.0.0.0:$port")
            return updatedConfig.writeConfig()
        }

        private fun readConfig(data: Map<String, Any>): Result<OpenAPIMockConfig> {
            return runCatching {
                mapper.convertValue(data, OpenAPIMockConfig::class.java)
            }
        }
    }
}
