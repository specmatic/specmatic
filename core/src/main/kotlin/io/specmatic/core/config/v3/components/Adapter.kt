package io.specmatic.core.config.v3.components

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore

data class Adapter(@get:JsonAnyGetter private val rawValue: Map<String, String>) {
    @JsonIgnore
    val hooks: Map<String, String> = rawValue

    companion object {
        @JvmStatic
        @JsonCreator
        fun create(@JsonAnySetter raw: Map<String, String>): Adapter = Adapter(raw)
    }
}
