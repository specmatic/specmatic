package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.specmatic.Governance
import io.specmatic.core.config.v3.specmatic.License
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolveElseThrow

data class Specmatic(
    val license: TemplateOrValue<License>? = null,
    val governance: TemplateOrValue<Governance>? = null,
    val settings: TemplateOrValue<RefOrValue<ConcreteSettings>>? = null
) {
    @JsonIgnore
    fun getLicense(): License? {
        return license?.resolve()
    }

    @JsonIgnore
    fun getGovernance(): Governance? {
        return governance?.resolve()
    }

    @JsonIgnore
    fun getSettings(resolver: RefOrValueResolver): ConcreteSettings? {
        return settings?.resolveElseThrow(resolver)
    }
}
