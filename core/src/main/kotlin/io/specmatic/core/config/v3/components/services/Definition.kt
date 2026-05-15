package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.wrapFully
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolveElseThrow
import io.specmatic.core.config.v3.components.sources.SourceV3

data class Definition(val definition: TemplateOrValue<Value>) {
    data class Value(val source: TemplateOrValue<RefOrValue<SourceV3>>, val specs: TemplateOrValue<List<TemplateOrValue<SpecificationDefinition>>>) {
        @JsonIgnore
        fun getSource(resolver: RefOrValueResolver): SourceV3 = source.resolveElseThrow(resolver)

        @JsonIgnore
        fun getSpecs(): List<SpecificationDefinition> = specs.resolveFully()
    }

    @JsonIgnore
    fun getSource(resolver: RefOrValueResolver): SourceV3 = definition.resolve().getSource(resolver)

    @JsonIgnore
    fun getSpecs(): List<SpecificationDefinition> = definition.resolve().getSpecs()

    companion object {
        fun create(specificationDefinition: SpecificationDefinition): Definition {
            val source = SourceV3(git = null, fileSystem = TemplateOrValue.Value(SourceV3.FileSystem()), web = null)
            val value = Value(source = TemplateOrValue.Value(RefOrValue.Value(source)), specs = listOf(specificationDefinition).wrapFully())
            return Definition(TemplateOrValue.Value(value))
        }
    }
}
