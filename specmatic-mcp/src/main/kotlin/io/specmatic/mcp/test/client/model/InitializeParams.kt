package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class InitializeParams(
    @JsonProperty("protocolVersion")
    val protocolVersion: String,
    @JsonProperty("capabilities")
    val capabilities: Capabilities,
    @JsonProperty("clientInfo")
    val clientInfo: ClientInfo
)
