package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.sources.SourceV3

data class Definition(val definition: Value) {
    data class Value(val source: RefOrValue<SourceV3>, val specs: List<SpecificationDefinition>)
}