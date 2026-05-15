package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.sources.GitAuthentication
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.resolveFullyOrEmpty
import io.specmatic.core.config.v3.resolveOrNull
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
            createTestMigration(source, source.resolvedTest.orEmpty())
        }
    }

    fun buildMockMigrations(sources: List<Source>): List<SourceMigration.MockSourceMigration> {
        return sources.flatMap { source ->
            val resolvedStub = source.resolvedStub.orEmpty()
            if (resolvedStub.isEmpty()) return@flatMap emptyList()
            resolvedStub.map { config -> createMockMigration(source, config) }
        }
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
        return when (resolvedProvider) {
            SourceProvider.git -> SourceV3(
                git = SourceV3.Git(url = resolvedRepository, branch = resolvedBranch, matchBranch = resolvedMatchBranch, auth = gitAuth),
                fileSystem = null,
                web = null
            )

            SourceProvider.filesystem -> SourceV3(
                git = null,
                fileSystem = SourceV3.FileSystem(directory = resolvedDirectory ?: "."),
                web = null
            )

            SourceProvider.web -> SourceV3(
                git = null,
                fileSystem = null,
                web = SourceV3.Web(url = resolvedWebBaseUrl)
            )
        }
    }
}
