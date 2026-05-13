package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.ExampleDirectories

class DataSectionMapper {
    fun mapFrom(exampleDirectories: List<String>, dictionaryPath: String? = null, hooks: Map<String, String> = emptyMap()): Data {
        val examples = exampleDirectories.takeIf { it.isNotEmpty() }?.let { directories ->
            RefOrValue.Value<List<RefOrValue<ExampleDirectories>>>(
                listOf(RefOrValue.Value(ExampleDirectories(directories)))
            )
        }

        val dictionary = dictionaryPath?.let { RefOrValue.Value(Dictionary(it)) }
        val adapters = hooks.takeIf { it.isNotEmpty() }?.let { RefOrValue.Value(Adapter(it)) }
        return Data(examples = examples, dictionary = dictionary, adapters = adapters)
    }
}
