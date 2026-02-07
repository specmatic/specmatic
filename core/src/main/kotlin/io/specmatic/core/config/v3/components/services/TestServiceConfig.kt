package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.config.nonNullElse
import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.SpecmaticConfigV3Resolver
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.OpenApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.resolveElseThrow
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.isOpenAPI
import java.io.File

data class TestServiceConfig(val service: RefOrValue<CommonServiceConfig<TestRunOptions, TestSettings>>) {
    @JsonIgnore
    fun getSpecDefinitionFor(specFile: File, resolver: RefOrValueResolver): SpecificationDefinition? {
        val serviceConfig = service.resolveElseThrow(resolver)
        return serviceConfig.definitions.map { it.definition }.firstNotNullOfOrNull { definition ->
            val source = definition.source.resolveElseThrow(resolver)
            definition.specs.firstOrNull { specDefinition -> specDefinition.matchesFile(source, specFile) }
        }
    }

    @JsonIgnore
    fun getSettings(mergeWith: TestSettings? = null, resolver: RefOrValueResolver): TestSettings? {
        val serviceConfig = service.resolveElseThrow(resolver)
        val testSettings = serviceConfig.settings?.resolveElseThrow(resolver)
        return testSettings.nonNullElse(mergeWith, TestSettings::merge)
    }

    @JsonIgnore
    fun getRunOptions(resolver: RefOrValueResolver, specType: SpecType): IRunOptions? {
        val serviceConfig = service.resolveElseThrow(resolver)
        val runOptions = serviceConfig.runOptions?.resolveElseThrow(resolver) ?: return null
        return runOptions.getRunOptionsFor(specType)
    }

    @JsonIgnore
    fun getOpenApiTestConfig(resolver: RefOrValueResolver): OpenApiTestConfig? {
        val runOptions = getRunOptions(resolver, SpecType.OPENAPI) ?: return null
        return runOptions as? OpenApiTestConfig
    }

    @JsonIgnore
    fun getSources(resolver: RefOrValueResolver): List<SourceV3> {
        val service = service.resolveElseThrow(resolver)
        return service.definitions.map { it.definition }.map { definition ->
            definition.source.resolveElseThrow(resolver)
        }
    }

    @JsonIgnore
    fun getSourcesContaining(specFile: File, resolver: RefOrValueResolver): SourceV3? {
        val service = service.resolveElseThrow(resolver)
        return service.definitions.map { it.definition }.firstNotNullOfOrNull { definition ->
            val source = definition.source.resolveElseThrow(resolver)
            val containsSpec = definition.specs.any { it.matchesFile(source, specFile) }
            source.takeIf { containsSpec }
        }
    }

    @JsonIgnore
    fun getSpecificationSources(resolver: RefOrValueResolver, testSettings: TestSettings?): Map<SourceV3, List<SpecificationSourceEntry>> {
        val service = service.resolveElseThrow(resolver)
        return service.definitions.map { it.definition }.associate { definition ->
            val source = definition.source.resolveElseThrow(resolver)
            val resilientSuite = testSettings?.resiliencyTests
            val examples = getExampleDirs(resolver)
            source to definition.specs.map {
                it.toSpecificationSource(source, resilientSuite, examples) { spec ->
                    getFirstBaseUrlFromRunOpts(spec, resolver)
                }
            }
        }
    }

    @JsonIgnore
    fun getExampleDirs(resolver: RefOrValueResolver): List<String> {
        val service = service.resolveElseThrow(resolver)
        return service.data?.toExampleDirs(resolver).orEmpty()
    }

    @JsonIgnore
    fun getAllSpecifications(resolver: RefOrValueResolver): List<String> {
        val service = service.resolveElseThrow(resolver)
        return service.definitions.flatMap { defRef ->
            val definition = defRef.definition
            definition.specs.map { it.getSpecificationPath() }
        }
    }

    fun copyResiliencyTestsConfig(resolver: RefOrValueResolver, resiliencyTestSuite: ResiliencyTestSuite): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val settings = service.settings?.resolveElseThrow(resolver) ?: TestSettings()
        val updatedSettings = settings.copy(resiliencyTests = resiliencyTestSuite)
        return this.copy(service = RefOrValue.Value(service.copy(settings = RefOrValue.Value(updatedSettings))))
    }

    fun withTestMode(resolver: RefOrValueResolver, strictMode: Boolean?, lenientMode: Boolean): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val settings = service.settings?.resolveElseThrow(resolver) ?: TestSettings()
        val updatedSettings = TestSettings(strictMode = strictMode, lenientMode = lenientMode).merge(settings)
        return this.copy(service = RefOrValue.Value(service.copy(settings = RefOrValue.Value(updatedSettings))))
    }

    fun withTestTimeout(resolver: RefOrValueResolver, timeout: Long?): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val settings = service.settings?.resolveElseThrow(resolver) ?: TestSettings()
        val updatedSettings = TestSettings(timeoutInMilliseconds = timeout).merge(settings)
        return this.copy(service = RefOrValue.Value(service.copy(settings = RefOrValue.Value(updatedSettings))))
    }

    fun withTestFilter(resolver: RefOrValueResolver, filter: String): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val runOpts = service.runOptions?.resolveElseThrow(resolver) ?: TestRunOptions()
        val openApiRunOpts = runOpts.openapi ?: return this
        val updatedOpenApiRunOpts = openApiRunOpts.copy(filter = filter)
        val updatedRunOpts = runOpts.copy(openapi = updatedOpenApiRunOpts)
        return copy(service = RefOrValue.Value(service.copy(runOptions = RefOrValue.Value(updatedRunOpts))))
    }

    fun withMatchBranch(resolver: RefOrValueResolver, matchBranch: Boolean): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val updatedDefinition = service.definitions.map { wrappedDefinition ->
            val definition = wrappedDefinition.definition
            val source = definition.source.resolveElseThrow(resolver).withMatchBranch(matchBranch)
            wrappedDefinition.copy(definition = definition.copy(source = RefOrValue.Value(source)))
        }
        return this.copy(service = RefOrValue.Value(service.copy(definitions = updatedDefinition)))
    }

    fun withExamples(resolver: SpecmaticConfigV3Resolver, exampleDirectories: List<String>): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val existingData = service.data ?: Data()
        val existingExamples = existingData.examples?.resolveElseThrow(resolver).orEmpty()
        val updatedExamples = existingExamples.plus(RefOrValue.Value(ExampleDirectories(directories = exampleDirectories)))
        return this.copy(
            service = RefOrValue.Value(
                value = service.copy(data = existingData.copy(examples = RefOrValue.Value(updatedExamples)))
            )
        )
    }

    @JsonIgnore
    private fun getFirstBaseUrlFromRunOpts(specFile: File, resolver: RefOrValueResolver): String? {
        val specTypesToCheck = when {
            specFile.extension == "wsdl" -> listOf(SpecType.WSDL)
            specFile.extension == "proto" -> listOf(SpecType.PROTOBUF)
            specFile.extension in setOf("graphql", "graphqls") -> listOf(SpecType.GRAPHQL)
            isOpenAPI(specFile.canonicalPath, logFailure = false) -> listOf(SpecType.OPENAPI)
            else -> listOf(SpecType.OPENAPI, SpecType.ASYNCAPI)
        }

        return specTypesToCheck.firstNotNullOfOrNull { getRunOptions(resolver, it)?.baseUrl }
    }
}
