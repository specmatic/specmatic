package io.specmatic.conformance_test_support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.parser.core.models.ParseOptions
import org.slf4j.LoggerFactory
import java.io.File
import io.swagger.v3.oas.models.Operation as SwaggerOperation

data class Operation(
    val method: String,
    val path: String,
    val requestContentType: String?,
    val statusCode: Int
) {
    fun hasRequestContentType(): Boolean = requestContentType != null

    fun isJsonContent(): Boolean = hasRequestContentType() && requestContentType!!.lowercase().contains("json")

    fun isYamlContent(): Boolean = hasRequestContentType() && requestContentType!!.lowercase().contains("yaml")
}

class OpenApiSpec(private val specFile: File) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val openApi = run {
        val options = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
        }

        val result = OpenAPIParser().readLocation(specFile.absolutePath, null, options)
        result.openAPI ?: error("Failed to parse OpenAPI spec at ${specFile.absolutePath}: ${result.messages}")
    }

    // We read the raw YAML (not the fully-resolved openApi model) because json-schema-validator
    // needs $ref pointers intact to handle discriminators correctly. Serializing the fully-resolved
    // model inlines all $refs, which breaks discriminator-based schema selection.
    private val rootNode: JsonNode = yamlMapper.readTree(specFile)

    private val schemaRegistry = when (openApi.specVersion) {
        SpecVersion.V30 -> openApi30SchemaRegistry
        SpecVersion.V31 -> openApi31SchemaRegistry

    }

    private val documentSchema: Schema = schemaRegistry.getSchema(
        SchemaLocation.of(specFile.toURI().toString()), rootNode
    )

    val operations: Set<Operation> = openApi.paths.orEmpty().flatMap { (path, item) ->
        item.readOperationsMap().flatMap { (swaggerMethod: PathItem.HttpMethod, swaggerOperation: SwaggerOperation) ->
            // Every spec MUST have at least 1 response
            val statusCodes = swaggerOperation.responses!!.keys.map { it.toInt() }

            when (swaggerOperation.requestBody) {
                null -> {
                    statusCodes.map { statusCode ->
                        Operation(swaggerMethod.name.uppercase(), path, null, statusCode)
                    }
                }

                else -> {
                    // If a spec has a requestBody it MUST have at least 1 request content section
                    val requestContentTypes = swaggerOperation.requestBody!!.content!!.keys

                    requestContentTypes.flatMap { contentType ->
                        statusCodes.map { statusCode ->
                            Operation(swaggerMethod.name.uppercase(), path, contentType, statusCode)
                        }
                    }
                }
            }
        }
    }.toSet()

    private val pathPatterns: Map<Operation, Regex> = operations.associateWith { it.path.toPathRegex() }

    fun toOperation(
        method: String,
        path: String,
        requestContentType: String?,
        statusCode: Int
    ): Operation? =
        pathPatterns.entries.find { (op, regex) ->
            op.method == method.uppercase()
                    && regex.matches(path)
                    && op.requestContentType == requestContentType
                    && op.statusCode == statusCode
        }?.key

    fun validateRequestBody(body: String, operation: Operation): List<Error> {
        require(operation.hasRequestContentType())

        return when {
            operation.isJsonContent() || operation.isYamlContent() -> {
                val requestBodyPath =
                    "/paths/${operation.path.escapeJsonPointer()}/${operation.method.lowercase()}/requestBody"
                val basePath = resolveRef(requestBodyPath)
                val pointer = "$basePath/content/${operation.requestContentType!!.escapeJsonPointer()}/schema"
                validate(pointer, body)
            }

            else -> {
                error("no support for validating requests of type:${operation.requestContentType}\nbody=${body}")
            }
        }
    }

    fun validateResponseBody(
        body: String, operation: Operation, responseContentType: String
    ): List<Error> {
        val responsePath =
            "/paths/${operation.path.escapeJsonPointer()}/${operation.method.lowercase()}/responses/${operation.statusCode}"
        val basePath = resolveRef(responsePath)
        val pointer = "$basePath/content/${responseContentType.escapeJsonPointer()}/schema"
        return validate(pointer, body)
    }

    // Since rootNode preserves the raw YAML (see comment above), a node at a given path may be
    // a $ref rather than an inline definition (e.g. `requestBody: {$ref: '#/components/requestBodies/Foo'}`).
    // In that case, we follow the $ref so the caller can continue building the pointer from the target.
    private fun resolveRef(path: String): String {
        val node = rootNode.at(path)
        return if (node.has($$"$ref")) {
            node.get($$"$ref").asText().removePrefix("#")
        } else {
            path
        }
    }

    private fun validate(pointer: String, body: String): List<Error> {
        val subSchema = documentSchema.getRefSchema(SchemaLocation.Fragment.of(pointer))
        return subSchema.validate(yamlMapper.readTree(body))
    }


    private fun String.toPathRegex(): Regex {
        if (this == "/") return Regex("^/$")

        val body = Regex("""\{[^}]+}""")
            .split(trimEnd('/'))
            .joinToString("[^/]+") { Regex.escape(it) }

        return Regex("^$body$")
    }

    private fun String.escapeJsonPointer(): String = replace("~", "~0").replace("/", "~1")

    companion object {
        private val openApi30SchemaRegistry =
            SchemaRegistry.withDialect(Dialects.getOpenApi30())

        private val openApi31SchemaRegistry =
            SchemaRegistry.withDialect(Dialects.getOpenApi31())

        private val yamlMapper = ObjectMapper(YAMLFactory())
    }
}
