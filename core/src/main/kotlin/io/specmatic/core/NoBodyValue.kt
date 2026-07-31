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

    override fun toNativeValue(): Any? {
        return null
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

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun specificity(): Int = 0

    override fun toString(): String = toStringValue().string

    override fun toStringValue(): StringValue {
        return EmptyString
    }
}
