package `in`.specmatic.core.value

import `in`.specmatic.core.ExampleDeclarations
import `in`.specmatic.core.pattern.NullPattern
import `in`.specmatic.core.pattern.Pattern

object NullValue : Value, ScalarValue {
    override val httpContentType: String = "text/plain"

    override fun valueErrorSnippet(): String = this.displayableType()

    override fun displayableValue(): String = "null"
    override fun toStringLiteral() = ""
    override fun displayableType(): String = "null"
    override fun exactMatchElseType(): Pattern = NullPattern
    override fun type(): Pattern = NullPattern
    override fun typeDeclarationWithKey(key: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            primitiveTypeDeclarationWithKey(key, types, exampleDeclarations, displayableType(), "(null)")

    override fun listOf(valueList: List<Value>): Value {
        return JSONArrayValue(valueList)
    }

    override fun typeDeclarationWithoutKey(exampleKey: String, types: Map<String, Pattern>, exampleDeclarations: ExampleDeclarations): Pair<TypeDeclaration, ExampleDeclarations> =
            primitiveTypeDeclarationWithoutKey(exampleKey, types, exampleDeclarations, displayableType(), "(null)")

    override val nativeValue: Any?
        get() = null

    override fun toString() = ""
}
