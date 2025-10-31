package io.specmatic.core

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.yamlStringToValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.test.ExampleProcessor
import io.specmatic.test.ExampleProcessor.toFactStore
import io.specmatic.test.asserts.WILDCARD_INDEX
import java.io.File

data class Dictionary(private val data: Map<String, Value>, private val focusedData: Map<String, Value> = emptyMap()) {
    private val defaultData: Map<String, Value> = data.filterKeys(::isPatternToken)

    fun addParameters(parameters: Map<String, Value>, breadCrumb: BreadCrumb): Dictionary {
        val parameters = mapOf(
            BreadCrumb.PARAMETERS.value to JSONObjectValue(
                mapOf(breadCrumb.value to JSONObjectValue(parameters)),
            ),
        )
        return copy(data = data.plus(parameters))
    }

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
        private const val SPECMATIC_CONSTANTS = "SPECMATIC_CONSTANTS"

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
                val dictionary = readValueAs<JSONObjectValue>(file).resolveConstants()
                from(data = dictionary.jsonObject)
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

        private fun JSONObjectValue.resolveConstants(): JSONObjectValue {
            if (this.jsonObject.containsKey(SPECMATIC_CONSTANTS).not()) return this

            val constants = this.getJSONObjectValue(SPECMATIC_CONSTANTS).toFactStore()
            return ExampleProcessor.resolve(this) { lookupKey, _ ->
                constants[lookupKey]
                    ?: throw ContractException("Could not find the replacement for the lookup key '$lookupKey' while resolving $SPECMATIC_CONSTANTS in the dictionary")
            }
        }
    }
}
