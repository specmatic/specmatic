package io.specmatic.core.config.v3

import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolveElseThrow
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.ExampleDirectories
import net.minidev.json.annotate.JsonIgnore

data class Data(
    val examples: TemplateOrValue<RefOrValue<List<RefOrValue<ExampleDirectories>>>>? = null,
    val dictionary: TemplateOrValue<RefOrValue<Dictionary>>? = null,
    val adapters: TemplateOrValue<RefOrValue<Adapter>>? = null,
) {
    @JsonIgnore
    fun getExamples(resolver: RefOrValueResolver): List<ExampleDirectories>? {
        return examples?.resolveElseThrow(resolver)?.map { it.resolveElseThrow(resolver) }
    }

    @JsonIgnore
    fun getDictionary(resolver: RefOrValueResolver): Dictionary? {
        return dictionary?.resolveElseThrow(resolver)
    }

    @JsonIgnore
    fun getAdapter(resolver: RefOrValueResolver): Adapter? {
        return adapters?.resolveElseThrow(resolver)
    }

    fun toExampleDirs(resolver: RefOrValueResolver): List<String> {
        if (examples == null) return emptyList()
        return examples.resolveElseThrow(resolver).flatMap { example ->
            example.resolveElseThrow(resolver).directories.resolveFully()
        }
    }
}
