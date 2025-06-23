package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.pattern.BooleanPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern

data class BooleanValue(val booleanValue: Boolean) : Value, ScalarValue {
    override val httpContentType = "text/plain"

    override fun displayableValue(): String = toStringLiteral()
    override fun toStringLiteral() = booleanValue.toString()
    override fun displayableType(): String = "boolean"
    override fun exactMatchElseType(): Pattern = ExactValuePattern(this)
    override fun type(): Pattern = BooleanPattern()

    override fun typeDeclarationWithKey(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            primitiveTypeDeclarationWithKey(key, types, exampleDeclarations, displayableType(), booleanValue.toString())

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun typeDeclarationWithoutKey(exampleKey: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            primitiveTypeDeclarationWithoutKey(exampleKey, types, exampleDeclarations, displayableType(), booleanValue.toString())

    override val nativeValue: Boolean
        get() = booleanValue

    override fun specificity(): Int = 1

    override fun toString() = booleanValue.toString()
}

val True = BooleanValue(true)
