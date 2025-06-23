package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Pattern

data class NumberValue(val number: Number) : Value, ScalarValue {
    override val httpContentType = "text/plain"

    override fun displayableValue(): String = toStringLiteral()
    override fun toStringLiteral() = number.toString()
    override fun displayableType(): String = "number"
    override fun exactMatchElseType(): Pattern = ExactValuePattern(this)
    override fun type(): Pattern = NumberPattern()

    override fun typeDeclarationWithKey(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> {
        val displayableType = if(number is Int || number is Long) {
            "integer"
        } else {
            displayableType()
        }
        
        return primitiveTypeDeclarationWithKey(key, types, exampleDeclarations, displayableType, number.toString())
    }

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun typeDeclarationWithoutKey(exampleKey: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> {
        val displayableType = if(number is Int || number is Long) {
            "integer"
        } else {
            displayableType()
        }

        return primitiveTypeDeclarationWithoutKey(
            exampleKey,
            types,
            exampleDeclarations,
            displayableType,
            number.toString(),
        )
    }

    override val nativeValue: Number
        get() = number

    override fun specificity(): Int = 1

    override fun toString() = number.toString()
}