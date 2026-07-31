package io.specmatic.core.value

import io.specmatic.core.pattern.NullPattern
import io.specmatic.core.pattern.Pattern

object NullValue : Value, ScalarValue {
    override val httpContentType: String = "text/plain"

    override fun valueErrorSnippet(): String = this.displayableType()

    override fun displayableValue(): String = "null"
    override fun toStringLiteral() = "(null)"
    override fun displayableType(): String = "null"
    override fun exactMatchElseType(): Pattern = NullPattern
    override fun type(): Pattern = NullPattern
    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override val nativeValue: Any?
        get() = null

    override fun alterValue(): NullValue {
        return NullValue
    }

    override fun specificity(): Int = 1

    override fun toString() = ""
}
