package io.specmatic.conformance_test_support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.Error
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.File
import io.swagger.v3.oas.models.Operation as SwaggerOperation

data class Operation(
    val method: String,
    val path: String,
    val requestContentType: String?,
    val statusCode: Int
)

class OpenApiSpec(private val specFile: File) {
    private val rootNode: JsonNode = yamlMapper.readTree(specFile)

    private val openApi = run {
        val options = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
        }
        val result = OpenAPIParser().readLocation(specFile.absolutePath, null, options)
        result.openAPI ?: error("Failed to parse OpenAPI spec at ${specFile.absolutePath}: ${result.messages}")
    }

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
        // No request body because this operation does not have request content
        // We verify that all valid request bodies have been sent because we check that all valid operations have been exercised
        // in the conformance tests
        if (operation.requestContentType == null) {
            return emptyList()
        }

        val pointer =
            "/paths/${operation.path.escapeJsonPointer()}/${operation.method.lowercase()}/requestBody/content/${operation.requestContentType.escapeJsonPointer()}/schema"
        return validate(pointer, body)
    }

    fun validateResponseBody(
        body: String, operation: Operation, responseContentType: String?
    ): List<Error> {
        // See comment in validateRequestBody for details
        if (responseContentType == null) {
            return emptyList()
        }

        val pointer =
            "/paths/${operation.path.escapeJsonPointer()}/${operation.method.lowercase()}/responses/${operation.statusCode}/content/${responseContentType.escapeJsonPointer()}/schema"
        return validate(pointer, body)
    }

    private fun validate(pointer: String, body: String): List<Error> {
        val schemaNode = rootNode.at(pointer)
        if (schemaNode.isMissingNode) {
            error("can't find schema for $pointer in $specFile")
        }
        val jsonSchema = schemaRegistry.getSchema(schemaNode)
        return jsonSchema.validate(yamlMapper.readTree(body))
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
        private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
        private val yamlMapper = ObjectMapper(YAMLFactory())
    }
}
