package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.APIKeySecuritySchemeConfiguration
import io.specmatic.core.BasicAuthSecuritySchemeConfiguration
import io.specmatic.core.BearerSecuritySchemeConfiguration
import io.specmatic.core.OAuth2SecuritySchemeConfiguration
import io.specmatic.core.SecurityConfiguration
import io.specmatic.core.SecuritySchemeConfiguration
import io.specmatic.core.config.OpenAPITestConfig as LegacyOpenAPITestConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import io.specmatic.core.config.v3.components.SecuritySchemeType
import io.specmatic.core.config.v3.components.runOptions.AsyncApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlTestConfig
import io.specmatic.core.config.v3.components.runOptions.OpenApiRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.OpenApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.ProtobufTestConfig
import io.specmatic.core.config.v3.components.runOptions.RunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.runOptions.WsdlRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.WsdlTestConfig
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.reporter.model.SpecType

class SystemUnderTestMapper {
    fun mapFrom(view: LegacyConfigView, source: SourceMigrationBuilder): TestServiceConfig? {
        val testDefinitions = source.buildTestMigrations(view.sources)
        val specTypesByPath = testDefinitions.mergeAllSpecTypesByPath()
        val specIdsByPath = testDefinitions.specIdsByPath()
        if (testDefinitions.isEmpty()) return null

        val runOptionsMapper = RunOptionsMapper(defaultHostForPortOnly = "localhost", specIdsByPath = specIdsByPath, specTypesByPath = specTypesByPath)
        val runOptionsOverrides = view.sources
            .flatMap { it.test.orEmpty() }
            .fold(runOptionsMapper) { acc, config -> acc.mergeConfig(config) }
            .mergeGlobalOpenApi(
                overlayFilePath = view.testConfig?.overlayFilePath,
                securitySchemes = view.security?.toSecuritySchemesV3(),
            )

        val openApiConfigExamples = extractExamplesFromTestConfigs(view)
        val runOptions = buildRunOptions(view, testDefinitions, runOptionsOverrides).dropNoOpSpecificationOverrides()
        val definitions = testDefinitions.map { migration ->
            migration.definition.dropSpecificationIdsWithoutOverrides(runOptions::hasSpecOverride)
        }

        val service = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = definitions,
            runOptions = RefOrValue.Value(runOptions),
            data = DataSectionMapper().mapFrom(
                exampleDirectories = view.globalExamples + openApiConfigExamples,
                dictionaryPath = view.stubConfig?.getDictionary()
            ),
        )

        return TestServiceConfig(service = RefOrValue.Value(service))
    }

    private fun List<SourceMigration.TestSourceMigration>.mergeAllSpecTypesByPath(): Map<String, SpecType> {
        return fold(mapOf()) { acc, migration ->
            acc.plus(migration.specTypesByPath)
        }
    }

    private fun List<SourceMigration.TestSourceMigration>.specIdsByPath(): Map<String, String> {
        return fold(mapOf()) { acc, migration ->
            acc.plus(migration.specIdsByPath)
        }
    }

    private fun buildRunOptions(view: LegacyConfigView, migrations: List<SourceMigration.TestSourceMigration>, overrides: RunOptionsMapper): TestRunOptions {
        return TestRunOptions(
            wsdl = buildWsdlTestConfig(view, migrations, overrides),
            openapi = buildOpenApiTestConfig(view, migrations, overrides),
            asyncapi = AsyncApiTestConfig(specs = overrides.asyncApi.values.map(::RunOptionsSpecifications)),
            protobuf = ProtobufTestConfig(specs = overrides.protobuf.values.map(::RunOptionsSpecifications)),
            graphqlsdl = GraphQLSdlTestConfig(specs = overrides.graphQl.values.map(::RunOptionsSpecifications)),
        )
    }

    private fun buildOpenApiTestConfig(view: LegacyConfigView, migrations: List<SourceMigration.TestSourceMigration>, overrides: RunOptionsMapper): OpenApiTestConfig {
        if (migrations.none { it.hasSpecType(SpecType.OPENAPI) }) return OpenApiTestConfig()
        return OpenApiTestConfig(
            workflow = view.workflow,
            filter = view.testConfig?.filter,
            baseUrl = view.testConfig?.baseUrl,
            swaggerUrl = view.testConfig?.swaggerUrl,
            actuatorUrl = view.testConfig?.actuatorUrl,
            swaggerUiBaseUrl = view.testConfig?.swaggerUIBaseURL,
            cert = view.testConfig?.https?.let { RefOrValue.Value(it) },
            specs = overrides.openApi.values.map(::OpenApiRunOptionsSpecifications),
        )
    }

    private fun buildWsdlTestConfig(view: LegacyConfigView, migrations: List<SourceMigration.TestSourceMigration>, overrides: RunOptionsMapper): WsdlTestConfig {
        if (migrations.none { it.hasSpecType(SpecType.WSDL) }) return WsdlTestConfig()
        return WsdlTestConfig(
            baseUrl = view.testConfig?.baseUrl,
            cert = view.testConfig?.https?.let { RefOrValue.Value(it) },
            specs = overrides.wsdl.values.map(::WsdlRunOptionsSpecifications)
        )
    }

    private fun SecurityConfiguration.toSecuritySchemesV3(): Map<String, SecuritySchemeConfigurationV3>? {
        return openAPI?.securitySchemes?.mapValues { (_, scheme) -> scheme.toSecuritySchemeV3() }
    }

    private fun SecuritySchemeConfiguration.toSecuritySchemeV3(): SecuritySchemeConfigurationV3 {
        return when (this) {
            is OAuth2SecuritySchemeConfiguration ->
                SecuritySchemeConfigurationV3(SecuritySchemeType.OAUTH2, token)
            is BasicAuthSecuritySchemeConfiguration ->
                SecuritySchemeConfigurationV3(SecuritySchemeType.BASIC_AUTH, token)
            is BearerSecuritySchemeConfiguration ->
                SecuritySchemeConfigurationV3(SecuritySchemeType.BEARER, token)
            is APIKeySecuritySchemeConfiguration ->
                SecuritySchemeConfigurationV3(SecuritySchemeType.API_KEY, value)
        }
    }

    private fun extractExamplesFromTestConfigs(view: LegacyConfigView): List<String> {
        return view.sources
            .flatMap { it.test.orEmpty() }
            .asConfigValues()
            .flatMap { extractExamplesFromTestConfig(it).asSequence() }
            .distinct()
            .toList()
    }

    private fun List<SpecExecutionConfig>.asConfigValues(): Sequence<SpecExecutionConfig.ConfigValue> {
        return asSequence().mapNotNull { it as? SpecExecutionConfig.ConfigValue }
    }

    private fun extractExamplesFromTestConfig(configValue: SpecExecutionConfig.ConfigValue): List<String> {
        return runCatching { LegacyOpenAPITestConfig.from(configValue.config).examples.orEmpty() }.getOrDefault(emptyList())
    }
}

internal fun Definition.dropSpecificationIdsWithoutOverrides(hasSpecOverride: (String) -> Boolean): Definition {
    return copy(
        definition = definition.copy(
            specs = definition.specs.map { specificationDefinition ->
                val objectDefinition = specificationDefinition as? SpecificationDefinition.ObjectValue ?: return@map specificationDefinition
                val specId = objectDefinition.spec.id ?: return@map specificationDefinition
                if (hasSpecOverride(specId)) return@map specificationDefinition
                if (objectDefinition.hasNoData()) return@map SpecificationDefinition.StringValue(objectDefinition.spec.path)
                objectDefinition.copy(spec = objectDefinition.spec.copy(id = null))
            }
        )
    )
}
