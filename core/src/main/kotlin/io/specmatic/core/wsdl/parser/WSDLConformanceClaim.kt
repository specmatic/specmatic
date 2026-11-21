package io.specmatic.core.wsdl.parser

import io.specmatic.core.value.XMLNode

internal const val BASIC_PROFILE_11 = "http://ws-i.org/profiles/basic/1.1"
private const val CLAIM_NAMESPACE = "http://ws-i.org/schemas/conformanceClaim/"
private const val WSDL_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/"

internal data class WSDLServiceInstance(val namespace: String, val service: String, val port: String)
internal enum class WSDLClaimScope { ServiceInstance, Description, Unsupported }
internal data class WSDLConformanceClaim(
    val profile: String?,
    val scope: WSDLClaimScope,
    val instance: WSDLServiceInstance?,
    val source: String,
    val location: String,
)

internal fun XMLNode.isWSDL(name: String): Boolean =
    this.name == name && elementNamespaceUriOrNull() == WSDL_NAMESPACE

internal fun wsdlConformanceClaims(definition: XMLNode, source: String): List<WSDLConformanceClaim> {
    fun visit(node: XMLNode, ancestors: List<XMLNode>): List<WSDLConformanceClaim> {
        val claim = if (node.name == "Claim" && node.elementNamespaceUriOrNull() == CLAIM_NAMESPACE) {
            val parent = ancestors.lastOrNull()
            val attachment = ancestors.getOrNull(ancestors.lastIndex - 1)
            val container = ancestors.getOrNull(ancestors.lastIndex - 2)
            val documented = parent?.isWSDL("documentation") == true
            val instance = if (documented && attachment?.isWSDL("port") == true &&
                container?.isWSDL("service") == true && ancestors.getOrNull(ancestors.lastIndex - 3)?.isWSDL("definitions") == true)
                WSDLServiceInstance(definition.getAttributeValue("targetNamespace"), container.getAttributeValue("name"), attachment.getAttributeValue("name"))
            else null
            val description = documented && attachment != null && (
                (attachment.isWSDL("binding") || attachment.isWSDL("portType") || attachment.isWSDL("message")) && container?.isWSDL("definitions") == true ||
                    attachment.isWSDL("operation") && container?.isWSDL("portType") == true
                )
            listOf(WSDLConformanceClaim(
                node.attributes["conformsTo"]?.toStringLiteral(),
                when { instance != null -> WSDLClaimScope.ServiceInstance; description -> WSDLClaimScope.Description; else -> WSDLClaimScope.Unsupported },
                instance, source,
                ancestors.joinToString("/") { it.name + (it.attributes["name"]?.toStringLiteral()?.let { name -> "[@name='$name']" } ?: "") },
            ))
        } else emptyList()
        return claim + node.childNodes.filterIsInstance<XMLNode>().flatMap { visit(it, ancestors + node) }
    }
    return visit(definition, emptyList())
}
