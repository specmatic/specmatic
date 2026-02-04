package io.specmatic.core.utilities

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*

private val yamlFactory = YAMLFactory().apply {
    disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
    enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID)
    disable(YAMLGenerator.Feature.USE_NATIVE_OBJECT_ID)
}

val yamlMapper: ObjectMapper = ObjectMapper(yamlFactory).registerKotlinModule()

fun yamlStringToValue(stringContent: String): Value {
    val cleaned = stringContent.cleanBOM()
    val raw: Any = yamlMapper.readValue(cleaned, Any::class.java)
    return toValue(raw)
}

private fun String.cleanBOM(): String = removePrefix(UTF_BYTE_ORDER_MARK)

private fun <T> convertToMap(data: Map<*, *>, using: (Any?) -> T): Map<String, T> {
    return data.entries.associate { (k, v) -> k.toString() to using(v) }
}

fun toValue(any: Any?): Value {
    val rawValue = if (any is JsonNode) yamlMapper.convertValue(any, Any::class.java) else any
    return when (rawValue) {
        null -> NullValue
        is Map<*, *> -> convertToMap(rawValue, ::toValue).let(::JSONObjectValue)
        is List<*> -> rawValue.map(::toValue).let(::JSONArrayValue)
        is String -> StringValue(rawValue)
        is Boolean -> BooleanValue(rawValue)
        is Number -> NumberValue(rawValue)
        is ByteArray -> BinaryValue(rawValue)
        else -> StringValue(rawValue.toString())
    }
}

fun fromYamlProperties(value: String): Map<String, Value> {
    val yamlFormat = value.split(",").joinToString("\n") { it.trim().replace(Regex(":(?! )"), ": ") }
    return when (val parsed = yamlStringToValue(yamlFormat)) {
        is JSONObjectValue -> parsed.jsonObject
        else -> emptyMap()
    }
}
