package io.specmatic.core.config.validation

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.OutputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.output.OutputUnit
import com.networknt.schema.path.PathType
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.objectMapper

class ConfigSchemaValidator {
    private val schemas = SpecmaticConfigVersion.entries
        .filter { it != SpecmaticConfigVersion.VERSION_1 }
        .associateWith { version -> loadSchema(version) }

    fun validate(version: SpecmaticConfigVersion, tree: JsonNode): List<ConfigValidationOutput> {
        val schema = schemas.getValue(version)
        val output = schema.validate(tree, OutputFormat.LIST)
        return output.details.orEmpty().map { toStandardOutput(it, schema) }
    }

    private fun loadSchema(version: SpecmaticConfigVersion): Schema {
        val resource = when (version) {
            SpecmaticConfigVersion.VERSION_2 -> "/config-validation/config-v2-resolved.schema.json"
            SpecmaticConfigVersion.VERSION_3 -> "/config-validation/config-v3-resolved.schema.json"
            SpecmaticConfigVersion.VERSION_1 -> error("V1 schema is intentionally out of scope")
        }

        val schemaNode = ConfigSchemaValidator::class.java.getResourceAsStream(resource)
            ?.use { objectMapper.readTree(it) }
            ?: error("Unable to load internal config schema: $resource")

        val registryConfig = SchemaRegistryConfig.builder()
            .errorMessageKeyword("errorMessage")
            .pathType(PathType.JSON_POINTER)
            .formatAssertionsEnabled(true)
            .cacheRefs(true)
            .build()

        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemaRegistryConfig(registryConfig)
        }

        return registry.getSchema(SchemaLocation.of("urn:specmatic:config:v${version.value}"), schemaNode)
    }

    private fun toStandardOutput(output: OutputUnit, schema: Schema): ConfigValidationOutput {
        val messages = output.errors.orEmpty().values.map(Any?::toString)
        return ConfigValidationOutput(
            valid = output.isValid,
            severity = ConfigValidationSeverity.ERROR,
            metadata = metadata(output, schema),
            keywordLocation = outputPath(output.evaluationPath),
            instanceLocation = outputPath(output.instanceLocation),
            error = messages.takeIf { it.isNotEmpty() }?.joinToString("; "),
            absoluteKeywordLocation = output.schemaLocation?.takeIf { it.isNotBlank() },
        )
    }

    private fun metadata(output: OutputUnit, schema: Schema): ConfigValidationMetadata? {
        val schemaNode = output.schemaLocation
            ?.substringAfter('#', "")
            ?.let { fragment -> schema.getSchemaNode().at(fragment) }
            ?.takeUnless(JsonNode::isMissingNode)
            ?: return null

        return ConfigValidationMetadata(
            title = schemaNode["title"]?.asText(),
            keyword = output.evaluationPath?.keyword(),
            description = schemaNode["description"]?.asText(),
            deprecated = schemaNode["deprecated"]?.asBoolean(),
            deprecationMessage = schemaNode["deprecationMessage"]?.asText(),
        )
    }

    private fun String.keyword(): String? {
        return split('/')
            .asReversed()
            .firstOrNull { it.isNotEmpty() && it.toIntOrNull() == null }
            ?.decodePointerToken()
    }

    private fun String.decodePointerToken(): String = replace("~1", "/").replace("~0", "~")
    private fun outputPath(path: String?): String = when (path) {
        null, "", "/", "#" -> ""
        else -> path
    }
}
