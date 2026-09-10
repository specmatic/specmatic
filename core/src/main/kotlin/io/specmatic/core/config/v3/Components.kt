package io.specmatic.core.config.v3

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.ConfigPathMapper
import java.io.File
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.validate
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.Examples
import io.specmatic.core.config.v3.components.runOptions.ContextDependentRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptions
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.validation.ConfigValidationOutput

data class Components(
    val sources: Map<String, SourceV3>? = null,
    val services: Map<String, CommonServiceConfig<ContextDependentRunOptions, ContextDependentSettings>>? = null,
    val runOptions: Map<String, RunOptions>? = null,
    val examples: Examples? = null,
    val dictionaries: Map<String, Dictionary>? = null,
    val adapters: Map<String, Adapter>? = null,
    val certificates: Map<String, HttpsConfiguration>? = null,
    val settings: Map<String, Settings>? = null,
) {
    fun validate(context: ValidationContext): List<ConfigValidationOutput> {
        return buildList {
            services.orEmpty().forEach { (name, service) ->
                addAll(service.validate(context.child("services").child(name)))
            }

            examples?.let { addAll(it.validate(context.child("examples"))) }
            runOptions.orEmpty().forEach { (name, options) ->
                addAll(options.validate(context.child("runOptions").child(name)))
            }
        }
    }

    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): Components {
        val mappedSources = sources?.mapValues { (name, source) ->
            source.mapPaths(mapper.child("sources").child(name), configDirectory)
        }

        val mappedServices = services?.mapValues { (name, service) ->
            service.mapPaths(
                configDirectory = configDirectory,
                sourceReferences = mappedSources.orEmpty(),
                mapper = mapper.child("services").child(name),
            )
        }

        return copy(
            sources = mappedSources,
            services = mappedServices,
            examples = examples?.mapPaths(mapper.child("examples"), configDirectory),
            adapters = adapters?.mapValues { (name, adapter) ->
                adapter.mapPaths(mapper.child("adapters").child(name), configDirectory)
            },
            runOptions = runOptions?.mapValues { (name, options) ->
                options.mapPaths(mapper.child("runOptions").child(name), configDirectory)
            },
            certificates = certificates?.mapValues { (name, certificate) ->
                certificate.mapPaths(mapper.child("certificates").child(name), configDirectory)
            },
            dictionaries = dictionaries?.mapValues { (name, dictionary) ->
                dictionary.mapPaths(mapper.child("dictionaries").child(name).child("path"), configDirectory)
            },
            settings = settings?.mapValues { (name, settings) ->
                when (settings) {
                    is ContextDependentSettings -> settings
                    is ConcreteSettings -> settings.mapPaths(mapper.child("settings").child(name), configDirectory)
                }
            },
        )
    }
}
