package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tool(
    @JsonProperty("name")
    val name: String,
    @JsonProperty("description")
    val description: String? = null,
    @JsonProperty("inputSchema")
    val inputSchema: JsonNode,
    @JsonProperty("outputSchema")
    val outputSchema: JsonNode? = null
)
