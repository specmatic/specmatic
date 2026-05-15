package io.specmatic.core.config.v3.components

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue

data class Adapter(@JsonValue val hooks: TemplateOrValue<Map<String, TemplateOrValue<String>>>) {
    @JsonIgnore
    fun getHooksResolved(): Map<String, String>?{
        return hooks.resolveFully()
    }
}
