package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.ExampleDirectories

data class Data(
    val examples: TemplateOrValue<RefOrValue<List<TemplateOrValue<RefOrValue<ExampleDirectories>>>>>? = null,
    val dictionary: TemplateOrValue<RefOrValue<Dictionary>>? = null,
    val adapters: TemplateOrValue<RefOrValue<Adapter>>? = null,
) {
    fun toExampleDirs(resolver: RefOrValueResolver): List<String> {
        if (examples == null) return emptyList()
        return examples.resolveElseThrow(resolver).flatMap { example ->
            example.resolveElseThrow(resolver).directories
        }
    }
}
