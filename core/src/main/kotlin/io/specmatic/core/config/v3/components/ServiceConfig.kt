package io.specmatic.core.config.v3.components

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.nonNullElse
import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.OpenApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.resolveElseThrow
import io.specmatic.reporter.model.SpecType
import java.io.File

data class CommonServiceConfig<RunOptions : Any, Settings: Any>(
    val description: String? = null,
    val definitions: List<Definition>,
    val runOptions: RefOrValue<RunOptions>? = null,
    val data: Data? = null,
    val settings: RefOrValue<Settings>? = null
)

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
}

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
    fun getHttpsConfiguration(runOptions: IRunOptions, resolver: RefOrValueResolver): HttpsConfiguration? {
        if (runOptions !is OpenApiMockConfig) return null
        return runOptions.cert?.resolveElseThrow(resolver)
    }

    @JsonIgnore
    fun getDictionary(service: CommonServiceConfig<MockRunOptions, MockSettings>, resolver: RefOrValueResolver): String? {
        return service.data?.dictionary?.resolveElseThrow(resolver)?.path
    }
}
