package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.ExampleDirectories

data class Data(
    val examples: RefOrValue<List<RefOrValue<ExampleDirectories>>>? = null,
    val dictionary: RefOrValue<Dictionary>? = null,
    val adapters: RefOrValue<Adapter>? = null,
)
