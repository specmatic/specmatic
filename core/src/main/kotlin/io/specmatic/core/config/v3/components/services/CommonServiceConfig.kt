package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue

data class CommonServiceConfig<RunOptions : Any, Settings: Any>(
    val description: String? = null,
    val definitions: List<Definition>,
    val runOptions: RefOrValue<RunOptions>? = null,
    val data: Data? = null,
    val settings: RefOrValue<Settings>? = null
)
