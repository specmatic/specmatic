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

data class Dictionary(
    private val data: Map<String, Value>,
    private val focusedData: Map<String, Value> = emptyMap(),
    private val strictMode: Boolean = false
) {
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
        return getReturnValueFor(lookupKey, defaultValue, resolved, resolver)?.realise(
            hasValue = { it, _ -> it },
            orFailure =  { f ->
                logger.debug(f.toFailure().reportString()); null
            },
            orException = { e ->
                logger.debug(e.toHasFailure().toFailure().reportString()); null
            },
        )
    }

    fun getValueFor(lookup: String, pattern: Pattern, resolver: Resolver): Value? {
        val tailEndKey = lookup.tailEndKey()
        val dictionaryValue = focusedData[tailEndKey] ?: return null
        return getReturnValueFor(lookup, dictionaryValue, pattern, resolver)?.realise(
            hasValue = { it, _ -> it },
            orFailure =  { f ->
                if (strictMode) f.toFailure().throwOnFailure()
                else logger.debug(f.toFailure().reportString()); null
            },
            orException = { e ->
                if (strictMode) e.toHasFailure().toFailure().throwOnFailure()
                else logger.debug(e.toHasFailure().toFailure().reportString()); null
            },
        )
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
        return runCatching {
            val valueToMatch = getValueToMatch(value, pattern, resolver) ?: return null
            val result = pattern.fillInTheBlanks(valueToMatch, resolver.copy(isNegative = false), removeExtraKeys = true)
            if (result is ReturnFailure && resolver.isNegative) return null
            result.addDetails("Invalid Dictionary value at \"$lookup\"", breadCrumb = "")
        }.getOrElse(::HasException)
    }

    private fun getValueToMatch(value: Value, pattern: Pattern, resolver: Resolver, overrideNestedCheck: Boolean = false): Value? {
        if (value !is JSONArrayValue) return value.takeIf { pattern.isScalar(resolver) || overrideNestedCheck }
        if (pattern !is SequenceType) {
            return if (overrideNestedCheck) value else selectValue(pattern, value.list, resolver)
        }

        val patternDepth = calculateDepth<Pattern>(pattern) { (resolvedHop(it, resolver) as? SequenceType)?.memberList?.patternList() }
        val valueDepth = calculateDepth<Value>(value) { (it as? JSONArrayValue)?.list }
        return when {
            valueDepth > patternDepth -> selectValue(pattern, value.list, resolver)
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
        return resolved is ScalarType || resolved is URLPathSegmentPattern
    }

    private fun resetFocus(): Dictionary = copy(focusedData = emptyMap())

    private fun selectValue(pattern: Pattern, values: List<Value>, resolver: Resolver): Value? {
        if (!strictMode) return selectValueLenient(pattern, values, resolver)
        return selectValueLenient(pattern, values, resolver) ?: throw ContractException(
            errorMessage = """
            None of the dictionary values matched the schema.
            This could happen due to conflicts in the dictionary at the same json path, due to conflicting dataTypes at the same json path between multiple payloads
            strictMode enforces the presence of matching values in the dictionary if the json-path is present
            Either ensure that a matching value exists in the dictionary or disable strictMode
            """.trimIndent()
        )
    }

    private fun selectValueLenient(pattern: Pattern, values: List<Value>, resolver: Resolver): Value? {
        return values.shuffled().firstOrNull { value ->
            runCatching {
                val result = pattern.matches(value, resolver.copy(findKeyErrorCheck = noPatternKeyCheckDictionary))
                if (result is Result.Failure) {
                    logger.debug("Invalid value $value from dictionary for ${pattern.typeName}")
                    logger.debug(result.reportString())
                }
                result.isSuccess()
            }.getOrElse { e ->
                logger.debug(e, "Failed to select value $value from dictionary for ${pattern.typeName}")
                false
            }
        }
    }

    companion object {
        private const val SPECMATIC_CONSTANTS = "SPECMATIC_CONSTANTS"
        private val noPatternKeyCheckDictionary = KeyCheck(noPatternKeyCheck, IgnoreUnexpectedKeys)

        fun from(file: File, strictMode: Boolean = false): Dictionary {
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
                from(data = dictionary.jsonObject, strictMode)
            }.getOrElse { e ->
                logger.debug(e)
                throw ContractException(
                    breadCrumb = file.path,
                    errorMessage = "Could not parse dictionary file ${file.path}, it must be a valid JSON/YAML object:\n${exceptionCauseMessage(e)}"
                )
            }
        }

        fun fromYaml(content: String, strictMode: Boolean = false): Dictionary {
            return runCatching  {
                val value = yamlStringToValue(content)
                if (value !is JSONObjectValue) throw ContractException("Expected dictionary file to be a YAML object")
                from(value.jsonObject, strictMode)
            }.getOrElse { e ->
                throw ContractException(
                    breadCrumb = "Error while parsing YAML dictionary content",
                    errorMessage = exceptionCauseMessage(e)
                )
            }
        }

        fun from(data: Map<String, Value>, strictMode: Boolean = false): Dictionary {
            return Dictionary(data = data, strictMode = strictMode)
        }

        fun empty(strictMode: Boolean = false): Dictionary {
            return Dictionary(data = emptyMap(), strictMode = strictMode)
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
