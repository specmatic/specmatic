package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.valueArrayToJsonString

typealias TypeDeclarationsCallType = (Value, String, Map<String, Pattern>, ExampleDeclarations) -> Pair<TypeDeclaration, ExampleDeclarations>

data class JSONArrayValue(override val list: List<Value>) : Value, ListValue, JSONComposite {
    override val httpContentType: String = "application/json"

    override fun displayableValue(): String = toStringLiteral()
    override fun toStringLiteral() = valueArrayToJsonString(list)
    override fun displayableType(): String = "json array"
    override fun exactMatchElseType(): Pattern = JSONArrayPattern(list.map { it.exactMatchElseType() })
    override fun type(): Pattern = JSONArrayPattern()
    override fun deepPattern(): Pattern {
        return if (list.isEmpty()) ListPattern(AnythingPattern) else ListPattern(list.first().deepPattern())
    }

    private fun typeDeclaration(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations, typeDeclarationsStoreCall: TypeDeclarationsCallType): Pair<TypeDeclaration, ExampleDeclarations> = when {
        list.isEmpty() -> Pair(TypeDeclaration(LIST_BREAD_CRUMB, types), exampleDeclarations)
        else -> {
            val declarations = list.map {
                val (typeDeclaration, newExamples) = typeDeclarationsStoreCall(it, key, types, exampleDeclarations)
                Pair(TypeDeclaration("(${withoutPatternDelimiters(typeDeclaration.typeValue)}*)", typeDeclaration.types), newExamples)
            }.let { declarations ->
                when {
                    list.first() is ScalarValue -> declarations.map { Pair(removeKey(it.first), exampleDeclarations) }
                    else -> declarations
                }
            }

            val newExamples = declarations.first().second
            val convergedType = declarations.map { it.first }.reduce { converged, current -> convergeTypeDeclarations(converged, current) }

            Pair(convergedType, newExamples)
        }
    }

    private fun removeKey(declaration: TypeDeclaration): TypeDeclaration {
        val newTypeValue = when {
            declaration.typeValue.contains(":") -> {
                val withoutKey = withoutPatternDelimiters(declaration.typeValue).split(":")[1].trim()
                "($withoutKey)"
            }
            else -> declaration.typeValue
        }

        return declaration.copy(typeValue = newTypeValue)
    }

    override fun typeDeclarationWithKey(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            typeDeclaration(key, types, exampleDeclarations) { value, innerKey, innerTypes, newExamples -> value.typeDeclarationWithKey(innerKey, innerTypes, newExamples) }

    override fun listOf(valueList: List<Value>): Value {
        TODO("Not yet implemented")
    }

    override fun typeDeclarationWithoutKey(exampleKey: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            typeDeclaration(exampleKey, types, exampleDeclarations) { value, innerKey, innerTypes, newExamples -> value.typeDeclarationWithoutKey(innerKey, innerTypes, newExamples) }

    override fun checkIfAllRootLevelKeysAreAttributeSelected(
        attributeSelectedFields: Set<String>,
        resolver: Resolver
    ): Result {
        if(list.all { it is JSONObjectValue }.not()) return Result.Success()

        return Result.fromResults(
            results = list.mapIndexed { index, it ->
                (it as JSONObjectValue).checkIfAllRootLevelKeysAreAttributeSelected(attributeSelectedFields, resolver).breadCrumb("[$index]")
            }
        )
    }

    override fun toString() = valueArrayToJsonString(list)
    fun getElementAtIndex(first: String, rest: List<String>): Value? {
        val trimmed = first.trim()

        if(trimmed.first() != '[' || trimmed.last() != ']')
            return null

        val index = trimmed.trim().removeSurrounding("[", "]").toIntOrNull() ?: return null

        val value = list[index]

        return when {
            rest.isEmpty() -> value
            value is JSONObjectValue -> value.findFirstChildByPath(rest)
            value is JSONArrayValue -> value.getElementAtIndex(rest.first(), rest.drop(1))
            else -> null
        }

    }

    override fun generality(): Int {
        return list.sumOf { it.generality() }
    }

    override fun specificity(): Int {
        return list.sumOf { it.specificity() }
    }
}
