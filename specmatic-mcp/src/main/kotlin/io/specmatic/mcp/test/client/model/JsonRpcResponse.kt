package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse(
    @JsonProperty("jsonrpc")
    val jsonrpc: String,
    @JsonProperty("id")
    val id: Any?,
    @JsonProperty("result")
    val result: JsonNode? = null,
    @JsonProperty("error")
    val error: JsonRpcError? = null
)
