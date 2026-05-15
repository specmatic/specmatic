package io.specmatic.core.config.v3.specmatic

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class License(val path: TemplateOrValue<String>) {
    @JsonIgnore
    fun getPath(): String {
        return path.resolve()
    }
}
