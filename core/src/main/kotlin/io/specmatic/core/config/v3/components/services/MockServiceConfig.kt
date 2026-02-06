package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.Source
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.nonNullElse
import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.SpecmaticConfigV3Resolver
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.OpenApiMockConfig
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.resolveElseThrow
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.isSupportedAPISpecification
import java.io.File

data class MockServiceConfig(val services: List<Value>, val data: Data? = null, val settings: RefOrValue<MockSettings>?) {
    data class Value(val service: RefOrValue<CommonServiceConfig<MockRunOptions, MockSettings>>)

    @JsonIgnore
    fun getService(specFile: File, resolver: RefOrValueResolver): CommonServiceConfig<MockRunOptions, MockSettings>? {
        return this.services.firstOrNull { service ->
            val serviceConfig = service.service.resolveElseThrow(resolver)
            serviceConfig.definitions.map { it.definition }.any { definition ->
                val source = definition.source.resolveElseThrow(resolver)
                definition.specs.any { specDefinition -> specDefinition.matchesFile(source, specFile) }
            }
        }?.service?.resolveElseThrow(resolver)
    }

    @JsonIgnore
    fun getSpecDefinitionFor(specFile: File, service: CommonServiceConfig<MockRunOptions, MockSettings>, resolver: RefOrValueResolver): SpecificationDefinition? {
        return service.definitions.map { it.definition }.firstNotNullOfOrNull { definition ->
            val source = definition.source.resolveElseThrow(resolver)
            definition.specs.firstOrNull { specDefinition -> specDefinition.matchesFile(source, specFile) }
        }
    }

    @JsonIgnore
    fun getSettings(service: CommonServiceConfig<MockRunOptions, MockSettings>, mergeWith: MockSettings? = null, resolver: RefOrValueResolver): MockSettings? {
        val mockSettings = settings?.resolveElseThrow(resolver)
        val mergedMockSettings = mockSettings.nonNullElse(mergeWith, MockSettings::merge)
        val serviceMockSettings = service.settings?.resolveElseThrow(resolver)
        return serviceMockSettings.nonNullElse(mergedMockSettings, MockSettings::merge)
    }

    @JsonIgnore
    fun getRunOptions(service: CommonServiceConfig<MockRunOptions, MockSettings>, resolver: RefOrValueResolver, specType: SpecType): IRunOptions? {
        val runOptions = service.runOptions?.resolveElseThrow(resolver)
        return runOptions?.getRunOptionsFor(specType)
    }

    @JsonIgnore
    fun getSources(resolver: RefOrValueResolver): List<SourceV3> {
        return services.map { it.service }.flatMap { serviceRef ->
            val service = serviceRef.resolveElseThrow(resolver)
            service.definitions.map { defRef ->
                val definition = defRef.definition
                definition.source.resolveElseThrow(resolver)
            }
        }
    }

    @JsonIgnore
    fun getSpecificationSources(resolver: RefOrValueResolver): Map<SourceV3, List<SpecificationSourceEntry>> {
        return services.map { it.service }.flatMap { serviceRef ->
            val service = serviceRef.resolveElseThrow(resolver)
            service.definitions.flatMap { defRef ->
                val definition = defRef.definition
                val source = definition.source.resolveElseThrow(resolver)
                val examples = service.data?.toExampleDirs(resolver).orEmpty()
                definition.specs.map {
                    it.toSpecificationSource(source, null, examples) { spec -> getFirstBaseUrlFromRunOpts(spec, service, resolver) }
                }.map { spec -> source to spec }
            }
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    @JsonIgnore
    fun getExampleDirs(specFile: File, resolver: RefOrValueResolver): List<String> {
        val service = getService(specFile, resolver)
        return service?.data?.toExampleDirs(resolver).orEmpty()
    }

    @JsonIgnore
    fun getAllSpecifications(resolver: RefOrValueResolver): List<String> {
        return services.map { it.service }.flatMap { serviceRef ->
            val service = serviceRef.resolveElseThrow(resolver)
            service.definitions.flatMap { defRef ->
                val definition = defRef.definition
                definition.specs.map { it.getSpecificationPath() }
            }
        }
    }

    @JsonIgnore
    private fun getFirstBaseUrlFromRunOpts(specFile: File, service: CommonServiceConfig<MockRunOptions, MockSettings>, resolver: RefOrValueResolver): String? {
        val specTypeToCheck = when {
            isSupportedAPISpecification(specFile.canonicalPath) -> SpecType.OPENAPI
            specFile.extension in setOf("graphql", "graphqls") -> SpecType.GRAPHQL
            specFile.extension == "proto" -> SpecType.PROTOBUF
            else -> SpecType.ASYNCAPI
        }

        val runOpts = getRunOptions(service, resolver, specTypeToCheck) ?: return null
        return runOpts.baseUrl
    }

    @JsonIgnore
    fun getSourcesContaining(specFile: File, resolver: SpecmaticConfigV3Resolver): SourceV3? {
        return services.map { it.service }.firstNotNullOfOrNull { serviceRef ->
            val service = serviceRef.resolveElseThrow(resolver)
            service.definitions.map { it.definition }.firstNotNullOfOrNull { definition ->
                val source = definition.source.resolveElseThrow(resolver)
                val containsSpec = definition.specs.any { it.matchesFile(source, specFile) }
                source.takeIf { containsSpec }
            }
        }
    }

    @JsonIgnore
    fun getCerts(resolver: RefOrValueResolver): List<Pair<String, HttpsConfiguration>> {
        return services.map { it.service.resolveElseThrow(resolver) }.mapNotNull { service ->
            val runOpts = getRunOptions(service, resolver, SpecType.OPENAPI) as? OpenApiMockConfig ?: return@mapNotNull null
            val cert = runOpts.cert?.resolveElseThrow(resolver) ?: return@mapNotNull null
            Pair(runOpts.baseUrl, cert)
        }
    }

    fun withStubMode(resolver: SpecmaticConfigV3Resolver, strictMode: Boolean): MockServiceConfig {
        val settings = this.settings?.resolveElseThrow(resolver) ?: MockSettings()
        val updatedSettings = MockSettings(strictMode = strictMode).merge(settings)
        return this.copy(settings = RefOrValue.Value(updatedSettings))
    }

    fun withStubFilter(resolver: SpecmaticConfigV3Resolver, filter: String): MockServiceConfig {
        return copy(
            services = services.map { value ->
                val service = value.service.resolveElseThrow(resolver)
                val runOpts = service.runOptions?.resolveElseThrow(resolver) ?: MockRunOptions()
                val openApiRunOpts = runOpts.openapi ?: return@map value
                val updatedOpenApiRunOpts = openApiRunOpts.copy(filter = filter)
                val updatedRunOpts = runOpts.copy(openapi = updatedOpenApiRunOpts)
                value.copy(service = RefOrValue.Value(service.copy(runOptions = RefOrValue.Value(updatedRunOpts))))
            }
        )
    }

    fun withGlobalDelay(resolver: SpecmaticConfigV3Resolver, delayInMilliseconds: Long): MockServiceConfig {
        val settings = this.settings?.resolveElseThrow(resolver) ?: MockSettings()
        val updatedSettings = MockSettings(delayInMilliseconds = delayInMilliseconds).merge(settings)
        return this.copy(settings = RefOrValue.Value(updatedSettings))
    }

    fun withMatchBranch(resolver: RefOrValueResolver, matchBranch: Boolean): MockServiceConfig {
        return copy(
            services = services.map { value ->
                val service = value.service.resolveElseThrow(resolver)
                val updatedDefinitions = service.definitions.map { wrappedDefinition ->
                    val definition = wrappedDefinition.definition
                    val source = definition.source.resolveElseThrow(resolver).withMatchBranch(matchBranch)
                    wrappedDefinition.copy(definition = definition.copy(source = RefOrValue.Value(source)))
                }
                value.copy(service = RefOrValue.Value(service.copy(definitions = updatedDefinitions)))
            }
        )
    }

    fun withExamples(resolver: SpecmaticConfigV3Resolver, exampleDirectories: List<String>): MockServiceConfig {
        return copy(
            services = services.map { value ->
                val service = value.service.resolveElseThrow(resolver)
                val existingData = service.data ?: Data()
                val existingExamples = existingData.examples?.resolveElseThrow(resolver).orEmpty()
                val updatedExamples = existingExamples + RefOrValue.Value(ExampleDirectories(directories = exampleDirectories))
                value.copy(service = RefOrValue.Value(service.copy(data = existingData.copy(examples = RefOrValue.Value(updatedExamples)))))
            }
        )
    }
}
