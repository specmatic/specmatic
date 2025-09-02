package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcRequest(
    @JsonProperty("jsonrpc")
    val jsonrpc: String = "2.0",
    @JsonProperty("id")
    val id: Any? = null,
    @JsonProperty("method")
    val method: String,
    @JsonProperty("params")
    val params: Any? = null
)
