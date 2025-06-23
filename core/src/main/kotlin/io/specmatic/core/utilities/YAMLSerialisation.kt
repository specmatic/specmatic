package io.specmatic.core.utilities

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*

private val yamlFactory = YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)

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

private fun toValue(any: Any?): Value {
    return when (any) {
        null -> NullValue
        is Map<*, *> -> convertToMap(any, ::toValue).let(::JSONObjectValue)
        is List<*> -> any.map(::toValue).let(::JSONArrayValue)
        is String -> StringValue(any)
        is Boolean -> BooleanValue(any)
        is Number -> NumberValue(any)
        else -> throw ContractException("Unknown value type: ${any::class.simpleName}")
    }
}
