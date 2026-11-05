package io.specmatic.core.pattern

import io.specmatic.core.value.localName

/** Attribute map keys retain optionality while identifying the XML expanded name. */
internal fun expandedAttributeName(name: String, namespaceUri: String?): String {
    val localName = name.localName()
    return if (namespaceUri.isNullOrEmpty()) localName else "{$namespaceUri}$localName"
}
