package io.specmatic.core.value

import io.specmatic.core.ExampleDeclarations
import io.specmatic.core.Result
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.ktor.http.*
import org.w3c.dom.Document
import org.w3c.dom.Node

data class BinaryValue(val byteArray: ByteArray = ByteArray(0)) : Value, ScalarValue, XMLValue {
    override val httpContentType = "application/octet-stream"

    override fun valueErrorSnippet(): String = displayableValue()

    override fun displayableValue(): String = toStringLiteral().quote()
    override fun toStringLiteral() = byteArray.contentToString()
    override fun displayableType(): String = "binary"
    override fun exactMatchElseType(): Pattern = ExactValuePattern(this)

    override fun build(document: Document): Node = document.createTextNode(byteArray.contentToString())

    override fun matchFailure(): Result.Failure =
        Result.Failure("Unexpected child value found: $byteArray")

    override fun addSchema(schema: XMLNode): XMLValue = this

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun specificity(): Int = 1

    override fun type(): Pattern = StringPattern()

    override fun typeDeclarationWithKey(
        key: String,
        types: Map<String, Pattern>,
        exampleDeclarations: ExampleDeclarations
    ): Pair<TypeDeclaration, ExampleDeclarations> =
        primitiveTypeDeclarationWithKey(key, types, exampleDeclarations, displayableType(), byteArray.contentToString())

    override fun typeDeclarationWithoutKey(
        exampleKey: String,
        types: Map<String, Pattern>,
        exampleDeclarations: ExampleDeclarations
    ): Pair<TypeDeclaration, ExampleDeclarations> =
        primitiveTypeDeclarationWithoutKey(
            exampleKey,
            types,
            exampleDeclarations,
            displayableType(),
            byteArray.contentToString()
        )

    override val nativeValue: ByteArray
        get() = byteArray

    override fun toString() = byteArray.contentToString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BinaryValue

        return byteArray.contentEquals(other.byteArray)
    }

    override fun hashCode(): Int {
        return byteArray.contentHashCode()
    }
}
