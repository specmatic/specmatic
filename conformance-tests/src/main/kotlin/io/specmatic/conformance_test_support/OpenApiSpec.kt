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
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.parser.core.models.ParseOptions
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URLDecoder
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

    fun isFormUrlEncodedContent(): Boolean =
        hasRequestContentType() && requestContentType!!.lowercase() == "application/x-www-form-urlencoded"
}

class OpenApiSpec(private val specFile: File) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    // Synchronized to work around swagger-parser thread-safety issue:
    // https://github.com/swagger-api/swagger-parser/issues/2295
    private val openApi = synchronized(parserLock) {
        val options = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
            isValidateInternalRefs = true
            isValidateExternalRefs = true
        }

        val result = OpenAPIParser().readLocation(specFile.absolutePath, null, options)

        if (result.messages.isNotEmpty() || result.openAPI == null) {
            error("Failed to parse OpenAPI spec at ${specFile.absolutePath}: ${result.messages}")
        }

        result.openAPI
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

        val jsonNode = when {
            operation.isJsonContent() || operation.isYamlContent() -> yamlMapper.readTree(body)
            operation.isFormUrlEncodedContent() -> formUrlEncodedToJson(body, operation)
            else -> error("no support for validating requests of type:${operation.requestContentType}\nbody=${body}")
        }

        return requestBodySchema(operation).validate(jsonNode)
    }

    fun validateQueryParameters(url: String, operation: Operation): List<String> {
        val swaggerOp = swaggerOperation(operation) ?: return emptyList()
        val queryParams = swaggerOp.parameters.orEmpty().filter { it.`in` == "query" }
        if (queryParams.isEmpty()) return emptyList()

        val queryPairs = parseQueryString(URI(url).rawQuery.orEmpty())
        return queryParams.flatMap { validateQueryParameter(it, queryPairs) }
    }

    private fun validateQueryParameter(
        parameter: Parameter,
        queryPairs: Map<String, List<String>>
    ): List<String> {
        val name = parameter.name
        val values = queryPairs[name].orEmpty()

        if (values.isEmpty()) {
            return if (parameter.required == true) listOf("/$name: required query parameter is missing")
            else emptyList()
        }

        val schemaType = parameter.schema?.type ?: parameter.schema?.types?.firstOrNull { it != "null" }
        val schemaAllowsNull = parameter.schema?.nullable == true
                || parameter.schema?.types?.contains("null") == true
        val itemsType = if (schemaType == "array") {
            parameter.schema?.items?.type ?: parameter.schema?.items?.types?.firstOrNull { it != "null" }
        } else null

        val node = try {
            when {
                // Empty value on the wire conventionally represents null for nullable params
                // (e.g. `?minPrice=` for a `[number, "null"]` schema).
                values.size == 1 && values.first().isEmpty() && schemaAllowsNull ->
                    jsonMapper.nullNode()

                schemaType == "array" -> jsonMapper.createArrayNode().apply {
                    values.forEach { add(parseFormValue(it, itemsType)) }
                }

                else -> parseFormValue(values.first(), schemaType)
            }
        } catch (e: Exception) {
            return listOf("/$name: cannot parse '${values.first()}' as $schemaType: ${e.message}")
        }

        val schemaNode: JsonNode = swaggerJsonMapper.valueToTree(parameter.schema)
        val schema = schemaRegistry.getSchema(parameterSchemaLocation, schemaNode)
        return schema.validate(node).map { "/$name${it.instanceLocation}: ${it.message}" }
    }

    private fun swaggerOperation(operation: Operation): SwaggerOperation? {
        val pathItem = openApi.paths[operation.path] ?: return null
        return pathItem.readOperationsMap()[PathItem.HttpMethod.valueOf(operation.method.uppercase())]
    }

    private fun parseQueryString(queryString: String): Map<String, List<String>> {
        if (queryString.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, MutableList<String>>()
        queryString.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val parts = pair.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], Charsets.UTF_8)
            val value = if (parts.size == 2) URLDecoder.decode(parts[1], Charsets.UTF_8) else ""
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
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

    private fun requestBodySchema(operation: Operation): Schema {
        val requestBodyPath =
            "/paths/${operation.path.escapeJsonPointer()}/${operation.method.lowercase()}/requestBody"
        val basePath = resolveRef(requestBodyPath)
        val pointer = "$basePath/content/${operation.requestContentType!!.escapeJsonPointer()}/schema"
        return documentSchema.getRefSchema(SchemaLocation.Fragment.of(pointer))
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

    private fun requestBodyProperties(operation: Operation): Map<String, io.swagger.v3.oas.models.media.Schema<*>> {
        val schema = swaggerOperation(operation)
            ?.requestBody?.content?.get(operation.requestContentType)?.schema
            ?: return emptyMap()
        return schema.properties.orEmpty()
    }

    private fun formUrlEncodedToJson(body: String, operation: Operation): JsonNode {
        val properties = requestBodyProperties(operation)

        val obj = jsonMapper.createObjectNode()
        body.split("&").forEach { pair ->
            val (key, value) = pair.split("=", limit = 2).map { URLDecoder.decode(it, Charsets.UTF_8) }
            val schema = properties[key]
            val schemaType = schema?.type ?: schema?.types?.firstOrNull()
            val parsedValue = parseFormValue(value, schemaType)
            when (schemaType) {
                "array" -> {
                    when {
                        parsedValue.isArray -> {
                            // Array sent as a single JSON-encoded value e.g. tags=["foo","bar"]
                            obj.set<JsonNode>(key, parsedValue)
                        }

                        else -> {
                            // Array sent as repeated fields e.g. tags=foo&tags=bar.
                            // Specmatic currently sends arrays as JSON-encoded single values,
                            // but this handles the repeated-field encoding if it's ever used.
                            obj.withArray(key).add(parsedValue)
                        }
                    }
                }

                else -> {
                    obj.set(key, parsedValue)
                }
            }
        }
        return obj
    }

    // Form data is all strings on the wire, but the JSON Schema validator needs correctly-typed
    // JsonNodes (e.g. IntNode, not TextNode("42")). We coerce the four primitive types explicitly;
    // "string" needs its own branch to prevent the `else` fallback from parsing values like "true"
    // or "42" as JSON literals. All other types (array, object, unknown) fall through to `readTree`
    // which handles JSON-encoded values, with a string fallback for plain scalars.
    private fun parseFormValue(value: String, schemaType: String?): JsonNode =
        when (schemaType) {
            "string" -> jsonMapper.valueToTree(value)
            "integer" -> jsonMapper.valueToTree(value.toLong())
            "number" -> jsonMapper.valueToTree(value.toDouble())
            "boolean" -> jsonMapper.valueToTree(value.toBoolean())
            else -> try {
                jsonMapper.readTree(value)
            } catch (_: Exception) {
                jsonMapper.valueToTree(value)
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

    fun findExtensionByKey(key: String): String? {
        val extensions = openApi.extensions ?: return null
        if (!extensions.containsKey(key)) return null
        return extensions[key]?.toString().orEmpty()
    }

    companion object {
        private val parserLock = Any()

        private val openApi30SchemaRegistry =
            SchemaRegistry.withDialect(Dialects.getOpenApi30())

        private val openApi31SchemaRegistry =
            SchemaRegistry.withDialect(Dialects.getOpenApi31())

        private val yamlMapper = ObjectMapper(YAMLFactory())
        private val jsonMapper = ObjectMapper()

        // Synthetic location used when building a Schema directly from a swagger Schema model
        // (no $refs to resolve, since swagger-parser already inlined them via isResolveFully).
        private val parameterSchemaLocation = SchemaLocation.of("urn:specmatic:query-parameter")
        private val swaggerJsonMapper: ObjectMapper = Json.mapper()
    }
}
