package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import io.specmatic.core.Configuration.Companion.DEFAULT_BASE_URL
import io.specmatic.core.utilities.Flags
import java.net.URI

sealed class SpecsWithPort {
    data class StringValue(@get:JsonValue val value: String) : SpecsWithPort()
    sealed class ObjectValue : SpecsWithPort() {
        abstract val specs: List<String>
        private val defaultBaseUrl: URI get() = URI(Flags.getStringValue(Flags.SPECMATIC_BASE_URL) ?: DEFAULT_BASE_URL)

        fun toBaseUrl(defaultBaseUrl: String? = null): String {
            val baseUrl = defaultBaseUrl?.let(::URI) ?: this.defaultBaseUrl
            return toUrl(baseUrl).toString()
        }

        abstract fun toUrl(default: URI): URI

        data class FullUrl(val baseUrl: String, override val specs: List<String>) : ObjectValue() {
            override fun toUrl(default: URI) = URI(baseUrl)
        }

        data class PartialUrl(val host: String? = null, val port: Int? = null, val basePath: String? = null, override val specs: List<String>) : ObjectValue() {
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
}

class ConsumesDeserializer(private val allowBasePath: Boolean = true) : JsonDeserializer<List<SpecsWithPort>>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): List<SpecsWithPort> {
        return p.codec.readTree<JsonNode>(p).takeIf(JsonNode::isArray)?.map { element ->
            when {
                element.isTextual -> SpecsWithPort.StringValue(element.asText())
                element.isObject -> element.parseObjectValue(p)
                else -> throw JsonMappingException(p, "Consumes entry must be string or object")
            }
        } ?: throw JsonMappingException(p, "Consumes should be an array")
    }

    private fun JsonNode.parseObjectValue(p: JsonParser): SpecsWithPort.ObjectValue {
        val validatedJsonNode = this.getValidatedJsonNode(p)
        val specs = validatedJsonNode.get("specs").map(JsonNode::asText)

        return when {
            has("baseUrl") -> SpecsWithPort.ObjectValue.FullUrl(get("baseUrl").asText(), specs)
            else -> SpecsWithPort.ObjectValue.PartialUrl(
                host = get("host")?.asText(),
                port = get("port")?.asInt(),
                basePath = get("basePath")?.asText(),
                specs = specs
            )
        }
    }

    private fun JsonNode.getValidatedJsonNode(p: JsonParser): JsonNode {
        val allowedFields = setOf("baseUrl", "host", "port", "basePath", "specs")
        val unknownFields = fieldNames().asSequence().filterNot(allowedFields::contains).toSet()
        if (unknownFields.isNotEmpty()) {
            throw JsonMappingException(p,
                "Unknown fields: ${unknownFields.joinToString(", ")}\nAllowed fields: ${allowedFields.joinToString(", ")}"
            )
        }

        if (!allowBasePath && has("basePath")) {
            // In provides, basePath is not permitted
            throw JsonMappingException(p, "Field 'basePath' is not supported in provides")
        }

        val specsField = get("specs")
        when {
            specsField == null -> throw JsonMappingException(p, "Missing required field 'specs'")
            !specsField.isArray -> throw JsonMappingException(p, "'specs' must be an array")
            specsField.isEmpty -> throw JsonMappingException(p, "'specs' array cannot be empty")
            specsField.any { !it.isTextual } -> throw JsonMappingException(p, "'specs' must contain only strings")
        }

        val hasBaseUrl = has("baseUrl")

        if (allowBasePath) {
            val partialFields = listOf("host", "port", "basePath").filter(::has)
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
}
