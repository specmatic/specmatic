package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

class Substitution(
    runningRequest: HttpRequest,
    private val originalRequest: HttpRequest,
    val httpPathPattern: HttpPathPattern,
    val headersPattern: HttpHeadersPattern,
    val body: Pattern,
    val resolver: Resolver,
    val data: JSONObjectValue,
) {
    private val variableValues: Map<String, String>

    init {
        val variableValuesFromHeaders = variablesFromMap(runningRequest.headers.filter { it.key in originalRequest.headers }, originalRequest.headers)
        val variableValuesFromQueryParams = variablesFromMap(runningRequest.queryParams.asMap(), originalRequest.queryParams.asMap())

        val runningPathPieces = runningRequest.path!!.split('/').filterNot { it.isBlank() }
        val originalPathPieces = originalRequest.path!!.split('/').filterNot { it.isBlank() }

        val variableValuesFromPath = runningPathPieces.zip(originalPathPieces)
            .mapNotNull { (runningPiece, originalPiece) ->
                if (!isPatternToken(originalPiece))
                    null
                else {
                    val pieces = withoutPatternDelimiters(originalPiece).split(':')
                    val name = pieces.getOrNull(0)
                        ?: throw ContractException("Could not interpret substituion expression $originalPiece")

                    name to runningPiece
                }
            }.toMap()

        val variableValuesFromRequestBody: Map<String, String> = getVariableValuesFromValue(runningRequest.body, originalRequest.body)

        variableValues = variableValuesFromHeaders + variableValuesFromRequestBody + variableValuesFromQueryParams + variableValuesFromPath
    }

    private fun variableFromString(value: String, originalValue: String): Pair<String, String>? {
        if(!isPatternToken(originalValue))
            return null

        val pieces = withoutPatternDelimiters(originalValue).split(":")

        val name = pieces.getOrNull(0) ?: return null

        return Pair(name, value)
    }

    private fun variablesFromMap(map: Map<String, String>, originalMap: Map<String, String>) = map.entries.map { (key, value) ->
        val originalValue = originalMap[key] ?: return@map null
        variableFromString(value, originalValue)
    }.filterNotNull().toMap()

    private fun getVariableValuesFromValue(value: JSONObjectValue, originalValue: JSONObjectValue): Map<String, String> {
        return originalValue.jsonObject.entries.fold(emptyMap()) { acc, entry ->
            val runningValue = value.jsonObject.getValue(entry.key)
            acc + getVariableValuesFromValue(runningValue, entry.value)
        }
    }

    private fun getVariableValuesFromValue(value: JSONArrayValue, originalValue: JSONArrayValue): Map<String, String> {
        return originalValue.list.foldRightIndexed(emptyMap()) { index: Int, item: Value, acc: Map<String, String> ->
            val runningItem = value.list[index]
            acc + getVariableValuesFromValue(runningItem, item)
        }
    }

    private fun getVariableValuesFromValue(value: Value, originalValue: Value): Map<String, String> {
        return when (originalValue) {
            is StringValue -> {
                if(isPatternToken(originalValue.string)) {
                    val pieces = withoutPatternDelimiters(originalValue.string).split(":")
                    val name = pieces.getOrNull(0) ?: return emptyMap()

                    mapOf(name to value.toStringLiteral())
                } else emptyMap()
            }
            is JSONObjectValue -> getVariableValuesFromValue(value as JSONObjectValue, originalValue)
            is JSONArrayValue -> getVariableValuesFromValue(value as JSONArrayValue, originalValue)
            else -> emptyMap()
        }
    }

    fun resolveSubstitutions(value: Value): Value {
        return when(value) {
            is JSONObjectValue -> resolveSubstitutions(value)
            is JSONArrayValue -> resolveSubstitutions(value)
            is StringValue -> {
                if(value.string.startsWith("{{") && value.string.endsWith("}}"))
                    StringValue(substituteSimpleVariableLookup(value.string))
                else
                    value
            }
            else -> value
        }
    }

    private fun substituteSimpleVariableLookup(string: String, key: String? = null): String {
        val name = string.trim().removeSurrounding("$(", ")")
        return variableValues[name]
                ?: throw ContractException("Could not resolve expression $string as no variable by the name $name was found")
    }

    private fun resolveSubstitutions(value: JSONObjectValue): Value {
        return value.copy(
            jsonObject = value.jsonObject.mapValues { entry ->
                resolveSubstitutions(entry.value)
            }
        )
    }

    private fun resolveSubstitutions(value: JSONArrayValue): Value {
        return value.copy(
            list = value.list.map {
                resolveSubstitutions(it)
            }
        )
    }

    fun resolveHeaderSubstitutions(headers: Map<String, String>, patternMap: Map<String, Pattern>): ReturnValue<Map<String, String>> {
        return headers.mapValues { (key, value) ->
            val returnValue = if(key !in patternMap && "$key?" !in patternMap)
                HasValue(value)
            else {
                val substituteValue = substituteVariableValues(value.trim(), key)

                (patternMap[key] ?: patternMap["$key?"])?.let { pattern ->
                    try {
                        HasValue(pattern.parse(substituteValue, resolver).toStringLiteral())
                    } catch (e: Throwable) {
                        HasException(e)
                    }
                } ?: HasValue(value)
            }

            returnValue.breadCrumb(key)
        }.mapFold()
    }

    private fun substituteVariableValues(value: String, key: String): String {
        return if(isSimpleVariableLookup(value)) {
            substituteSimpleVariableLookup(value, key)
        } else if(isDataLookup(value)) {
            substituteDataLookupExpression(value)
        } else value
    }

    private fun substituteDataLookupExpression(value: String): String {
        val pieces = value.removeSurrounding("$(", ")").split('.')

        val lookupSyntaxErrorMessage =
            "Could not resolve lookup expression $value. Syntax should be $(lookupData.dictionary[VARIABLE_NAME].key)"

        if (pieces.size != 3) throw ContractException(lookupSyntaxErrorMessage)

        val (lookupStoreName, dictionaryLookup, keyName) = pieces

        val dictionaryPieces = dictionaryLookup.split('[')
        if (dictionaryPieces.size != 2) throw ContractException(lookupSyntaxErrorMessage)

        val (dictionaryName, dictionaryLookupVariableName) = dictionaryPieces.map { it.removeSuffix("]") }

        val lookupStore = data.findFirstChildByPath(lookupStoreName)
            ?: throw ContractException("Data store named $dictionaryName not found")

        val lookupStoreDictionary: JSONObjectValue = lookupStore as? JSONObjectValue
            ?: throw ContractException("Data store named $dictionaryName should be an object")

        val dictionaryValue = lookupStoreDictionary.findFirstChildByPath(dictionaryName)
            ?: throw ContractException("Could not resolve lookup expression $value because $lookupStoreName.$dictionaryName does not exist")

        val dictionary: JSONObjectValue = dictionaryValue as? JSONObjectValue
            ?: throw ContractException("Dictionary $lookupStoreName.$dictionaryName should be an object")

        val dictionaryLookupValue = variableValues[dictionaryLookupVariableName] ?: "*"

        val finalObject = dictionary.findFirstChildByPath(dictionaryLookupValue) ?: dictionary.findFirstChildByPath("*")
            ?: throw MissingDataException("Could not resolve lookup expression $value because variable $lookupStoreName.$dictionaryName[$dictionaryLookupVariableName] does not exist")

        val finalObjectDictionary = finalObject as? JSONObjectValue
            ?: throw ContractException("$lookupStoreName.$dictionaryName[$dictionaryLookupVariableName] should be an object")

        val valueToReturn = finalObjectDictionary.findFirstChildByPath(keyName)
            ?: throw ContractException("Could not resolve lookup expression $value because value $keyName in $lookupStoreName.$dictionaryName[$dictionaryLookupVariableName] does not exist")

        return valueToReturn.toStringLiteral()
    }

    class Not

    private fun isDataLookup(value: String): Boolean {
        return isLookup(value) && value.contains('[')
    }

    private fun isSimpleVariableLookup(value: String) =
        isLookup(value) && !value.contains('[')

    private fun isLookup(value: String) =
        value.startsWith("$(") && value.endsWith(")")

    fun substitute(value: Value, pattern: Pattern, key: String? = null): ReturnValue<Value> {
        return try {
            if(value !is StringValue)
                HasValue(value)
            else if(isSimpleVariableLookup(value.string)) {
                val updatedString = substituteSimpleVariableLookup(value.string, key)
                HasValue(pattern.parse(updatedString, resolver))
            } else if (isDataLookup(value.string)) {
                val updatedString = substituteDataLookupExpression(value.string)
                HasValue(pattern.parse(updatedString, resolver))
            } else
                HasValue(value)
        } catch(e: Throwable) {
            HasException(e)
        }
    }

    fun resolveIfLookup(value: Value, pattern: Pattern): Value {
        val strValue = (value as? StringValue)?.nativeValue ?: return value
        if (!isDataLookup(strValue)) return value

        val resolvedValue = runCatching { substituteDataLookupExpression(strValue) }.getOrElse { return value }
        val patternFromValue = resolver.patternFromTokenBased(StringValue(resolvedValue)) ?: return pattern.parse(resolvedValue, resolver)

        return when {
            patternFromValue is AnyValuePattern -> pattern.generate(resolver)
            pattern.encompasses(patternFromValue, resolver, resolver).isSuccess() -> patternFromValue.generate(resolver)
            else -> throw ContractException("""
            Cannot resolve substitutions, expected ${pattern.typeAlias ?: pattern.typeName} but got ${patternFromValue.typeAlias ?: patternFromValue.typeName}
            """.trimIndent()
            )
        }
    }

    fun isDropDirective(value: Value): Boolean {
        val strValue = (value as? StringValue)?.nativeValue ?: return false
        if (!isDataLookup(strValue)) return false
        val resolvedValue = runCatching { substituteDataLookupExpression(strValue) }.getOrElse { return false }
        return resolvedValue == "(drop)"
    }

    private fun hasTemplate(string: String): Boolean {
        return string.startsWith("{{") && string.endsWith("}}")
    }

    fun substitute(string: String, pattern: Pattern): ReturnValue<Value> {
        return try {
            val updatedString = substituteSimpleVariableLookup(string)
            HasValue(pattern.parse(updatedString, resolver))
        } catch(e: Throwable) {
            HasException(e)
        }
    }
}

class MissingDataException(override val message: String) : Throwable(message)
