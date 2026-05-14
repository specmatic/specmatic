package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.components.sources.SourceV3

data class Definition(val definition: TemplateOrValue<Value>) {
    data class Value(val source: TemplateOrValue<RefOrValue<SourceV3>>, val specs: TemplateOrValue<List<TemplateOrValue<SpecificationDefinition>>>)

    companion object {
        fun create(specificationDefinition: SpecificationDefinition): Definition {
            val source = SourceV3(git = null, fileSystem = SourceV3.FileSystem(), web = null)
            val value = Value(RefOrValue.Value(source), specs = listOf(specificationDefinition))
            return Definition(value)
        }
    }
}
