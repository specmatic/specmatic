package io.specmatic.core.wsdl

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.fold.*
import io.specmatic.core.value.StringValue

/** Apply declaration namespace requirements at the WSDL boundary, including named types and groups. */
internal fun Pattern.withWSDLNamespaceRequirements(soapVersion: SOAPVersion): Pattern = accept(WSDLNamespaceRequirements(soapVersion))

private class WSDLNamespaceRequirements(private val soapVersion: SOAPVersion) : PatternVisitor<Unit, Pattern> {
    override val rootContext = Unit

    override fun opaque(case: OpaquePatternCase<out Pattern, Unit>): Pattern = case.pattern

    override fun xmlElement(case: XmlElementPatternCase<out Pattern, Unit>): Pattern {
        val rebuilt = case.rebuild(case.attributes, case.projectChildren(this)) as XMLPattern
        val data = rebuilt.pattern
        val namespace = if (data.isSOAP && data.namespaceUri == SOAPVersion.SOAP11.envelopeNamespace)
            soapVersion.envelopeNamespace else data.namespaceUri
        val attributes = if (data.isSOAP) data.attributes.mapValues { (name, value) ->
            if ((name == "xmlns" || name.startsWith("xmlns:")) &&
                (value as? ExactValuePattern)?.pattern?.toStringLiteral() == SOAPVersion.SOAP11.envelopeNamespace)
                ExactValuePattern(StringValue(soapVersion.envelopeNamespace)) else value
        } else data.attributes
        return rebuilt.copy(pattern = data.copy(
            namespaceUri = namespace,
            requiredNamespaceUri = namespace.orEmpty(),
            attributes = attributes,
            namespaceBindings = if (data.isSOAP) data.namespaceBindings.mapValues { (_, value) ->
                if (value == SOAPVersion.SOAP11.envelopeNamespace) soapVersion.envelopeNamespace else value
            } else data.namespaceBindings
        ))
    }

    override fun indexedList(case: IndexedListPatternCase<out Pattern, Unit>): Pattern =
        case.rebuild(case.projectItems(this))

    override fun repeatedList(case: RepeatedListPatternCase<out Pattern, Unit>): Pattern =
        case.rebuild(case.projectItem(this, 0))

    override fun oneOf(case: OneOfPatternCase<out Pattern, Unit>): Pattern =
        case.rebuild(case.projectAlternatives(this))

    override fun anyOf(case: AnyOfPatternCase<out Pattern, Unit>): Pattern =
        case.rebuild(case.projectAlternatives(this))

    override fun alternativeSequences(case: AlternativeSequencesPatternCase<out Pattern, Unit>): Pattern =
        case.rebuild(case.projectAlternatives(this))

    override fun delegated(case: DelegatedPatternCase<out Pattern, out Pattern, Unit>): Pattern =
        case.rewrapProjected(case.projectDelegate(this)) ?: case.pattern
}
