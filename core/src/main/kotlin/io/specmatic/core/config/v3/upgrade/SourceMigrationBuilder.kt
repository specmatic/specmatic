package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.config.ConfigTemplateMetadata
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.sources.GitAuthentication
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.determineSpecTypeFor
import io.specmatic.reporter.model.SpecType
import java.io.File

sealed interface SourceMigration {
    val definition: Definition
    val specTypesByPath: Map<String, SpecType>
    val specIdsByPath: Map<String, String>

    data class TestSourceMigration(
        override val definition: Definition,
        val configs: List<SpecExecutionConfig>,
        override val specTypesByPath: Map<String, SpecType>,
        override val specIdsByPath: Map<String, String>,
    ) : SourceMigration

    data class MockSourceMigration(
        override val definition: Definition,
        val config: SpecExecutionConfig,
        override val specTypesByPath: Map<String, SpecType>,
        override val specIdsByPath: Map<String, String>,
    ) : SourceMigration

    fun hasSpecType(specType: SpecType): Boolean {
        return specTypesByPath.values.contains(specType)
    }
}

class SourceMigrationBuilder(private val gitAuth: GitAuthentication?) {
    fun buildTestMigrations(sources: List<Source>): List<SourceMigration.TestSourceMigration> {
        return sources.mapNotNull { source ->
            createTestMigration(source, source.test.orEmpty())
        }
    }

    fun buildMockMigrations(sources: List<Source>): List<SourceMigration.MockSourceMigration> {
        return sources.flatMap { source ->
            if (source.stub.isNullOrEmpty()) return@flatMap emptyList()
            source.stub.map { config -> createMockMigration(source, config) }
        }
    }

    fun transferMetadata(sources: List<Source>, metadata: ConfigTemplateMetadata): ConfigTemplateMetadata {
        val testMetadata = sources.foldIndexed(SourceTemplateTransferState(metadata)) { sourceIndex, state, source ->
            state.transferTestSource(sourceIndex, source)
        }

        return sources.foldIndexed(MockSourceTemplateTransferState(testMetadata.metadata)) { sourceIndex, state, source ->
            state.transferMockSource(sourceIndex, source)
        }.metadata
    }

    private fun createTestMigration(source: Source, configs: List<SpecExecutionConfig>): SourceMigration.TestSourceMigration? {
        if (configs.isEmpty()) return null
        val specTypesByPath = resolveSpecTypesByPath(configs)
        val specIdsByPath = buildSpecificationIds(specTypesByPath.keys)
        val definition = createDefinition(source, configs, specTypesByPath, specIdsByPath)
        return SourceMigration.TestSourceMigration(definition = definition, configs = configs, specTypesByPath = specTypesByPath, specIdsByPath = specIdsByPath)
    }

    private fun createMockMigration(source: Source, config: SpecExecutionConfig): SourceMigration.MockSourceMigration {
        val configs = listOf(config)
        val specTypesByPath = resolveSpecTypesByPath(configs)
        val specIdsByPath = buildSpecificationIds(specTypesByPath.keys)
        val definition = createDefinition(source, configs, specTypesByPath, specIdsByPath)
        return SourceMigration.MockSourceMigration(definition = definition, config = config, specTypesByPath = specTypesByPath, specIdsByPath = specIdsByPath)
    }

    private fun createDefinition(source: Source, configs: List<SpecExecutionConfig>, specTypesByPath: Map<String, SpecType>, specIdsByPath: Map<String, String>): Definition {
        return Definition(
            Definition.Value(
                source = RefOrValue.Value(source.toSourceV3(gitAuth)),
                specs = specTypesByPath.keys.map { specPath -> toSpecificationDefinition(specPath, specIdsByPath, configs) },
            )
        )
    }

    private fun resolveSpecTypesByPath(configs: List<SpecExecutionConfig>): Map<String, SpecType> {
        return configs.fold(linkedMapOf()) { acc, config ->
            config.specs().fold(acc) { specsAcc, specPath ->
                if (specPath in specsAcc) return@fold specsAcc
                LinkedHashMap(specsAcc).apply { put(specPath, resolveSpecType(specPath, config)) }
            }
        }
    }

    private fun toSpecificationDefinition(specPath: String, specIdsByPath: Map<String, String>, configs: List<SpecExecutionConfig>): SpecificationDefinition {
        val urlPathPrefix = configs.asReversed().firstNotNullOfOrNull { config ->
            val objectValue = config as? SpecExecutionConfig.ObjectValue.PartialUrl ?: return@firstNotNullOfOrNull null
            if (specPath in objectValue.specs) objectValue.basePath else null
        }

        return SpecificationDefinition.ObjectValue(
            SpecificationDefinition.Specification(id = specIdsByPath[specPath], path = specPath, urlPathPrefix = urlPathPrefix)
        )
    }

    private fun resolveSpecType(specPath: String, config: SpecExecutionConfig): SpecType {
        val explicitType = (config as? SpecExecutionConfig.ConfigValue)?.specType?.let { type ->
            SpecType.entries.firstOrNull { it.value.equals(type, ignoreCase = true) }
        }

        return explicitType ?: determineSpecTypeFor(File(specPath)).first()
    }

    private fun Source.toSourceV3(gitAuth: GitAuthentication?): SourceV3 {
        return when (provider) {
            SourceProvider.git -> SourceV3(
                git = SourceV3.Git(url = repository, branch = branch, matchBranch = matchBranch, auth = gitAuth),
                fileSystem = null,
                web = null
            )

            SourceProvider.filesystem -> SourceV3(
                git = null,
                fileSystem = SourceV3.FileSystem(directory = directory ?: "."),
                web = null
            )

            SourceProvider.web -> SourceV3(
                git = null,
                fileSystem = null,
                web = SourceV3.Web(url = webBaseUrl)
            )
        }
    }
}

private data class SourceTemplateTransferState(
    val metadata: ConfigTemplateMetadata,
    val definitionIndex: Int = 0,
) {
    fun transferTestSource(sourceIndex: Int, source: Source): SourceTemplateTransferState {
        val configs = source.test.orEmpty()
        if (configs.isEmpty()) return this

        val targetDefinitionPrefix = listOf("systemUnderTest", "service", "definitions", definitionIndex.toString(), "definition")
        val transferred = metadata
            .transferSourceProvider(sourceIndex, targetDefinitionPrefix + "source")
            .transferAuth(targetDefinitionPrefix + listOf("source", "git", "auth"))
            .transferSpecPaths(sourceIndex, "provides", configs, targetDefinitionPrefix + "specs")

        return copy(metadata = transferred, definitionIndex = definitionIndex + 1)
    }
}

private data class MockSourceTemplateTransferState(
    val metadata: ConfigTemplateMetadata,
    val serviceIndex: Int = 0,
) {
    fun transferMockSource(sourceIndex: Int, source: Source): MockSourceTemplateTransferState {
        val transferred = source.stub.orEmpty().foldIndexed(metadata) { configIndex, currentMetadata, config ->
            val targetDefinitionPrefix = listOf("dependencies", "services", (serviceIndex + configIndex).toString(), "service", "definitions", "0", "definition")
            currentMetadata
                .transferSourceProvider(sourceIndex, targetDefinitionPrefix + "source")
                .transferAuth(targetDefinitionPrefix + listOf("source", "git", "auth"))
                .transferSpecPaths(sourceIndex, "consumes", listOf(config), targetDefinitionPrefix + "specs", configIndexOffset = configIndex)
        }

        return copy(metadata = transferred, serviceIndex = serviceIndex + source.stub.orEmpty().size)
    }
}

private fun ConfigTemplateMetadata.transferSourceProvider(sourceIndex: Int, targetSourcePrefix: List<String>): ConfigTemplateMetadata {
    return sourceRootPrefixes(sourceIndex).fold(this) { metadata, sourceRootPrefix ->
        metadata
            .transferTemplatesUnder(sourceRootPrefix + "git", targetSourcePrefix + "git")
            .transferTemplatesUnder(sourceRootPrefix + "filesystem", targetSourcePrefix + "filesystem")
            .transferTemplatesUnder(sourceRootPrefix + "web", targetSourcePrefix + "web")
    }
}

private fun ConfigTemplateMetadata.transferAuth(targetAuthPrefix: List<String>): ConfigTemplateMetadata {
    return this
        .transferTemplate(listOf("auth", "bearer-file"), targetAuthPrefix + "bearerFile")
        .transferTemplate(listOf("auth", "bearer-environment-variable"), targetAuthPrefix + "bearerEnvironmentVariable")
        .transferTemplate(listOf("auth", "personal-access-token"), targetAuthPrefix + "personalAccessToken")
}

private fun ConfigTemplateMetadata.transferSpecPaths(
    sourceIndex: Int,
    entryKind: String,
    configs: List<SpecExecutionConfig>,
    targetSpecsPrefix: List<String>,
    configIndexOffset: Int = 0,
): ConfigTemplateMetadata {
    val specIndices = linkedMapOf<String, Int>()
    return configs.foldIndexed(this) { configIndex, metadata, config ->
        config.specs().foldIndexed(metadata) { specIndexInConfig, currentMetadata, specPath ->
            val targetSpecIndex = specIndices.getOrPut(specPath) { specIndices.size }
            sourceEntryPrefixes(sourceIndex, entryKind, configIndex + configIndexOffset).fold(currentMetadata) { pathMetadata, sourceEntryPrefix ->
                val sourcePath = when (config) {
                    is SpecExecutionConfig.StringValue -> sourceEntryPrefix
                    else -> sourceEntryPrefix + listOf("specs", specIndexInConfig.toString())
                }

                pathMetadata
                    .transferTemplate(sourcePath, targetSpecsPrefix + targetSpecIndex.toString())
                    .transferTemplate(sourcePath, targetSpecsPrefix + listOf(targetSpecIndex.toString(), "spec", "path"))
            }
        }
    }
}

private fun sourceRootPrefixes(sourceIndex: Int): List<List<String>> {
    val indexedPath = listOf("contracts", sourceIndex.toString())
    return when (sourceIndex) {
        0 -> listOf(indexedPath, listOf("contracts"))
        else -> listOf(indexedPath)
    }
}

private fun sourceEntryPrefixes(sourceIndex: Int, entryKind: String, entryIndex: Int): List<List<String>> {
    return sourceRootPrefixes(sourceIndex).map { sourceRootPrefix -> sourceRootPrefix + listOf(entryKind, entryIndex.toString()) }
}
