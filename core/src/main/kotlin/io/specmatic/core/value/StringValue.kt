package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.Result
import io.specmatic.core.pattern.*
import io.ktor.http.*
import org.apache.commons.lang3.StringEscapeUtils
import org.w3c.dom.Document
import org.w3c.dom.Node

data class StringValue(val string: String = "", private val xml: Boolean) : Value, ScalarValue, XMLValue {
    constructor(string: String = "") : this(string, false)

    override val httpContentType = "text/plain"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is CDATAValue) return other.stringValue.string == this.string

        if (other !is StringValue) return false

        return string == other.string
    }

    override fun valueErrorSnippet(): String = displayableValue()

    override fun displayableValue(): String = toStringLiteral().quote()

    override fun toStringLiteral(): String {
        if (xml) {
            return StringEscapeUtils.escapeXml11(string)
        }

        return string
    }

    override fun displayableType(): String = "string"

    override fun exactMatchElseType(): Pattern {
        return when {
            isPatternToken() -> DeferredPattern(string)
            isMatcherToken() -> parsedPattern(string)
            else -> ExactValuePattern(this)
        }
    }

    override fun build(document: Document): Node = document.createTextNode(string)

    override fun matchFailure(): Result.Failure =
        Result.Failure("Unexpected child value found: $string")

    override fun addSchema(schema: XMLNode): XMLValue = this

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun type(): Pattern = StringPattern()

    override fun typeDeclarationWithKey(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            primitiveTypeDeclarationWithKey(key, types, exampleDeclarations, displayableType(), string)

    override fun typeDeclarationWithoutKey(exampleKey: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            primitiveTypeDeclarationWithoutKey(exampleKey, types, exampleDeclarations, displayableType(), string)

    override val nativeValue: String
        get() = string

    override fun toString() = string

    fun isPatternToken(): Boolean = isPatternToken(string.trim())
    fun isMatcherToken(): Boolean = isMatcherToken(string.trim())
    fun isPatternOrMatcherToken(): Boolean = isPatternToken() || isMatcherToken()
    fun trimmed(): StringValue = StringValue(string.trim())

    override fun generality(): Int {
        return if(isPatternOrMatcherToken(string)) 1 else 0
    }

    override fun specificity(): Int {
        return if(!isPatternOrMatcherToken(string)) 1 else 0
    }
}