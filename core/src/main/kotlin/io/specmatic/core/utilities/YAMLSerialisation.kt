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

fun toValue(any: Any?, onStringValue: (String) -> String = { it }): Value {
    if (any is Value) return any
    return when (any) {
        null -> NullValue
        is Map<*, *> -> convertToMap(any, using = { toValue(it, onStringValue) }).let(::JSONObjectValue)
        is List<*> -> any.map { toValue(it, onStringValue) }.let(::JSONArrayValue)
        is String -> StringValue(onStringValue(any))
        is Boolean -> BooleanValue(any)
        is Number -> NumberValue(any)
        else -> throw ContractException("Unknown value type: ${any::class.simpleName}")
    }
}
