package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import io.specmatic.core.Configuration.Companion.DEFAULT_BASE_URL
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import io.specmatic.core.utilities.Flags
import java.net.URI

sealed class SpecExecutionConfig {
    data class StringValue(@get:JsonValue val value: String) : SpecExecutionConfig()
    sealed class ObjectValue : SpecExecutionConfig() {
        abstract val specs: List<String>
        abstract val resiliencyTests: ResiliencyTestsConfig?
        abstract val examples: List<String>?

        fun toBaseUrl(defaultBaseUrl: String? = null): String {
            val resolvedBaseUrl = defaultBaseUrl
                ?: Flags.getStringValue(Flags.SPECMATIC_BASE_URL)
                ?: DEFAULT_BASE_URL
            val baseUrl = URI(resolvedBaseUrl)
            return toUrl(baseUrl).toString()
        }

        abstract fun toUrl(default: URI): URI

        data class FullUrl(
            val baseUrl: String,
            override val specs: List<String>,
            override val resiliencyTests: ResiliencyTestsConfig? = null,
            override val examples: List<String>? = null
        ) : ObjectValue() {
            override fun toUrl(default: URI) = URI(baseUrl)
        }

        data class PartialUrl(
            val host: String? = null,
            val port: Int? = null,
            val basePath: String? = null,
            override val specs: List<String>,
            override val resiliencyTests: ResiliencyTestsConfig? = null,
            override val examples: List<String>? = null
        ) : ObjectValue() {
            override fun toUrl(default: URI): URI {
                return URI(
                    default.scheme,
                    default.userInfo,
                    host ?: default.host,
                    port ?: default.port,
                    basePath ?: default.path,
                    default.query,
                    default.fragment
                )
            }
        }
    }

    data class ConfigValue(
        val specs: List<String>,
        val specType: String,
        val config: Map<String, Any>
    ) : SpecExecutionConfig() {
        fun contains(specPath: String, specType: String): Boolean {
            return specPath in this.specs.toSet() && specType == this.specType
        }
    }

    @JsonIgnore
    fun contains(absoluteSpecPath: String): Boolean = when (this) {
        is StringValue -> absoluteSpecPath.contains(this.value)
        is ObjectValue -> this.specs.any { absoluteSpecPath.contains(it) }
        is ConfigValue -> this.specs.any { absoluteSpecPath.contains(it) }
    }

    @JsonIgnore
    fun specs(): List<String> {
        return when (this) {
            is StringValue -> listOf(this.value)
            is ObjectValue -> this.specs
            is ConfigValue -> this.specs
        }
    }

    @JsonIgnore
    fun specToBaseUrlPairList(defaultBaseUrl: String?): List<Pair<String, String?>> {
        return when (this) {
            is StringValue -> listOf(this.value to null)
            is ObjectValue -> this.specs.map { specPath ->
                specPath to this.toBaseUrl(defaultBaseUrl)
            }
            is ConfigValue -> this.specs.map { specPath ->
                specPath to null
            }
        }
    }
}

class ConsumesDeserializer(private val consumes: Boolean = true) : JsonDeserializer<List<SpecExecutionConfig>>() {
    private val keyBeingDeserialized = if(consumes) "consumes" else "provides"

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): List<SpecExecutionConfig> {
        return p.codec.readTree<JsonNode>(p).takeIf(JsonNode::isArray)?.map { element ->
            when {
                element.isTextual -> SpecExecutionConfig.StringValue(element.asText())
                element.isObject -> element.parseObjectValue(p)
                else -> throw JsonMappingException(p, "Consumes entry must be string or object")
            }
        } ?: throw JsonMappingException(p, "Consumes should be an array")
    }

    private fun JsonNode.parseObjectValue(p: JsonParser): SpecExecutionConfig {
        if (has("specType") || has("config")) {
            return parseConfigValue(p)
        }

        val validatedJsonNode = this.getValidatedJsonNode(p)
        val specs = validatedJsonNode.get("specs").map(JsonNode::asText)
        val resiliencyTests = parseResiliencyTestsIfApplicable(p)
        val examples = validatedJsonNode.get("examples")?.map(JsonNode::asText)

        return when {
            has("baseUrl") -> SpecExecutionConfig.ObjectValue.FullUrl(
                get("baseUrl").asText(),
                specs,
                resiliencyTests,
                examples
            )

            else -> SpecExecutionConfig.ObjectValue.PartialUrl(
                host = get("host")?.asText(),
                port = get("port")?.asInt(),
                basePath = get("basePath")?.asText(),
                specs = specs,
                resiliencyTests = resiliencyTests,
                examples = examples
            )
        }
    }

    private fun JsonNode.parseConfigValue(p: JsonParser): SpecExecutionConfig.ConfigValue {
        validateConfigValueFields(p)

        val specs = get("specs").map(JsonNode::asText)
        val specType = get("specType").asText()
        val config = get("config").toMap(p)

        return SpecExecutionConfig.ConfigValue(specs, specType, config)
    }

    private fun JsonNode.validateConfigValueFields(p: JsonParser) {
        validateSpecsFieldAndReturn(p)
        val specTypeField = get("specType")
        if (specTypeField == null || !specTypeField.isTextual) {
            throw JsonMappingException(p, "Missing or invalid required field 'specType' key in '$keyBeingDeserialized' field in Specmatic configuration")
        }
        val configField = get("config")
        if (configField == null || !configField.isObject) {
            throw JsonMappingException(p, "Missing or invalid required field 'config' key in '$keyBeingDeserialized' field in Specmatic configuration")
        }
    }

    private fun JsonNode.validateSpecsFieldAndReturn(p: JsonParser): JsonNode {
        val specsField = get("specs")
        when {
            specsField == null -> throw JsonMappingException(p, "Missing required field 'specs' in '$keyBeingDeserialized' field in Specmatic configuration")
            !specsField.isArray -> throw JsonMappingException(p, "'specs' must be an array in '$keyBeingDeserialized' field in Specmatic configuration")
            specsField.isEmpty -> throw JsonMappingException(p, "'specs' array cannot be empty in '$keyBeingDeserialized' field in Specmatic configuration")
            specsField.any { !it.isTextual } -> throw JsonMappingException(p, "'specs' must contain only strings in '$keyBeingDeserialized' field in Specmatic configuration")
        }
        return specsField
    }

    private fun JsonNode.toMap(p: JsonParser): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        this.properties().forEach { (key, value) ->
            map[key] = value.nativeValue(p)
        }
        return map
    }

    private fun JsonNode.nativeValue(p: JsonParser): Any {
        return when {
            this.isTextual -> this.asText()
            this.isInt -> this.asInt()
            this.isLong -> this.asLong()
            this.isDouble -> this.asDouble()
            this.isBoolean -> this.asBoolean()
            this.isArray -> this.map { it.nativeValue(p) }
            this.isObject -> this.toMap(p)
            this.isNull -> throw JsonMappingException(p, "Null values not supported in 'config' key present under $keyBeingDeserialized field in Specmatic configuration")
            else -> throw JsonMappingException(p, "Unsupported value type in 'config' key present under $keyBeingDeserialized field in Specmatic configuration")
        }
    }

    private fun JsonNode.getValidatedJsonNode(p: JsonParser): JsonNode {
        val allowedFields = buildSet {
            addAll(listOf("baseUrl", "host", "port", "basePath", "specs", "examples"))
            if (!consumes) add("resiliencyTests")
        }
        val unknownFields = fieldNames().asSequence().filterNot(allowedFields::contains).toSet()
        if (unknownFields.isNotEmpty()) {
            throw JsonMappingException(p,
                "Unknown fields: ${unknownFields.joinToString(", ")}\nAllowed fields: ${allowedFields.joinToString(", ")}"
            )
        }

        if (!consumes && has("basePath")) {
            throw JsonMappingException(p, "Field 'basePath' is not supported in provides")
        }

        validateSpecsFieldAndReturn(p)

        val hasBaseUrl = has("baseUrl")

        if (consumes) {
            val partialFields = listOf("host", "port", "basePath", "examples").filter(::has)
            val hasPartialFields = partialFields.isNotEmpty()
            when {
                hasBaseUrl && hasPartialFields -> throw JsonMappingException(p, "Cannot combine baseUrl with ${partialFields.joinToString(", ")}")
                !hasBaseUrl && !hasPartialFields -> throw JsonMappingException(p, "Must provide baseUrl or one or combination of host, port, and basePath")
            }
        } else {
            val hasHostOrPort = has("host") || has("port")
            when {
                hasBaseUrl && hasHostOrPort -> throw JsonMappingException(p, "Cannot combine baseUrl with host or port")
                !hasBaseUrl && !hasHostOrPort -> throw JsonMappingException(p, "Must provide baseUrl or one or combination of host and port")
            }
        }

        return this
    }

    private fun JsonNode.parseResiliencyTestsIfApplicable(p: JsonParser): ResiliencyTestsConfig? {
        if (consumes) return null // consumes: resiliencyTests not supported
        val node = get("resiliencyTests") ?: return null
        if (!node.isObject) throw JsonMappingException(p, "'resiliencyTests' must be an object with field 'enable'")

        val enableNode = node.get("enable") ?: return ResiliencyTestsConfig() // present but no enable -> default/null
        if (!enableNode.isTextual) throw JsonMappingException(p, "'resiliencyTests.enable' must be one of: positiveOnly, all, none")
        val enable = when (val value = enableNode.asText()) {
            "positiveOnly" -> ResiliencyTestSuite.positiveOnly
            "all" -> ResiliencyTestSuite.all
            "none" -> ResiliencyTestSuite.none
            else -> throw JsonMappingException(p, "Unknown value '$value' for 'resiliencyTests.enable'. Allowed: positiveOnly, all, none")
        }
        return ResiliencyTestsConfig(enable = enable)
    }
}
