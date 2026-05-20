package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.TemplatableValue
import io.specmatic.core.config.ConfigTemplateMetadata
import io.specmatic.core.config.OpenAPIMockConfig as LegacyOpenAPIMockConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.determineSpecTypeFor
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
import io.specmatic.reporter.model.SpecType
import java.io.File

class DependenciesMapper {
    fun mapFrom(view: LegacyConfigView, source: SourceMigrationBuilder): MockServiceConfig? {
        val migrations = source.buildMockMigrations(view.sources)
        if (migrations.isEmpty()) return null

        return MockServiceConfig(
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

    fun transferMetadata(view: LegacyConfigView, metadata: ConfigTemplateMetadata): ConfigTemplateMetadata {
        val state = view.sources.foldIndexed(MockTemplateTransferState(metadata)) { sourceIndex, sourceState, source ->
            source.stub.orEmpty().foldIndexed(sourceState) { configIndex, configState, config ->
                configState.transferMockRunOptions(sourceIndex, configIndex, config)
            }
        }

        return state.metadata
            .transferStubRunOptions("openapi", state.openApiServiceIndices)
            .transferStubRunOptions("wsdl", state.wsdlServiceIndices)
            .transferDependencyData(view)
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
            filter = view.stubConfig?.getTemplatedFilter(),
            baseUrl = view.stubConfig?.getTemplatedBaseUrl(),
            cert = view.stubConfig?.getHttps()?.let { RefOrValue.Value(it) },
            specs = overrides.openApi.values.map(::OpenApiRunOptionsSpecifications),
        )
    }

    private fun buildWsdlMockConfig(view: LegacyConfigView, migration: SourceMigration.MockSourceMigration, overrides: RunOptionsMapper): WsdlMockConfig {
        if (!migration.hasSpecType(SpecType.WSDL)) return WsdlMockConfig()
        return WsdlMockConfig(
            baseUrl = view.stubConfig?.getTemplatedBaseUrl(),
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

    private data class MockTemplateTransferState(
        val metadata: ConfigTemplateMetadata,
        val serviceIndex: Int = 0,
        val openApiServiceIndices: Set<Int> = linkedSetOf(),
        val wsdlServiceIndices: Set<Int> = linkedSetOf(),
    ) {
        fun transferMockRunOptions(sourceIndex: Int, configIndex: Int, config: SpecExecutionConfig): MockTemplateTransferState {
            val transferred = config.specs().foldIndexed(this) { specIndex, state, specPath ->
                when (config.mockSpecTypeFor(specPath)) {
                    SpecType.OPENAPI -> state.transferSpecConfigTemplate(sourceIndex, configIndex, specIndex, config, "openapi")
                        .copy(openApiServiceIndices = state.openApiServiceIndices + serviceIndex)

                    SpecType.WSDL -> state.transferSpecConfigTemplate(sourceIndex, configIndex, specIndex, config, "wsdl")
                        .copy(wsdlServiceIndices = state.wsdlServiceIndices + serviceIndex)

                    SpecType.ASYNCAPI -> state.transferSpecConfigTemplate(sourceIndex, configIndex, specIndex, config, "asyncapi")
                    SpecType.GRAPHQL -> state.transferSpecConfigTemplate(sourceIndex, configIndex, specIndex, config, "graphqlsdl")
                    SpecType.PROTOBUF -> state.transferSpecConfigTemplate(sourceIndex, configIndex, specIndex, config, "protobuf")
                }
            }

            return transferred.copy(serviceIndex = serviceIndex + 1)
        }

        private fun transferSpecConfigTemplate(
            sourceIndex: Int,
            configIndex: Int,
            specIndex: Int,
            config: SpecExecutionConfig,
            v3SpecType: String,
        ): MockTemplateTransferState {
            return copy(
                metadata = metadata.transferSpecConfigTemplate(
                    sourceIndex = sourceIndex,
                    configIndex = configIndex,
                    specIndex = specIndex,
                    serviceIndex = serviceIndex,
                    config = config,
                    v3SpecType = v3SpecType,
                )
            )
        }
    }
}

private data class MockSourceTemplateField(val sourceSegments: List<String>, val targetName: String)

private fun ConfigTemplateMetadata.transferSpecConfigTemplate(
    sourceIndex: Int,
    configIndex: Int,
    specIndex: Int,
    serviceIndex: Int,
    config: SpecExecutionConfig,
    v3SpecType: String,
): ConfigTemplateMetadata {
    val runOptionsMetadata = config.mockSourceTemplateFields().fold(this) { currentMetadata, field ->
        val targetPath = listOf("dependencies", "services", serviceIndex.toString(), "service", "runOptions", v3SpecType, "specs", specIndex.toString(), "spec", field.targetName)
        mockSourcePathPrefixes(sourceIndex, "consumes", configIndex).fold(currentMetadata) { metadata, prefix ->
            metadata.transferTemplate(prefix + field.sourceSegments, targetPath)
        }
    }

    val configMetadata = if (config is SpecExecutionConfig.ConfigValue) {
        val targetSpecPrefix = listOf("dependencies", "services", serviceIndex.toString(), "service", "runOptions", v3SpecType, "specs", specIndex.toString(), "spec")
        mockSourcePathPrefixes(sourceIndex, "consumes", configIndex).fold(runOptionsMetadata) { metadata, prefix ->
            metadata.transferTemplatesUnder(prefix + "config", targetSpecPrefix)
        }
    } else {
        runOptionsMetadata
    }

    if (config !is SpecExecutionConfig.ObjectValue.PartialUrl) return configMetadata

    val targetPath = listOf("dependencies", "services", serviceIndex.toString(), "service", "definitions", "0", "definition", "specs", specIndex.toString(), "spec", "urlPathPrefix")
    return mockSourcePathPrefixes(sourceIndex, "consumes", configIndex).fold(configMetadata) { metadata, prefix ->
        metadata.transferTemplate(prefix + listOf("basePath"), targetPath)
    }
}

private fun SpecExecutionConfig.mockSourceTemplateFields(): List<MockSourceTemplateField> {
    return when (this) {
        is SpecExecutionConfig.ConfigValue -> listOf("baseUrl", "host", "port").map { fieldName ->
            MockSourceTemplateField(listOf("config", fieldName), fieldName)
        }

        is SpecExecutionConfig.ObjectValue.FullUrl -> listOf(MockSourceTemplateField(listOf("baseUrl"), "baseUrl"))
        is SpecExecutionConfig.ObjectValue.PartialUrl -> listOfNotNull(
            MockSourceTemplateField(listOf("host"), "host"),
            MockSourceTemplateField(listOf("port"), "port"),
        )

        is SpecExecutionConfig.StringValue -> emptyList()
    }
}

private fun SpecExecutionConfig.mockSpecTypeFor(specPath: String): SpecType {
    val explicitType = (this as? SpecExecutionConfig.ConfigValue)?.specType?.let { configuredSpecType ->
        SpecType.entries.firstOrNull { specType -> specType.value.equals(configuredSpecType, ignoreCase = true) }
    }

    return explicitType ?: determineSpecTypeFor(File(specPath)).first()
}

private fun mockSourcePathPrefixes(sourceIndex: Int, entryKind: String, entryIndex: Int): List<List<String>> {
    val indexedPath = listOf("contracts", sourceIndex.toString(), entryKind, entryIndex.toString())
    return when (sourceIndex) {
        0 -> listOf(indexedPath, listOf("contracts", entryKind, entryIndex.toString()))
        else -> listOf(indexedPath)
    }
}

private fun ConfigTemplateMetadata.transferStubRunOptions(specType: String, serviceIndices: Collection<Int>): ConfigTemplateMetadata {
    return serviceIndices.fold(this) { metadata, serviceIndex ->
        val targetPrefix = listOf("dependencies", "services", serviceIndex.toString(), "service", "runOptions", specType)
        metadata
            .transferTemplate(listOf("stub", "baseUrl"), targetPrefix + "baseUrl")
            .transferTemplate(listOf("stub", "filter"), targetPrefix + "filter")
            .transferTemplatesUnder(listOf("stub", "https"), targetPrefix + "cert")
    }
}

private fun ConfigTemplateMetadata.transferDependencyData(view: LegacyConfigView): ConfigTemplateMetadata {
    val globalData = transferTemplatesUnder(
        sourcePrefix = listOf("examples"),
        targetPrefix = listOf("dependencies", "data", "examples", "0", "directories"),
    ).transferTemplate(
        sourcePath = listOf("stub", "dictionary"),
        targetPath = listOf("dependencies", "data", "dictionary", "path"),
    ).transferTemplatesUnder(
        sourcePrefix = listOf("hooks"),
        targetPrefix = listOf("dependencies", "data", "adapters"),
    )

    var serviceIndex = 0
    return view.sources.foldIndexed(globalData) { sourceIndex, sourceMetadata, source ->
        source.stub.orEmpty().foldIndexed(sourceMetadata) { configIndex, configMetadata, config ->
            val currentServiceIndex = serviceIndex
            serviceIndex += 1
            val configValue = config as? SpecExecutionConfig.ConfigValue ?: return@foldIndexed configMetadata
            val examples = configValue.config["examples"] as? List<*> ?: return@foldIndexed configMetadata
            examples.foldIndexed(configMetadata) { exampleIndex, exampleMetadata, example ->
                if (example !is String && example !is TemplatableValue<*>) return@foldIndexed exampleMetadata
                mockSourcePathPrefixes(sourceIndex, "consumes", configIndex).fold(exampleMetadata) { currentMetadata, prefix ->
                    currentMetadata.transferTemplate(
                        sourcePath = prefix + listOf("config", "examples", exampleIndex.toString()),
                        targetPath = listOf("dependencies", "services", currentServiceIndex.toString(), "service", "data", "examples", "0", "directories", exampleIndex.toString()),
                    )
                }
            }
        }
    }
}
