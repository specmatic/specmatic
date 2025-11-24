@file:Suppress("ktlint:standard:no-wildcard-imports")

package io.specmatic.core.value

import org.w3c.dom.Document
import org.w3c.dom.Node

data class CDATAValue(
    val stringValue: StringValue,
) : ScalarValue,
    XMLValue by stringValue {
    constructor(string: String) : this(StringValue(string))

    override fun build(document: Document): Node = document.createCDATASection(stringValue.string)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is StringValue) return other.string == this.stringValue.string
        if (other !is CDATAValue) return false

        return stringValue == other.stringValue
    }

    fun nodeToString(
        indent: String,
        lineSeparator: String,
    ): String {
        return "$indent<![CDATA[${stringValue.string.replace("]]>", "]]&gt;")}]]>$lineSeparator"
    }

    override val nativeValue: String
        get() = stringValue.string

    override fun alterValue(): ScalarValue = stringValue.alterValue()
}
