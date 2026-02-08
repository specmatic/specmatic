package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.specmatic.Governance
import io.specmatic.core.config.v3.specmatic.License

data class Specmatic(
    val license: License? = null,
    val governance: Governance? = null,
    val settings: RefOrValue<ConcreteSettings>? = null
)
