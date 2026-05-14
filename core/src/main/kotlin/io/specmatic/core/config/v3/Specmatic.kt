package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.specmatic.Governance
import io.specmatic.core.config.v3.specmatic.License

data class Specmatic(
    val license: TemplateOrValue<License>? = null,
    val governance: TemplateOrValue<Governance>? = null,
    val settings: TemplateOrValue<RefOrValue<ConcreteSettings>>? = null
)
