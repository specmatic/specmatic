package io.specmatic.core.substitution

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Substitution
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

class SubstitutionImpl private constructor(
    private val resolver: Resolver,
    private val variableValues: Map<String, String> = emptyMap(),
    private val data: JSONObjectValue = JSONObjectValue(emptyMap()),
) : Substitution {
    private fun substituteSimpleVariableLookup(string: String): String {
        val name = string.trim().removeSurrounding("$(", ")")
        return variableValues[name]
            ?: throw ContractException("Could not resolve expression $string as no variable by the name $name was found")
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

    private fun isDataLookup(value: String): Boolean {
        return isLookup(value) && value.contains('[')
    }

    private fun isSimpleVariableLookup(value: String) =
        isLookup(value) && !value.contains('[')

    private fun isLookup(value: String) =
        value.startsWith("$(") && value.endsWith(")")

    override fun substitute(value: Value, pattern: Pattern, key: String?): ReturnValue<Value> {
        return try {
            if (value !is StringValue)
                HasValue(value)
            else if (isSimpleVariableLookup(value.string)) {
                val updatedString = substituteSimpleVariableLookup(value.string)
                HasValue(pattern.parse(updatedString, resolver))
            } else if (isDataLookup(value.string)) {
                val updatedString = substituteDataLookupExpression(value.string)
                HasValue(pattern.parse(updatedString, resolver))
            } else
                HasValue(value)
        } catch (e: Throwable) {
            HasException(e)
        }
    }

    override fun resolveIfLookup(value: Value, pattern: Pattern): Value {
        val strValue = (value as? StringValue)?.nativeValue ?: return value
        if (!isDataLookup(strValue)) return value

        val resolvedValue = runCatching { substituteDataLookupExpression(strValue) }.getOrElse { e ->
            logger.debug(e, "Error resolving data lookup expression ${value.string}, using original value")
            return value
        }

        return runCatching { pattern.parse(resolvedValue, resolver) }.getOrDefault(StringValue(resolvedValue))
    }

    override fun isDropDirective(value: Value): Boolean {
        val strValue = (value as? StringValue)?.nativeValue ?: return false

        val resolved =
            if (!isDataLookup(strValue)) {
                strValue
            } else {
                try {
                    substituteDataLookupExpression(strValue)
                } catch (e: Throwable) {
                    logger.debug(e, "Failed to check for drop directive")
                    strValue
                }
            }

        return resolved == DROP_DIRECTIVE
    }

    override fun upsertStoreUsing(originalValue: Value, runningValue: Value): Substitution {
        val extractedVariables = SubstitutionVariableStoreUpdater.fromValues(
            originalValue = originalValue,
            runningValue = runningValue
        )

        return SubstitutionImpl(
            data = data,
            resolver = resolver,
            variableValues = variableValues + extractedVariables,
        )
    }

    companion object {
        const val DROP_DIRECTIVE = "$(drop)"

        fun empty(resolver: Resolver): SubstitutionImpl {
            return SubstitutionImpl(resolver = resolver)
        }

        fun from(runningRequest: HttpRequest, originalRequest: HttpRequest, data: JSONObjectValue, resolver: Resolver): SubstitutionImpl {
            val variableValues = SubstitutionVariableExtractor.fromRequest(runningRequest = runningRequest, originalRequest = originalRequest)
            return SubstitutionImpl(variableValues = variableValues, resolver = resolver, data = data)
        }
    }
}

class MissingDataException(override val message: String) : Throwable(message)
