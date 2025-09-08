package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RootsCapability(
    @JsonProperty("listChanged")
    val listChanged: Boolean = true
)
