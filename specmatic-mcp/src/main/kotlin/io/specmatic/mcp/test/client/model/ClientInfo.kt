package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClientInfo(
    @JsonProperty("name")
    val name: String,
    @JsonProperty("version")
    val version: String
)
