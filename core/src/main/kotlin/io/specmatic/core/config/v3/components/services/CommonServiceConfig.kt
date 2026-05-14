package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.TemplateOrValue

data class CommonServiceConfig<RunOptions : Any, Settings: Any>(
    val description: TemplateOrValue<String>? = null,
    val definitions: TemplateOrValue<List<TemplateOrValue<Definition>>>,
    val runOptions: TemplateOrValue<RefOrValue<RunOptions>>? = null,
    val data: TemplateOrValue<Data>? = null,
    val settings: TemplateOrValue<RefOrValue<Settings>>? = null
)
