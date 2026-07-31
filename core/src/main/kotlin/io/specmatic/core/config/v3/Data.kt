package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.ConfigPathMapper
import java.io.File

data class Data(
    val examples: RefOrValue<List<RefOrValue<ExampleDirectories>>>? = null,
    val dictionary: RefOrValue<Dictionary>? = null,
    val adapters: RefOrValue<Adapter>? = null,
) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): Data {
        return copy(
            adapters = adapters?.mapValue { it.mapPaths(mapper.child("adapters"), configDirectory) },
            dictionary = dictionary?.mapValue {
                it.mapPaths(mapper.child("dictionary").child("path"), configDirectory)
            },
            examples = examples?.mapValue { list -> list.mapIndexed { i, value ->
                value.mapValue { it.mapPaths(mapper.child("examples").child(i), configDirectory) } }
            },
        )
    }

    fun toExampleDirs(resolver: RefOrValueResolver): List<String> {
        if (examples == null) return emptyList()
        return examples.resolveElseThrow(resolver).flatMap { example ->
            example.resolveElseThrow(resolver).directories
        }
    }
}
