import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion.VersionFlag

class SchemaValidator {
    data class ValidationResult(val valid: Boolean, val errors: List<String>)

    private val factory = JsonSchemaFactory.getInstance(VersionFlag.V7)
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    fun validate(body: String, schema: JsonNode?): ValidationResult {
        if (schema == null || body.isBlank()) return ValidationResult(true, emptyList())
        return try {
            val jsonSchema = factory.getSchema(schema)
            val node = mapper.readTree(body)
            val errors = jsonSchema.validate(node).map { it.message }
            ValidationResult(errors.isEmpty(), errors)
        } catch (e: Exception) {
            ValidationResult(false, listOf("Parse error: ${e.message}"))
        }
    }
}
