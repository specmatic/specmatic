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
import io.specmatic.core.pattern.breadCrumb
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

class SubstitutionImpl private constructor(
    private val strictMode: Boolean = false,
    private val variableValues: Map<String, Value> = emptyMap(),
    private val data: JSONObjectValue = JSONObjectValue(emptyMap()),
) : Substitution {
    private fun substituteSimpleVariableLookup(string: String): Value {
        val name = string.trim().removeSurrounding("$(", ")")
        return variableValues[name]
            ?: throw ContractException("Could not resolve expression $string as no variable by the name $name was found")
    }

    private fun substituteDataLookupExpression(value: String): Value {
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

        val dictionaryLookupValue = variableValues[dictionaryLookupVariableName]?.toStringLiteral() ?: "*"

        val finalObject = dictionary.findFirstChildByPath(dictionaryLookupValue) ?: dictionary.findFirstChildByPath("*")
            ?: throw MissingDataException("Could not resolve lookup expression $value because variable $lookupStoreName.$dictionaryName[$dictionaryLookupVariableName] does not exist")

        val finalObjectDictionary = finalObject as? JSONObjectValue
            ?: throw ContractException("$lookupStoreName.$dictionaryName[$dictionaryLookupVariableName] should be an object")

        val valueToReturn = finalObjectDictionary.findFirstChildByPath(keyName)
            ?: throw ContractException("Could not resolve lookup expression $value because value $keyName in $lookupStoreName.$dictionaryName[$dictionaryLookupVariableName] does not exist")

        return valueToReturn
    }

    private fun isDataLookup(value: String): Boolean {
        return isLookup(value) && value.contains('[')
    }

    private fun isSimpleVariableLookup(value: String) =
        isLookup(value) && !value.contains('[')

    private fun isLookup(value: String) =
        value.startsWith("$(") && value.endsWith(")")

    private fun resolveValue(value: StringValue): Value {
        return InterpolatedSubstitution.resolve(value.string) { token ->
            when {
                isDataLookup(token) -> substituteDataLookupExpression(token)
                isSimpleVariableLookup(token) -> substituteSimpleVariableLookup(token)
                else -> StringValue(token)
            }
        }
    }

    private fun unresolvedSubstitutionFallback(value: StringValue, pattern: Pattern, key: String?, resolver: Resolver, e: Throwable): ReturnValue<Value> {
        if (strictMode) return HasException(e)
        logger.log(e, "Could not resolve substitution expression ${value.string}, using generated value instead")
        return runCatching {
            if (key == null) return@runCatching resolver.generate(pattern)
            resolver.generate(pattern.typeAlias, key, pattern)
        }.map(::HasValue).getOrElse(::HasException).breadCrumb(key)
    }

    override fun substitute(value: Value): ReturnValue<Value> {
        if (value !is StringValue) return HasValue(value)
        if (!InterpolatedSubstitution.containsLookup(value.string)) return HasValue(value)
        return runCatching { HasValue(resolveValue(value)) }.getOrElse(::HasException)
    }

    override fun substitute(value: Value, pattern: Pattern, resolver: Resolver, key: String?): ReturnValue<Value> {
        if (value !is StringValue) return HasValue(value)
        if (!InterpolatedSubstitution.containsLookup(value.string)) return HasValue(value)

        val resolvedValue = runCatching { resolveValue(value) }.getOrElse { e ->
            return unresolvedSubstitutionFallback(value, pattern, key, resolver, e)
        }

        return runCatching { HasValue(pattern.parse(resolvedValue.toUnformattedString(), resolver)) }.getOrElse { e ->
            return unresolvedSubstitutionFallback(value, pattern, key, resolver, e)
        }
    }

    override fun isDropDirective(value: Value): Boolean {
        val strValue = (value as? StringValue)?.nativeValue ?: return false

        val resolved: String =
            if (!isDataLookup(strValue)) {
                strValue
            } else {
                try {
                    substituteDataLookupExpression(strValue).toUnformattedString()
                } catch (e: Throwable) {
                    logger.debug(e, "Failed to check for drop directive")
                    strValue
                }
            }

        return resolved == DROP_DIRECTIVE
    }

    override fun upsertStoreUsing(originalValue: Value, runningValue: Value, resolver: Resolver): SubstitutionImpl {
        val extractedVariables = SubstitutionVariableExtractor.fromValues(
            resolver = resolver,
            runningValue = runningValue,
            originalValue = originalValue,
        )

        return SubstitutionImpl(
            data = data,
            strictMode = strictMode,
            variableValues = variableValues + extractedVariables,
        )
    }

    companion object {
        const val DROP_DIRECTIVE = "$(drop)"

        fun empty(data: JSONObjectValue = JSONObjectValue(), strictMode: Boolean = false): SubstitutionImpl {
            return SubstitutionImpl(strictMode = strictMode, data = data)
        }

        fun from(
            runningRequest: HttpRequest,
            originalRequest: HttpRequest,
            data: JSONObjectValue,
            resolver: Resolver,
            strictMode: Boolean = false,
        ): SubstitutionImpl {
            val variableValuesFromHeaders = SubstitutionVariableExtractor.fromMap(
                resolver = resolver,
                runningMap = runningRequest.headers,
                originalMap = originalRequest.headers,
            )

            val variableValuesFromRequestBody = SubstitutionVariableExtractor.fromValues(
                resolver = resolver,
                runningValue = runningRequest.body,
                originalValue = originalRequest.body,
            )

            val variableValuesFromQueryParams = SubstitutionVariableExtractor.fromMap(
                resolver = resolver,
                runningMap = runningRequest.queryParams.asMap(),
                originalMap = originalRequest.queryParams.asMap(),
            )

            val variableValuesFromPath = SubstitutionVariableExtractor.fromPath(
                resolver = resolver,
                runningPath = runningRequest.path,
                originalPath = originalRequest.path,
            )

            val variableValues = variableValuesFromHeaders + variableValuesFromRequestBody + variableValuesFromQueryParams + variableValuesFromPath
            return SubstitutionImpl(variableValues = variableValues, data = data, strictMode = strictMode)
        }
    }
}

class MissingDataException(override val message: String) : Throwable(message)
