package io.specmatic.core.pattern

import io.specmatic.core.Result.Failure
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/** Validate namespaces once at the WSDL envelope boundary, including wildcard-skipped descendants. */
internal fun XMLNode.namespaceValidationFailure(): Failure? {
    val inheritedDeclarations = namespaces.mapKeys { (prefix, _) ->
        if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
    }.mapValues { StringValue(it.value) }
    val xml = copy(attributes = inheritedDeclarations + attributes).namespaceValidationXML()
    val builder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder()
    builder.setErrorHandler(object : DefaultHandler() {
        override fun error(exception: SAXParseException) { throw exception }
        override fun fatalError(exception: SAXParseException) { throw exception }
    })

    return try {
        builder.parse(InputSource(StringReader(xml)))
        null
    } catch (exception: SAXException) {
        Failure("Invalid XML namespace declarations or names in $realName: ${exception.message}")
    }
}

/** Escape decoded values without repairing the namespace declarations being validated. */
private fun XMLNode.namespaceValidationXML(): String {
    val output = StringWriter()
    val writer = XMLOutputFactory.newFactory().apply {
        setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, false)
    }.createXMLStreamWriter(output)
    writer.writeNamespaceValidationElement(this)
    writer.close()
    return output.toString()
}

private fun XMLStreamWriter.writeNamespaceValidationElement(node: XMLNode) {
    writeStartElement(node.realName)
    node.attributes.forEach { (name, value) -> writeAttribute(name, value.string) }
    node.childNodes.forEach { child ->
        when (child) {
            is XMLNode -> writeNamespaceValidationElement(child)
            else -> writeCharacters(child.toStringLiteral())
        }
    }
    writeEndElement()
}
