package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonRpcError(
    @JsonProperty("code")
    val code: Int,
    @JsonProperty("message")
    val message: String,
    @JsonProperty("data")
    val data: JsonNode? = null
) {
    private val invalidParamsStatusCode = -32602

    fun isInvalidParamsStatusCode() = code == invalidParamsStatusCode
}
