package io.specmatic.core.config.validation

import com.fasterxml.jackson.databind.JsonNode
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.objectMapper
import io.specmatic.core.config.parseSpecmaticConfigTree
import io.specmatic.core.config.resolveTemplates
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.SpecmaticConfig
import java.io.File
import java.nio.file.Path

class SpecmaticConfigValidator(private val schemaValidator: ConfigSchemaValidator = ConfigSchemaValidator()) {
    fun validate(file: File): ConfigValidationResult = validate(file.readText(), file.toPath())

    fun validate(
        content: String,
        origin: Path = Path.of("specmatic.yaml"),
        schemaOutputFormat: ConfigSchemaOutputFormat = ConfigSchemaOutputFormat.HIERARCHICAL,
    ): ConfigValidationResult {
        val authoredTree = parse(content)
            ?: return invalid(null, "/", "Configuration could not be parsed as YAML or JSON.")

        val version = detectVersion(authoredTree)
            ?: return invalid(null, "/version", versionError(authoredTree["version"]))

        return if (version == SpecmaticConfigVersion.VERSION_1) {
            validateV1Binding(authoredTree, version)
        } else {
            validateResolved(authoredTree, version, origin, schemaOutputFormat)
        }
    }

    private fun validateV1Binding(tree: JsonNode, version: SpecmaticConfigVersion): ConfigValidationResult {
        return if (bind(version, tree) == null) {
            invalid(
                version = version,
                instanceLocation = "/",
                message = "The configuration could not be bound to the V1 Specmatic configuration model.",
            )
        } else {
            ConfigValidationResult.Valid(version)
        }
    }

    private fun validateResolved(authoredTree: JsonNode, version: SpecmaticConfigVersion, origin: Path, schemaOutputFormat: ConfigSchemaOutputFormat): ConfigValidationResult {
        val resolvedTree = try {
            resolveTemplates(authoredTree)
        } catch (_: Exception) {
            return invalid(version, "/", "Configuration expressions could not be resolved.")
        }

        val schemaOutput = schemaValidator.validate(version, resolvedTree, schemaOutputFormat)
        if (schemaOutput.isNotEmpty()) {
            return ConfigValidationResult.Invalid(version, schemaOutput)
        }

        val bound = bind(version, resolvedTree) ?: run {
            return invalid(
                version = version,
                instanceLocation = "/",
                message = "The resolved configuration satisfies its schema but cannot be bound to the Specmatic configuration model.",
            )
        }

        val transformed = bound.transform(origin.toFile())
        val loadErrors = loadContracts(transformed, origin)
        val semanticErrors = when (bound) {
            is SpecmaticConfigV3 -> bound.validate(origin)
            else -> emptyList()
        }

        val errors = loadErrors + semanticErrors
        return if (errors.isEmpty()) {
            ConfigValidationResult.Valid(version)
        } else {
            ConfigValidationResult.Invalid(version, errors)
        }
    }

    private fun loadContracts(config: SpecmaticConfig, origin: Path): List<ConfigValidationOutput> {
        val checkoutDirectory = origin.toFile().canonicalFile.parentFile.resolve(".specmatic").canonicalFile
        return runCatching {
            config.loadSources(config.getMatchBranchEnabled()).flatMap { source ->
                source.loadContracts(
                    configFilePath = origin.toFile().canonicalPath,
                    selector = { it.testContracts + it.stubContracts },
                    workingDirectory = checkoutDirectory.canonicalPath,
                )
            }
        }.fold(
            onSuccess = { emptyList() },
            onFailure = { failure ->
                listOf(
                    element = ConfigValidationOutput(
                        valid = false,
                        keywordLocation = "",
                        instanceLocation = "",
                        severity = ConfigValidationSeverity.ERROR,
                        error = "Could not load specification sources: ${failure.message ?: failure::class.simpleName}",
                    )
                )
            }
        )
    }

    private fun parse(content: String): JsonNode? = try {
        parseSpecmaticConfigTree(content)
    } catch (_: Exception) {
        null
    }

    private fun detectVersion(tree: JsonNode): SpecmaticConfigVersion? = literalVersion(tree["version"])
    private fun versionError(versionNode: JsonNode?): String = if (versionNode == null) {
        "Configuration validation supports V1, V2, and V3; a literal version (1, 2, or 3) is required."
    } else {
        "Configuration validation supports only a literal integer version 1, 2, or 3."
    }

    private fun bind(version: SpecmaticConfigVersion, tree: JsonNode): SpecmaticVersionedConfig? = try {
        when (version) {
            SpecmaticConfigVersion.VERSION_1 -> objectMapper.treeToValue(tree, SpecmaticConfigV1::class.java)
            SpecmaticConfigVersion.VERSION_2 -> objectMapper.treeToValue(tree, SpecmaticConfigV2::class.java)
            SpecmaticConfigVersion.VERSION_3 -> objectMapper.treeToValue(tree, SpecmaticConfigV3::class.java)
        }
    } catch (_: Exception) {
        null
    }

    private fun invalid(version: SpecmaticConfigVersion?, instanceLocation: String, message: String): ConfigValidationResult.Invalid {
        return ConfigValidationResult.Invalid(
            version = version,
            output = listOf(
                element = ConfigValidationOutput(
                    valid = false,
                    error = message,
                    keywordLocation = "",
                    instanceLocation = instanceLocation,
                    severity = ConfigValidationSeverity.ERROR,
                )
            ),
        )
    }

    private fun literalVersion(versionNode: JsonNode?): SpecmaticConfigVersion? {
        if (versionNode == null || !versionNode.isIntegralNumber) return null
        return when (versionNode.intValue()) {
            1 -> SpecmaticConfigVersion.VERSION_1
            2 -> SpecmaticConfigVersion.VERSION_2
            3 -> SpecmaticConfigVersion.VERSION_3
            else -> null
        }
    }
}
