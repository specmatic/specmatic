package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.ValidationContext
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.resolveElseThrow
import io.specmatic.core.config.validation.ConfigValidationOutput
import java.io.File

data class CommonServiceConfig<RunOptions : Any, Settings : Any>(
    val description: String? = null,
    val definitions: List<Definition>,
    val runOptions: RefOrValue<RunOptions>? = null,
    val data: Data? = null,
    val settings: RefOrValue<Settings>? = null
) {
    fun mapPaths(
        mapper: ConfigPathMapper,
        configDirectory: File,
        sourceReferences: Map<String, SourceV3> = emptyMap()
    ): CommonServiceConfig<RunOptions, Settings> {
        val mappedDefinitions = definitions.mapIndexed { index, definition ->
            definition.mapPaths(
                configDirectory = configDirectory,
                sourceReferences = sourceReferences,
                mapper = mapper.child("definitions").child(index),
            )
        }

        return copy(
            definitions = mappedDefinitions,
            data = data?.mapPaths(mapper.child("data"), configDirectory),
        )
    }

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

inline fun <reified R : Any, reified S : Any> CommonServiceConfig<R, S>.validate(
    context: ValidationContext,
    crossinline validateRunOptions: (R, ValidationContext) -> List<ConfigValidationOutput> = { _, _ -> emptyList() },
    crossinline validateDefinition: (Definition, R?, ValidationContext, ValidationContext) -> List<ConfigValidationOutput> = { _, _, _, _ -> emptyList() },
): List<ConfigValidationOutput> {
    val resolvedRunOpts = runCatching { runOptions?.resolveElseThrow(context.resolver) }.getOrNull()
    val runOptionsContext = context.child("runOptions").updateWithRefOrValue(runOptions)
    val definitionOutput = definitions.flatMapIndexed { index, definition ->
        val definitionContext = context.child("definitions").child(index)
        validateDefinition(definition, resolvedRunOpts, definitionContext, runOptionsContext)
    }

    val runOptionsOutput = runOptions?.let { reference ->
        context.child("runOptions").check(
            reference = reference,
            resolve = { value, resolver -> value.resolveElseThrow<R>(resolver) },
            validate = { value, valueContext -> validateRunOptions(value, valueContext) },
        )
    }.orEmpty()

    val settingsOutput = settings?.let { reference ->
        context.child("settings").check(
            reference = reference,
            resolve = { value, resolver -> value.resolveElseThrow<S>(resolver) },
        )
    }.orEmpty()

    val dataOutput = data?.validate(context.child("data")).orEmpty()
    return definitionOutput + runOptionsOutput + settingsOutput + dataOutput
}
