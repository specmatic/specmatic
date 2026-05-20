package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.APIKeySecuritySchemeConfiguration
import io.specmatic.core.BasicAuthSecuritySchemeConfiguration
import io.specmatic.core.BearerSecuritySchemeConfiguration
import io.specmatic.core.OAuth2SecuritySchemeConfiguration
import io.specmatic.core.SecurityConfiguration
import io.specmatic.core.SecuritySchemeConfiguration
import io.specmatic.core.TemplatableValue
import io.specmatic.core.config.ConfigTemplateMetadata
import io.specmatic.core.config.OpenAPITestConfig as LegacyOpenAPITestConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.determineSpecTypeFor
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
import java.io.File

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

    fun transferMetadata(view: LegacyConfigView, metadata: ConfigTemplateMetadata): ConfigTemplateMetadata {
        val state = view.sources.foldIndexed(TemplateTransferState(metadata)) { sourceIndex, sourceState, source ->
            source.test.orEmpty().foldIndexed(sourceState) { configIndex, configState, config ->
                configState.transferProviderRunOptions(sourceIndex, configIndex, config)
            }
        }.includeGlobalOpenApiSpecs(view)

        return state.metadata
            .transferTestRunOptions()
            .transferWorkflow()
            .transferOverlayToOpenApiSpecs(state.openApiSpecIndices.values)
            .transferSecurityToOpenApiSpecs(state.openApiSpecIndices.values)
            .transferTestData(view)
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
            filter = TemplatableValue.of(view.testConfig?.filter),
            baseUrl = TemplatableValue.of(view.testConfig?.baseUrl),
            swaggerUrl = TemplatableValue.of(view.testConfig?.swaggerUrl),
            actuatorUrl = TemplatableValue.of(view.testConfig?.actuatorUrl),
            swaggerUiBaseUrl = TemplatableValue.of(view.testConfig?.swaggerUIBaseURL),
            cert = view.testConfig?.https?.let { RefOrValue.Value(it) },
            specs = overrides.openApi.values.map(::OpenApiRunOptionsSpecifications),
        )
    }

    private fun buildWsdlTestConfig(view: LegacyConfigView, migrations: List<SourceMigration.TestSourceMigration>, overrides: RunOptionsMapper): WsdlTestConfig {
        if (migrations.none { it.hasSpecType(SpecType.WSDL) }) return WsdlTestConfig()
        return WsdlTestConfig(
            baseUrl = TemplatableValue.of(view.testConfig?.baseUrl),
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

    private data class TemplateTransferState(
        val metadata: ConfigTemplateMetadata,
        val openApiSpecIndices: Map<String, Int> = linkedMapOf(),
        val wsdlSpecIndices: Map<String, Int> = linkedMapOf(),
        val asyncApiSpecIndices: Map<String, Int> = linkedMapOf(),
        val graphQlSpecIndices: Map<String, Int> = linkedMapOf(),
        val protobufSpecIndices: Map<String, Int> = linkedMapOf(),
    ) {
        fun transferProviderRunOptions(sourceIndex: Int, configIndex: Int, config: SpecExecutionConfig): TemplateTransferState {
            if (config is SpecExecutionConfig.StringValue) return this

            return config.specs().foldIndexed(this) { specIndexInConfig, state, specPath ->
                when (config.providerSpecTypeFor(specPath)) {
                    SpecType.OPENAPI -> state.transferSpecConfigTemplate(
                        sourceIndex = sourceIndex,
                        configIndex = configIndex,
                        specIndexInConfig = specIndexInConfig,
                        specPath = specPath,
                        config = config,
                        v3SpecType = "openapi",
                        knownIndices = state.openApiSpecIndices,
                        updateKnownIndices = { indices -> copy(openApiSpecIndices = indices) },
                    )

                    SpecType.WSDL -> state.transferSpecConfigTemplate(
                        sourceIndex = sourceIndex,
                        configIndex = configIndex,
                        specIndexInConfig = specIndexInConfig,
                        specPath = specPath,
                        config = config,
                        v3SpecType = "wsdl",
                        knownIndices = state.wsdlSpecIndices,
                        updateKnownIndices = { indices -> copy(wsdlSpecIndices = indices) },
                    )

                    SpecType.ASYNCAPI -> state.transferSpecConfigTemplate(
                        sourceIndex = sourceIndex,
                        configIndex = configIndex,
                        specIndexInConfig = specIndexInConfig,
                        specPath = specPath,
                        config = config,
                        v3SpecType = "asyncapi",
                        knownIndices = state.asyncApiSpecIndices,
                        updateKnownIndices = { indices -> copy(asyncApiSpecIndices = indices) },
                    )

                    SpecType.GRAPHQL -> state.transferSpecConfigTemplate(
                        sourceIndex = sourceIndex,
                        configIndex = configIndex,
                        specIndexInConfig = specIndexInConfig,
                        specPath = specPath,
                        config = config,
                        v3SpecType = "graphqlsdl",
                        knownIndices = state.graphQlSpecIndices,
                        updateKnownIndices = { indices -> copy(graphQlSpecIndices = indices) },
                    )

                    SpecType.PROTOBUF -> state.transferSpecConfigTemplate(
                        sourceIndex = sourceIndex,
                        configIndex = configIndex,
                        specIndexInConfig = specIndexInConfig,
                        specPath = specPath,
                        config = config,
                        v3SpecType = "protobuf",
                        knownIndices = state.protobufSpecIndices,
                        updateKnownIndices = { indices -> copy(protobufSpecIndices = indices) },
                    )
                }
            }
        }

        fun includeGlobalOpenApiSpecs(view: LegacyConfigView): TemplateTransferState {
            val updatedOpenApiSpecIndices = view.sources
                .flatMap { source -> source.test.orEmpty() }
                .fold(openApiSpecIndices) { indices, config ->
                    config.specs().fold(indices) { specIndices, specPath ->
                        if (config.providerSpecTypeFor(specPath) != SpecType.OPENAPI) return@fold specIndices
                        if (specPath in specIndices) specIndices else specIndices + (specPath to specIndices.size)
                    }
                }

            return copy(openApiSpecIndices = updatedOpenApiSpecIndices)
        }

        private fun transferSpecConfigTemplate(
            sourceIndex: Int,
            configIndex: Int,
            specIndexInConfig: Int,
            specPath: String,
            config: SpecExecutionConfig,
            v3SpecType: String,
            knownIndices: Map<String, Int>,
            updateKnownIndices: TemplateTransferState.(Map<String, Int>) -> TemplateTransferState,
        ): TemplateTransferState {
            val targetIndex = knownIndices[specPath] ?: knownIndices.size
            val updatedState = if (specPath in knownIndices) this else updateKnownIndices(knownIndices + (specPath to targetIndex))

            val fieldMetadataState = config.providerSourceTemplateFields(specIndexInConfig).fold(updatedState) { state, field ->
                val targetPath = listOf("systemUnderTest", "service", "runOptions", v3SpecType, "specs", targetIndex.toString(), "spec", field.targetName)
                val updatedMetadata = providerSourcePathPrefixes(sourceIndex, "provides", configIndex).fold(state.metadata) { currentMetadata, prefix ->
                    currentMetadata.transferTemplate(prefix + field.sourceSegments, targetPath)
                }

                state.copy(metadata = updatedMetadata)
            }

            if (config !is SpecExecutionConfig.ConfigValue) return fieldMetadataState

            val targetSpecPrefix = listOf("systemUnderTest", "service", "runOptions", v3SpecType, "specs", targetIndex.toString(), "spec")
            val updatedMetadata = providerSourcePathPrefixes(sourceIndex, "provides", configIndex).fold(fieldMetadataState.metadata) { currentMetadata, prefix ->
                currentMetadata.transferTemplatesUnder(prefix + "config", targetSpecPrefix)
            }

            return fieldMetadataState.copy(metadata = updatedMetadata)
        }
    }
}

private data class ProviderSourceTemplateField(val sourceSegments: List<String>, val targetName: String)

private fun SpecExecutionConfig.providerSourceTemplateFields(specIndexInConfig: Int): List<ProviderSourceTemplateField> {
    return when (this) {
        is SpecExecutionConfig.ConfigValue -> listOf("baseUrl", "host", "port").map { fieldName ->
            ProviderSourceTemplateField(listOf("config", fieldName), fieldName)
        }

        is SpecExecutionConfig.ObjectValue.FullUrl -> listOf(ProviderSourceTemplateField(listOf("baseUrl"), "baseUrl"))
        is SpecExecutionConfig.ObjectValue.PartialUrl -> listOfNotNull(
            ProviderSourceTemplateField(listOf("host"), "host"),
            ProviderSourceTemplateField(listOf("port"), "port"),
        )

        is SpecExecutionConfig.StringValue -> emptyList()
    }
}

private fun SpecExecutionConfig.providerSpecTypeFor(specPath: String): SpecType {
    val explicitType = (this as? SpecExecutionConfig.ConfigValue)?.specType?.let { configuredSpecType ->
        SpecType.entries.firstOrNull { specType -> specType.value.equals(configuredSpecType, ignoreCase = true) }
    }

    return explicitType ?: determineSpecTypeFor(File(specPath)).first()
}

private fun providerSourcePathPrefixes(sourceIndex: Int, entryKind: String, entryIndex: Int): List<List<String>> {
    val indexedPath = listOf("contracts", sourceIndex.toString(), entryKind, entryIndex.toString())
    return when (sourceIndex) {
        0 -> listOf(indexedPath, listOf("contracts", entryKind, entryIndex.toString()))
        else -> listOf(indexedPath)
    }
}

private fun ConfigTemplateMetadata.transferTestRunOptions(): ConfigTemplateMetadata {
    return this
        .transferTemplate(listOf("test", "baseUrl"), listOf("systemUnderTest", "service", "runOptions", "openapi", "baseUrl"))
        .transferTemplate(listOf("test", "filter"), listOf("systemUnderTest", "service", "runOptions", "openapi", "filter"))
        .transferTemplate(listOf("test", "swaggerUIBaseURL"), listOf("systemUnderTest", "service", "runOptions", "openapi", "swaggerUiBaseUrl"))
        .transferTemplate(listOf("test", "swaggerUrl"), listOf("systemUnderTest", "service", "runOptions", "openapi", "swaggerUrl"))
        .transferTemplate(listOf("test", "actuatorUrl"), listOf("systemUnderTest", "service", "runOptions", "openapi", "actuatorUrl"))
        .transferTemplatesUnder(listOf("test", "https"), listOf("systemUnderTest", "service", "runOptions", "openapi", "cert"))
}

private fun ConfigTemplateMetadata.transferWorkflow(): ConfigTemplateMetadata {
    return transferTemplatesUnder(listOf("workflow"), listOf("systemUnderTest", "service", "runOptions", "openapi", "workflow"))
}

private fun ConfigTemplateMetadata.transferOverlayToOpenApiSpecs(openApiSpecIndices: Collection<Int>): ConfigTemplateMetadata {
    return openApiSpecIndices.fold(this) { metadata, specIndex ->
        metadata.transferTemplate(
            listOf("test", "overlayFilePath"),
            listOf("systemUnderTest", "service", "runOptions", "openapi", "specs", specIndex.toString(), "spec", "overlayFilePath"),
        )
    }
}

private fun ConfigTemplateMetadata.transferSecurityToOpenApiSpecs(openApiSpecIndices: Collection<Int>): ConfigTemplateMetadata {
    return openApiSpecIndices.fold(this) { metadata, specIndex ->
        metadata.transferTemplatesUnder(
            sourcePrefix = listOf("security", "OpenAPI", "securitySchemes"),
            targetPrefix = listOf("systemUnderTest", "service", "runOptions", "openapi", "specs", specIndex.toString(), "spec", "securitySchemes"),
            suffixTransform = { suffix ->
                when (suffix.lastOrNull()) {
                    "value" -> suffix.dropLast(1) + "token"
                    else -> suffix
                }
            },
        )
    }
}

private fun ConfigTemplateMetadata.transferTestData(view: LegacyConfigView): ConfigTemplateMetadata {
    val withGlobalData = transferTemplatesUnder(
        sourcePrefix = listOf("examples"),
        targetPrefix = listOf("systemUnderTest", "service", "data", "examples", "0", "directories"),
    ).transferTemplate(
        sourcePath = listOf("stub", "dictionary"),
        targetPath = listOf("systemUnderTest", "service", "data", "dictionary", "path"),
    )

    val examplesByValue = linkedMapOf<String, Int>()
    return view.sources.foldIndexed(withGlobalData) { sourceIndex, metadata, source ->
        source.test.orEmpty().foldIndexed(metadata) { configIndex, configMetadata, config ->
            val configValue = config as? SpecExecutionConfig.ConfigValue ?: return@foldIndexed configMetadata
            val examples = configValue.config["examples"] as? List<*> ?: return@foldIndexed configMetadata
            examples.foldIndexed(configMetadata) { exampleIndex, exampleMetadata, example ->
                val exampleText = example.configValueText() ?: return@foldIndexed exampleMetadata
                val targetIndex = view.globalExamples.size + examplesByValue.getOrPut(exampleText) { examplesByValue.size }
                providerSourcePathPrefixes(sourceIndex, "provides", configIndex).fold(exampleMetadata) { currentMetadata, prefix ->
                    currentMetadata.transferTemplate(
                        sourcePath = prefix + listOf("config", "examples", exampleIndex.toString()),
                        targetPath = listOf("systemUnderTest", "service", "data", "examples", "0", "directories", targetIndex.toString()),
                    )
                }
            }
        }
    }
}

private fun Any?.configValueText(): String? {
    return when (this) {
        is TemplatableValue<*> -> value.toString()
        is String -> this
        else -> null
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
