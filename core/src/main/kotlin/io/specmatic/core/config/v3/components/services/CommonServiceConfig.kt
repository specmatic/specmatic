package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.resolveElseThrow
import java.io.File

data class CommonServiceConfig<RunOptions : Any, Settings : Any>(
    val description: String? = null,
    val definitions: List<Definition>,
    val runOptions: RefOrValue<RunOptions>? = null,
    val data: Data? = null,
    val settings: RefOrValue<Settings>? = null
) {
    fun withCanonicalizedDefinitionFilesystemSources(
        resolver: RefOrValueResolver,
        workingDirectory: File
    ): CommonServiceConfig<RunOptions, Settings> {
        val updatedDefinitions = definitions.map { wrappedDefinition ->
            val definition = wrappedDefinition.definition
            val source = definition.source.resolveElseThrow(resolver). withCanonicalizedSources(workingDirectory)
            wrappedDefinition.copy(definition = definition.copy(source = RefOrValue.Value(source)))
        }

        return copy(definitions = updatedDefinitions)
    }
}
