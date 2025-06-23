package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.pattern.Pattern

interface Value {
    fun valueErrorSnippet(): String = "${this.displayableValue()} (${this.displayableType()})"

    val httpContentType: String

    fun displayableValue(): String
    fun toStringLiteral(): String

    fun toStringValue(): StringValue {
        return StringValue(toStringLiteral())
    }

    fun displayableType(): String
    fun exactMatchElseType(): Pattern
    fun type(): Pattern

    fun deepPattern(): Pattern {
        return type()
    }

    fun typeDeclarationWithoutKey(exampleKey: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations>
    fun typeDeclarationWithKey(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations>
    fun listOf(valueList: List<Value>): Value

    fun hasTemplate(): Boolean {
        return this is StringValue
                && this.string.startsWith("$(")
                && this.string.endsWith(")")
    }

    fun hasDataTemplate(): Boolean {
        return this is StringValue && this.string.hasDataTemplate()
    }

    fun generality(): Int {
        return 0
    }

    fun specificity(): Int
}

fun Value.mergeWith(other: Value): Value {
    require(other is JSONObjectValue) { "Can only merge JSONObjectValues, got ${other.javaClass.name}" }
    return when (this) {
        is JSONObjectValue -> JSONObjectValue(jsonObject.plus(other.jsonObject))
        is JSONArrayValue -> JSONArrayValue(list.map { it.mergeWith(other) })
        else -> this
    }
}

fun String.hasDataTemplate(): Boolean {
    return this.startsWith("$(") && this.endsWith(")")
}