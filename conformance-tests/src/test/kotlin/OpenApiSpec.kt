import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.core.models.ParseOptions

class OpenApiSpec private constructor(private val openApi: OpenAPI) {
    companion object {
        private val mapper = ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

        fun load(path: String): OpenApiSpec {
            val options = ParseOptions().apply {
                isResolve = true
                isResolveFully = true
            }
            val result = OpenAPIParser().readLocation(path, null, options)
            val api = result.openAPI ?: error("Failed to parse OpenAPI spec at $path: ${result.messages}")
            return OpenApiSpec(api)
        }
    }

    fun rawModel(): OpenAPI = openApi

    fun allRoutes(): Set<Pair<String, String>> = openApi.paths.orEmpty()
        .flatMap { (path, item) ->
            item.readOperationsMap().orEmpty().keys.map { method ->
                method.name.uppercase() to path
            }
        }.toSet()

    fun requestBodySchema(method: String, template: String, contentType: String = "application/json"): JsonNode? {
        val operation = findOperation(method, template) ?: return null
        val schema = operation.requestBody?.content?.get(contentType)?.schema ?: return null
        return mapper.convertValue(schema, JsonNode::class.java)
    }

    fun responseBodySchema(
        method: String,
        template: String,
        statusCode: Int,
        contentType: String = "application/json"
    ): JsonNode? {
        val operation = findOperation(method, template) ?: return null
        val response = operation.responses?.get(statusCode.toString()) ?: return null
        val schema = response.content?.get(contentType)?.schema ?: return null
        return mapper.convertValue(schema, JsonNode::class.java)
    }

    fun requestBodyExamples(method: String, template: String, contentType: String = "application/json"): Map<String, JsonNode> {
        val operation = findOperation(method, template) ?: return emptyMap()
        val examples = operation.requestBody?.content?.get(contentType)?.examples ?: return emptyMap()
        return examples.mapValues { mapper.valueToTree(it.value.value) }
    }

    fun responseBodyExamples(method: String, template: String, statusCode: Int, contentType: String = "application/json"): Map<String, JsonNode> {
        val operation = findOperation(method, template) ?: return emptyMap()
        val response = operation.responses?.get(statusCode.toString()) ?: return emptyMap()
        val examples = response.content?.get(contentType)?.examples ?: return emptyMap()
        return examples.mapValues { mapper.valueToTree(it.value.value) }
    }

    private fun findOperation(method: String, template: String) =
        openApi.paths?.get(template)?.readOperationsMap()
            ?.entries?.firstOrNull { it.key.name.equals(method, ignoreCase = true) }?.value
}
