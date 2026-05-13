package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.config.Switch
import io.specmatic.core.config.OpenAPIMockConfig as LegacyOpenAPIMockConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.runOptions.AsyncApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlMockConfig
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.OpenApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.OpenApiRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.ProtobufMockConfig
import io.specmatic.core.config.v3.components.runOptions.RunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.WsdlMockConfig
import io.specmatic.core.config.v3.components.runOptions.WsdlRunOptionsSpecifications
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.MockServiceConfig
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.reporter.model.SpecType

class DependenciesMapper {
    fun mapFrom(view: LegacyConfigView, source: SourceMigrationBuilder): MockServiceConfig? {
        val migrations = source.buildMockMigrations(view.sources)
        if (migrations.isEmpty()) return null

        return MockServiceConfig(
            settings = RefOrValue.Value(buildSettings(view)),
            services = migrations.map { migration ->
                createService(
                    view = view,
                    migration = migration,
                    examples = extractExamplesFromConfig(migration.config),
                )
            },
            data = DataSectionMapper().mapFrom(
                hooks = view.stubHooks,
                exampleDirectories = view.globalExamples,
                dictionaryPath = view.stubConfig?.getDictionary(),
            ),
        )
    }

    private fun buildSettings(view: LegacyConfigView): MockSettings {
        return MockSettings(
            generative = view.stubConfig?.getGenerative(),
            strictMode = view.stubConfig?.getStrictMode(),
            lenientMode = view.stubConfig?.getLenientMode(),
            delayInMilliseconds = view.stubConfig?.getDelayInMilliseconds(),
            hotReload = view.stubConfig?.getHotReload()?.let { it == Switch.enabled },
            startTimeoutInMilliseconds = view.stubConfig?.getStartTimeoutInMilliseconds(),
            gracefulRestartTimeoutInMilliseconds = view.stubConfig?.getGracefulRestartTimeoutInMilliseconds(),
        )
    }

    private fun buildRunOptions(migration: SourceMigration.MockSourceMigration, view: LegacyConfigView): MockRunOptions {
        val initialMapper = RunOptionsMapper(defaultHostForPortOnly = "0.0.0.0", specIdsByPath = migration.specIdsByPath, specTypesByPath = migration.specTypesByPath)
        val overrides = listOf(migration.config).fold(initialMapper) { acc, config ->
            acc.mergeConfig(config = config)
        }

        return MockRunOptions(
            wsdl = buildWsdlMockConfig(view, migration, overrides),
            openapi = buildOpenApiMockConfig(view, migration, overrides),
            asyncapi = AsyncApiMockConfig(specs = overrides.asyncApi.values.map(::RunOptionsSpecifications)),
            protobuf = ProtobufMockConfig(specs = overrides.protobuf.values.map(::RunOptionsSpecifications)),
            graphqlsdl = GraphQLSdlMockConfig(specs = overrides.graphQl.values.map(::RunOptionsSpecifications)),
        )
    }

    private fun buildOpenApiMockConfig(view: LegacyConfigView, migration: SourceMigration.MockSourceMigration, overrides: RunOptionsMapper): OpenApiMockConfig {
        if (!migration.hasSpecType(SpecType.OPENAPI)) return OpenApiMockConfig()
        return OpenApiMockConfig(
            filter = view.stubConfig?.getFilter(),
            baseUrl = view.stubConfig?.getBaseUrl(),
            cert = view.stubConfig?.getHttps()?.let { RefOrValue.Value(it) },
            specs = overrides.openApi.values.map(::OpenApiRunOptionsSpecifications),
        )
    }

    private fun buildWsdlMockConfig(view: LegacyConfigView, migration: SourceMigration.MockSourceMigration, overrides: RunOptionsMapper): WsdlMockConfig {
        if (!migration.hasSpecType(SpecType.WSDL)) return WsdlMockConfig()
        return WsdlMockConfig(
            baseUrl = view.stubConfig?.getBaseUrl(),
            cert = view.stubConfig?.getHttps()?.let { RefOrValue.Value(it) },
            specs = overrides.wsdl.values.map(::WsdlRunOptionsSpecifications)
        )
    }

    private fun createService(migration: SourceMigration.MockSourceMigration, view: LegacyConfigView, examples: List<String>): MockServiceConfig.Value {
        val runOptions = buildRunOptions(migration, view).dropNoOpSpecificationOverrides()
        val definition = migration.definition.dropSpecificationIdsWithoutOverrides(runOptions::hasSpecOverride)
        val data = DataSectionMapper().mapFrom(exampleDirectories = examples)
        return MockServiceConfig.Value(
            service = RefOrValue.Value(
                CommonServiceConfig(
                    data = data,
                    definitions = listOf(definition),
                    runOptions = RefOrValue.Value(runOptions),
                )
            )
        )
    }

    private fun extractExamplesFromConfig(config: SpecExecutionConfig): List<String> {
        val configValue = config as? SpecExecutionConfig.ConfigValue ?: return emptyList()
        return runCatching { LegacyOpenAPIMockConfig.from(configValue.config).examples.orEmpty() }.getOrDefault(emptyList())
    }
}
