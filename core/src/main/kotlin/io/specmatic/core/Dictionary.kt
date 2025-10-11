package io.specmatic.core

import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.contains
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.jsonObjectMapper
import io.specmatic.core.utilities.yamlObjectMapper
import io.specmatic.core.utilities.yamlStringToValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.test.asserts.WILDCARD_INDEX
import java.io.File

data class Dictionary(private val data: Map<String, Value>, private val focusedData: Map<String, Value> = emptyMap()) {
    private val defaultData: Map<String, Value> = data.filterKeys(::isPatternToken)

    fun plus(other: Map<String, Value>): Dictionary {
        return copy(data = data + other)
    }

    fun containsKey(key: String): Boolean {
        return key in data
    }

    fun getRawValue(key: String): Value {
        if (key !in data) throw IllegalArgumentException("Dictionary does not contain key: $key")
        return data.getValue(key)
    }

    fun focusIntoSchema(pattern: Pattern, key: String, resolver: Resolver): Dictionary {
        return focusInto(pattern, key, resolver, data)
    }

    fun focusIntoProperty(pattern: Pattern, key: String, resolver: Resolver): Dictionary {
        return focusInto(pattern, key, resolver, focusedData)
    }

    fun <T> focusIntoSequence(pattern: T, childPattern: Pattern, key: String, resolver: Resolver): Dictionary where T: Pattern, T: SequenceType {
        return focusInto(pattern, key, resolver, focusedData) { value ->
            when (val valueToMatch = getValueToMatch(value, childPattern, resolver, overrideNestedCheck = false)) {
                is JSONObjectValue -> valueToMatch
                is Value -> JSONObjectValue(mapOf(key to valueToMatch))
                else -> value as? JSONObjectValue
            }
        }
    }

    fun getDefaultValueFor(pattern: Pattern, resolver: Resolver): Value? {
        val resolved = resolvedHop(pattern, resolver)
        val lookupKey = withPatternDelimiters(resolved.typeName)
        val defaultValue = defaultData[lookupKey] ?: return null
        return getReturnValueFor(lookupKey, defaultValue, resolved, resolver)?.withDefault(null) { it }
    }

    fun getValueFor(lookup: String, pattern: Pattern, resolver: Resolver): ReturnValue<Value>? {
        val tailEndKey = lookup.tailEndKey()
        val dictionaryValue = focusedData[tailEndKey] ?: return null
        return getReturnValueFor(lookup, dictionaryValue, pattern, resolver)
    }

    private fun focusInto(
        pattern: Pattern, key: String,
        resolver: Resolver, storeToUse: Map<String, Value>,
        onValue: (Value) -> JSONObjectValue? = { it as? JSONObjectValue }
    ): Dictionary {
        val rawValue = storeToUse[key] ?: return resetFocus()
        val valueToFocusInto = getValueToMatch(rawValue, pattern, resolver, true) ?: return resetFocus()
        val dataToFocusInto = onValue(valueToFocusInto)?.jsonObject ?: storeToUse
        return copy(focusedData = dataToFocusInto)
    }

    private fun getReturnValueFor(lookup: String, value: Value, pattern: Pattern, resolver: Resolver): ReturnValue<Value>? {
        val valueToMatch = getValueToMatch(value, pattern, resolver) ?: return null
        return runCatching {
            val result = pattern.fillInTheBlanks(valueToMatch, resolver.copy(isNegative = false), removeExtraKeys = true)
            if (result is ReturnFailure && resolver.isNegative) return null
            result.addDetails("Invalid Dictionary value at \"$lookup\"", breadCrumb = "")
        }.getOrElse(::HasException)
    }

    private fun getValueToMatch(value: Value, pattern: Pattern, resolver: Resolver, overrideNestedCheck: Boolean = false): Value? {
        if (value !is JSONArrayValue) return value.takeIf { pattern.isScalar(resolver) || overrideNestedCheck }
        if (pattern !is SequenceType) {
            return if (overrideNestedCheck) value else value.list.randomOrNull()
        }

        val patternDepth = calculateDepth<Pattern>(pattern) { (resolvedHop(it, resolver) as? SequenceType)?.memberList?.patternList() }
        val valueDepth = calculateDepth<Value>(value) { (it as? JSONArrayValue)?.list }
        return when {
            valueDepth > patternDepth -> value.list.randomOrNull()
            else -> value
        }
    }

    private fun <T> calculateDepth(data: T, getChildren: (T) -> List<T>?): Int {
        val children = getChildren(data) ?: return 0
        return when {
            children.isEmpty() -> 1
            else -> 1 + children.maxOf { calculateDepth(it, getChildren) }
        }
    }

    private fun String.tailEndKey(): String = substringAfterLast(".").removeSuffix(WILDCARD_INDEX)

    private fun Pattern.isScalar(resolver: Resolver): Boolean {
        val resolved = resolvedHop(this, resolver)
        return resolved is ScalarType || resolved is URLPathSegmentPattern || resolved is QueryParameterScalarPattern
    }

    private fun resetFocus(): Dictionary = copy(focusedData = emptyMap())

    companion object {
        fun from(file: File): Dictionary {
            if (!file.exists()) throw ContractException(
                breadCrumb = file.path,
                errorMessage = "Expected dictionary file at ${file.path}, but it does not exist"
            )

            if (!file.isFile) throw ContractException(
                breadCrumb = file.path,
                errorMessage = "Expected dictionary file at ${file.path} to be a file"
            )

            return runCatching {
                logger.log("Using dictionary file ${file.path}")
                from(data = DictionaryReader.read(file).jsonObject)
            }.getOrElse { e ->
                logger.debug(e)
                throw ContractException(
                    breadCrumb = file.path,
                    errorMessage = "Could not parse dictionary file ${file.path}, it must be a valid JSON/YAML object:\n${exceptionCauseMessage(e)}"
                )
            }
        }

        fun fromYaml(content: String): Dictionary {
            return runCatching  {
                val value = yamlStringToValue(content)
                if (value !is JSONObjectValue) throw ContractException("Expected dictionary file to be a YAML object")
                from(value.jsonObject)
            }.getOrElse { e ->
                throw ContractException(
                    breadCrumb = "Error while parsing YAML dictionary content",
                    errorMessage = exceptionCauseMessage(e)
                )
            }
        }

        fun from(data: Map<String, Value>): Dictionary {
            return Dictionary(data = data)
        }

        fun empty(): Dictionary {
            return Dictionary(data = emptyMap())
        }
    }
}

object DictionaryReader {
    private const val SPECMATIC_CONSTANTS = "SPECMATIC_CONSTANTS"

    fun read(dictFile: File): JSONObjectValue {
        val node = yamlObjectMapper.readTree(dictFile)

        if (node !is ObjectNode) return readValueAs<JSONObjectValue>(dictFile)

        val constants = extractAndRemoveConstantsFromNode(node)
        if (constants.isEmpty()) return readValueAs<JSONObjectValue>(dictFile)

        findConstantPlaceholders(node, constants).forEach { (pointer, value) ->
            replaceValueAtPointerWithGivenValue(pointer, value, node)
        }

        return readValueAs<JSONObjectValue>(
            content = jsonObjectMapper.writeValueAsString(node),
            extension = dictFile.extension
        )
    }

    private fun extractAndRemoveConstantsFromNode(node: ObjectNode): Map<String, Any> {
        if(node.contains(SPECMATIC_CONSTANTS).not()) return emptyMap()

        val constants = try {
            jsonObjectMapper.convertValue(
                node.get(SPECMATIC_CONSTANTS),
                object : TypeReference<Map<String, Any>>() {}
            )
        } catch(e: Throwable) {
            throw ContractException(
                errorMessage = "Could not parse $SPECMATIC_CONSTANTS as an object: ${e.message}. Please ensure it is a valid JSON/YAML object."
            )
        }

        node.remove(SPECMATIC_CONSTANTS)
        return constants
    }

    private fun replaceValueAtPointerWithGivenValue(
        pointer: JsonPointer,
        value: Any,
        json: ObjectNode
    ) {
        val parent = json.at(pointer.head())
        val propertyName = pointer.last().matchingProperty
        val arrayIndex = pointer.last().matchingIndex

        when {
            parent.isObject && propertyName != null -> {
                (parent as ObjectNode).replace(propertyName, jsonObjectMapper.valueToTree(value))
            }

            parent.isArray && arrayIndex >= 0 -> {
                val arrayNode = parent as ArrayNode
                arrayNode.set(arrayIndex, jsonObjectMapper.valueToTree<JsonNode>(value))
            }
        }
    }

    private fun findConstantPlaceholders(
        dictNode: JsonNode,
        constants: Map<String, Any>,
        path: String = "",
        replacements: MutableMap<JsonPointer, Any> = mutableMapOf()
    ): Map<JsonPointer, Any> {
        when {
            dictNode.isTextual -> {
                val text = dictNode.asText()
                if (text.startsWith("<") && text.endsWith(">")) {
                    val key = text.substring(1, text.length - 1)
                    constants[key]?.let { value ->
                        replacements.put(JsonPointer.compile(path), value)
                    }
                }
            }

            dictNode.isObject -> {
                dictNode.properties().forEach { (fieldName, fieldValue) ->
                    findConstantPlaceholders(fieldValue, constants, "$path/$fieldName", replacements)
                }
            }

            dictNode.isArray -> {
                dictNode.forEachIndexed { index, element ->
                    findConstantPlaceholders(element, constants, "$path/$index", replacements)
                }
            }
        }
        return replacements.toMap()
    }
}