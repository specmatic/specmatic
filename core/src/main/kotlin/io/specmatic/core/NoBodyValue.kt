package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.*

object NoBodyValue : Value {
    override val httpContentType: String
        get() = ""

    override fun displayableValue(): String {
        return "No body"
    }

    override fun toStringLiteral(): String {
        return ""
    }

    override fun displayableType(): String {
        return "No body"
    }

    override fun exactMatchElseType(): Pattern {
        return NoBodyPattern
    }

    override fun type(): Pattern {
        return NoBodyPattern
    }

    override fun typeDeclarationWithoutKey(
        exampleKey: String,
        types: Map<String, Pattern>,
        exampleDeclarations: ExampleDeclarations
    ): Pair<TypeDeclaration, ExampleDeclarations> {
        return TypeDeclaration("No body", types) to exampleDeclarations
    }

    override fun typeDeclarationWithKey(
        key: String,
        types: Map<String, Pattern>,
        exampleDeclarations: ExampleDeclarations
    ): Pair<TypeDeclaration, ExampleDeclarations> {
        return TypeDeclaration("No body", types) to exampleDeclarations
    }

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun specificity(): Int = 0

    override fun toString(): String = toStringValue().string

    override fun toStringValue(): StringValue {
        return EmptyString
    }
}
