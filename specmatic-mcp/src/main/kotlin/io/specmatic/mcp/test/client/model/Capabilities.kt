package io.specmatic.mcp.test.client.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Capabilities(
    @JsonProperty("roots")
    val roots: RootsCapability? = null,
    @JsonProperty("sampling")
    val sampling: Map<String, Any> = emptyMap()
)
