package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.*

data class JSONObjectValue(val jsonObject: Map<String, Value> = emptyMap()) : Value, JSONComposite {
    override val httpContentType = "application/json"

    override fun valueErrorSnippet(): String {
        return "JSON object ${displayableValue()}"
    }

    override fun displayableValue() = toStringLiteral()
    override fun toStringLiteral() = valueMapToPrettyJsonString(jsonObject)
    fun toUnformattedStringLiteral() = valueMapToUnindentedJsonString(jsonObject)
    override fun displayableType(): String = "json object"
    override fun exactMatchElseType(): Pattern = toJSONObjectPattern(jsonObject.mapValues { it.value.exactMatchElseType() })
    override fun type(): Pattern = JSONObjectPattern()

    override fun deepPattern(): Pattern = toJSONObjectPattern(jsonObject.mapValues { it.value.deepPattern() })

    fun removeKeysNotPresentIn(keys: Set<String>): JSONObjectValue {
        if (keys.isEmpty()) return this
        return this.copy(jsonObject = jsonObject.filterKeys {
            withoutOptionality(it) in keys
        }.mapKeys {
            withoutOptionality(it.key)
        })
    }

    override fun checkIfAllRootLevelKeysAreAttributeSelected(
        attributeSelectedFields: Set<String>,
        resolver: Resolver
    ): Result {
        if (jsonObject.keys == attributeSelectedFields) return Result.Success()

        return Result.fromResults(
            results = resolver.findKeyErrorList(
                attributeSelectedFields.associateBy { it },
                jsonObject
            ).map {
                it.missingKeyToResult("key", resolver.mismatchMessages).breadCrumb(it.name)
            }
        )
    }

    override fun toString() = valueMapToPrettyJsonString(jsonObject)

    override fun typeDeclarationWithKey(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> {
        val (jsonTypeMap, newTypes, newExamples) = dictionaryToDeclarations(jsonObject, types, exampleDeclarations)

        val newType = toTabularPattern(jsonTypeMap.mapValues {
            DeferredPattern(it.value.pattern)
        })

        val newTypeName = exampleDeclarations.getNewName(key.capitalizeFirstChar(), newTypes.keys)

        val typeDeclaration = TypeDeclaration("($newTypeName)", newTypes.plus(newTypeName to newType))

        return Pair(typeDeclaration, newExamples)
    }

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun typeDeclarationWithoutKey(exampleKey: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            typeDeclarationWithKey(exampleKey, types, exampleDeclarations)

    fun getString(key: String): String {
        return (jsonObject.getValue(key) as StringValue).string
    }

    fun getBoolean(key: String): Boolean {
        return (jsonObject.getValue(key) as BooleanValue).booleanValue
    }

    fun getInt(key: String): Int {
        return (jsonObject.getValue(key) as NumberValue).number.toInt()
    }

    fun getJSONObject(key: String): Map<String, Value> {
        return (jsonObject.getValue(key) as JSONObjectValue).jsonObject
    }

    fun getJSONObjectValue(key: String): JSONObjectValue {
        return jsonObject.getValue(key) as JSONObjectValue
    }

    fun getJSONArray(key: String): List<Value> {
        return (jsonObject.getValue(key) as JSONArrayValue).list
    }

    fun findFirstChildByPath(path: String): Value? =
        findFirstChildByPath(path.split("."))

    fun findFirstChildByPath(path: List<String>): Value? =
        findFirstChildByPath(path.first(), path.drop(1))

    private fun findFirstChildByPath(first: String, rest: List<String>): Value? {
        return findFirstChildByName(first)?.let {
            when {
                rest.isEmpty() -> it
                it is JSONObjectValue -> it.findFirstChildByPath(rest)
                it is JSONArrayValue -> it.getElementAtIndex(rest.first(), rest.drop(1))
                else -> null
            }
        }
    }

    fun findFirstChildByName(name: String): Value? =
        jsonObject[name]

    fun keys() = jsonObject.keys
    fun addEntry(key: String, value: String): JSONObjectValue {
        return this.copy(
            jsonObject = jsonObject.plus(key to StringValue(value))
        )
    }

    override fun generality(): Int {
        return jsonObject.values.sumOf { it.generality() }
    }

    override fun specificity(): Int {
        return jsonObject.values.sumOf { it.specificity() }
    }
}

internal fun dictionaryToDeclarations(jsonObject: Map<String, Value>, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Triple<Map<String, DeferredPattern>, Map<String, Pattern>, ExampleDeclarations> {
    return jsonObject
            .entries
            .fold(Triple(emptyMap(), types, exampleDeclarations)) { acc, entry ->
                val (jsonTypeMap, accTypes, accExamples) = acc
                val (key, value) = entry

                val (newTypes, newExamples) = value.typeDeclarationWithKey(key, accTypes, accExamples)
                Triple(jsonTypeMap.plus(key to DeferredPattern(newTypes.typeValue)), newTypes.types, newExamples)
            }
}
