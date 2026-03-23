package io.specmatic.conformance_test_support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.Error
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.File

data class Operation(val method: String, val path: String)

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

    fun operations(): Set<Operation> = openApi.paths.orEmpty().flatMap { (path, item) ->
        item.readOperationsMap().map { (method, _) -> Operation(method.name.uppercase(), path) }
    }.toSet()

    private val pathPatterns: Map<Operation, Regex> = operations().associateWith { it.path.toPathRegex() }

    fun matchingOperation(method: String, path: String): Operation? =
        pathPatterns.entries.find { (op, regex) -> op.method == method.uppercase() && regex.matches(path) }?.key

    fun isMatchingOperation(method: String, path: String): Boolean =
        matchingOperation(method, path) != null

    fun validateRequestBody(body: String, path: String, method: String, contentType: String): List<Error> {
        val pointer =
            "/paths/${path.escapeJsonPointer()}/${method.lowercase()}/requestBody/content/${contentType.escapeJsonPointer()}/schema"
        return validate(pointer, body)
    }

    fun validateResponseBody(
        body: String, path: String, method: String, statusCode: Int, contentType: String
    ): List<Error> {
        val pointer =
            "/paths/${path.escapeJsonPointer()}/${method.lowercase()}/responses/$statusCode/content/${contentType.escapeJsonPointer()}/schema"
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
