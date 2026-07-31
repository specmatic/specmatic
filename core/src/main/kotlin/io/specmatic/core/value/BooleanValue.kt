package io.specmatic.core.value

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

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override val nativeValue: Boolean
        get() = booleanValue

    override fun alterValue(): BooleanValue {
        return copy(booleanValue = !booleanValue)
    }

    override fun specificity(): Int = 1

    override fun toString() = booleanValue.toString()
}

val True = BooleanValue(true)
