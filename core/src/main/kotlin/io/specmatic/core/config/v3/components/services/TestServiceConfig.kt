package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.config.nonNullElse
import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.SpecmaticConfigV3Resolver
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolveElseThrow
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.OpenApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.runOptions.ConfigWithCert
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.determineSpecTypeFor
import io.specmatic.core.config.wrapFully
import io.specmatic.reporter.model.SpecType
import java.io.File

data class TestServiceConfig(val service: TemplateOrValue<RefOrValue<CommonServiceConfig<TestRunOptions, TestSettings>>>) {
    @JsonIgnore
    fun getSpecDefinitionFor(specFile: File, resolver: RefOrValueResolver): SpecificationDefinition? {
        val serviceConfig = service.resolveElseThrow(resolver)
        return serviceConfig.getDefinitions().firstNotNullOfOrNull { definition ->
            val source = definition.getSource(resolver)
            definition.getSpecs().firstOrNull { specDefinition -> specDefinition.matchesFile(source, specFile) }
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
        return service.getDefinitions().map { definition ->
            definition.getSource(resolver)
        }
    }

    @JsonIgnore
    fun getSourcesContaining(specFile: File, resolver: RefOrValueResolver): SourceV3? {
        val service = service.resolveElseThrow(resolver)
        return service.getDefinitions().firstNotNullOfOrNull { definition ->
            val source = definition.getSource(resolver)
            val containsSpec = definition.getSpecs().any { it.matchesFile(source, specFile) }
            source.takeIf { containsSpec }
        }
    }

    @JsonIgnore
    fun getSpecificationSources(resolver: RefOrValueResolver, testSettings: TestSettings?): Map<SourceV3, List<SpecificationSourceEntry>> {
        val service = service.resolveElseThrow(resolver)
        return service.getDefinitions().flatMap { definition ->
            val examples = getExampleDirs(resolver)
            val resilientSuite = testSettings?.getSchemaResiliencyTests()
            val source = definition.getSource(resolver)
            definition.getSpecs()
                .map { it.toSpecificationSource(source, resilientSuite, examples) { specId, file -> getFirstBaseUrlFromRunOpts(specId, file, resolver) } }
                .map { spec -> source to spec }
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    @JsonIgnore
    fun getExampleDirs(specFile: File, resolver: RefOrValueResolver): List<String>? {
        val service = service.resolveElseThrow(resolver)
        val containsDefinition = service.getDefinitions().any { definition ->
            val source = definition.getSource(resolver)
            definition.getSpecs().any { it.matchesFile(source, specFile) }
        }

        if (!containsDefinition) return null
        return service.getData()?.toExampleDirs(resolver)
    }

    @JsonIgnore
    fun getExampleDirs(resolver: RefOrValueResolver): List<String>? {
        val service = service.resolveElseThrow(resolver)
        return service.getData()?.toExampleDirs(resolver)
    }

    @JsonIgnore
    fun getDictionaryPath(resolver: RefOrValueResolver): String? {
        val service = service.resolveElseThrow(resolver)
        val data = service.getData() ?: return null
        return data.getDictionary(resolver)?.getPath()
    }

    @JsonIgnore
    fun getCerts(resolver: RefOrValueResolver): List<Pair<String, HttpsConfiguration>> {
        val serviceConfig = service.resolveElseThrow(resolver)
        return serviceConfig.getDefinitions().flatMap { definition ->
            definition.getSpecs().mapNotNull { specDefinition ->
                val specPath = specDefinition.getSpecificationPath().lowercase()
                val specType = if (specPath.endsWith(".wsdl")) SpecType.WSDL else SpecType.OPENAPI
                val runOptions = getRunOptions(resolver, specType) ?: return@mapNotNull null
                val runCert = (runOptions as? ConfigWithCert)?.cert?.resolveElseThrow(resolver) ?: return@mapNotNull null
                val specId = specDefinition.getSpecificationId()
                val runOptionSpecOverride = specId?.let(runOptions::getMatchingSpecification)
                val baseUrl = runOptionSpecOverride?.getBaseUrl("localhost") ?: runOptions.getBaseUrlIfExists() ?: return@mapNotNull null
                Pair(baseUrl, runCert)
            }
        }
    }

    fun copyResiliencyTestsConfig(resolver: RefOrValueResolver, resiliencyTestSuite: ResiliencyTestSuite): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val settings = service.settings?.resolveElseThrow(resolver) ?: TestSettings()
        val updatedSettings = settings.copy(schemaResiliencyTests = TemplateOrValue.Value(resiliencyTestSuite))
        return this.copy(service = TemplateOrValue.Value(RefOrValue.Value(service.copy(settings = TemplateOrValue.Value(RefOrValue.Value(updatedSettings))))))
    }

    fun withTestMode(resolver: RefOrValueResolver, strictMode: Boolean?, lenientMode: Boolean?): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val settings = service.settings?.resolveElseThrow(resolver) ?: TestSettings()
        val updatedSettings = TestSettings(strictMode = strictMode?.let { TemplateOrValue.Value(it) }, lenientMode = lenientMode?.let { TemplateOrValue.Value(it) }).merge(settings)
        return copy(service = TemplateOrValue.Value(RefOrValue.Value(service.copy(settings = TemplateOrValue.Value(RefOrValue.Value(updatedSettings))))))
    }

    fun withTestTimeout(resolver: RefOrValueResolver, timeout: Long): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val settings = service.settings?.resolveElseThrow(resolver) ?: TestSettings()
        val updatedSettings = TestSettings(timeoutInMilliseconds = TemplateOrValue.Value(timeout)).merge(settings)
        return copy(service = TemplateOrValue.Value(RefOrValue.Value(service.copy(settings = TemplateOrValue.Value(RefOrValue.Value(updatedSettings))))))
    }

    fun withBaseUrl(resolver: SpecmaticConfigV3Resolver, testBaseURL: String): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val runOpts = service.runOptions?.resolveElseThrow(resolver) ?: TestRunOptions()
        val openApiRunOpts = runOpts.openapi?.resolve()?.copy(baseUrl = TemplateOrValue.Value(testBaseURL)) ?: OpenApiTestConfig(baseUrl = TemplateOrValue.Value(testBaseURL))
        val updatedRunOpts = runOpts.copy(openapi = TemplateOrValue.Value(openApiRunOpts))
        return copy(service = TemplateOrValue.Value(RefOrValue.Value(service.copy(runOptions = TemplateOrValue.Value(RefOrValue.Value(updatedRunOpts))))))
    }

    fun withTestFilter(resolver: RefOrValueResolver, filter: String): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val runOpts = service.runOptions?.resolveElseThrow(resolver) ?: TestRunOptions()
        val openApiRunOpts = runOpts.openapi?.resolve() ?: OpenApiTestConfig()
        val updatedOpenApiRunOpts = openApiRunOpts.copy(filter = TemplateOrValue.Value(filter))
        val updatedRunOpts = runOpts.copy(openapi = TemplateOrValue.Value(updatedOpenApiRunOpts))
        return copy(service = TemplateOrValue.Value(RefOrValue.Value(service.copy(runOptions = TemplateOrValue.Value(RefOrValue.Value(updatedRunOpts))))))
    }

    fun withMatchBranch(resolver: RefOrValueResolver, matchBranch: Boolean): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val updatedDefinition = service.getDefinitions().map { wrappedDefinition ->
            val definition = wrappedDefinition.definition
            val source = definition.resolve().getSource(resolver).withMatchBranch(matchBranch)
            wrappedDefinition.copy(definition = TemplateOrValue.Value(definition.resolve().copy(source = TemplateOrValue.Value(RefOrValue.Value(source)))))
        }

        return copy(service = TemplateOrValue.Value(RefOrValue.Value(service.copy(definitions = TemplateOrValue.Value(updatedDefinition.map { TemplateOrValue.Value(it) })))))
    }

    fun withExamples(resolver: SpecmaticConfigV3Resolver, exampleDirectories: List<String>): TestServiceConfig {
        val service = this.service.resolveElseThrow(resolver)
        val existingData = service.getData() ?: Data()
        val existingExamples = existingData.examples?.resolveElseThrow(resolver).orEmpty()
        val updatedExamples = existingExamples.plus(RefOrValue.Value(ExampleDirectories(directories = exampleDirectories.wrapFully())))
        return this.copy(
            service = TemplateOrValue.Value(RefOrValue.Value(
                value = service.copy(
                    data = TemplateOrValue.Value(existingData.copy(examples = TemplateOrValue.Value(RefOrValue.Value(updatedExamples))))
                ))
            )
        )
    }

    @JsonIgnore
    private fun getFirstBaseUrlFromRunOpts(specId: String?, specFile: File, resolver: RefOrValueResolver): String? {
        val specTypesToCheck = determineSpecTypeFor(specFile)
        return specTypesToCheck.firstNotNullOfOrNull {
            val runOptions = getRunOptions(resolver, it) ?: return@firstNotNullOfOrNull null
            val runOptionSpecOverride = specId?.let(runOptions::getMatchingSpecification)
            runOptionSpecOverride?.getBaseUrl("localhost") ?: runOptions.getBaseUrlIfExists()
        }
    }
}
