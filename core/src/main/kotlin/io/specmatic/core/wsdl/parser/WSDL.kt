package io.specmatic.core.wsdl.parser

import io.specmatic.core.Feature
import io.specmatic.core.SPECMATIC_GITHUB_ISSUES
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.*
import io.specmatic.core.wsdl.SOAPVersion
import io.specmatic.core.wsdl.parser.message.*
import io.specmatic.license.core.SpecmaticProtocol
import java.io.File

private fun namespaceToPrefixMap(wsdlNode: XMLNode): Map<String, String> {
    val explicitPrefixes = wsdlNode.attributes.filterKeys {
        it.startsWith("xmlns:")
    }.mapValues {
        it.value.toStringLiteral()
    }.map {
        Pair(it.value, it.key.removePrefix("xmlns:"))
    }.toMap()

    val defaultNamespace = wsdlNode.attributes["xmlns"]?.toStringLiteral()
        ?.let { namespace -> mapOf(namespace to "") }
        .orEmpty()

    return defaultNamespace.plus(explicitPrefixes)
}

private fun prefixToNamespaceMap(wsdlNode: XMLNode): Map<String, String> {
    val explicitPrefixes = wsdlNode.attributes.filterKeys {
        it.startsWith("xmlns:")
    }.mapValues {
        it.value.toStringLiteral()
    }.mapKeys {
        it.key.removePrefix("xmlns:")
    }

    val defaultNamespace = wsdlNode.attributes["xmlns"]?.toStringLiteral()
        ?.let { namespace -> mapOf("" to namespace) }
        .orEmpty()

    return defaultNamespace.plus(explicitPrefixes)
}

private fun definitionsFrom(rootDefinitionXML: XMLNode, parentWSDL: File): List<XMLNode> {
    val importedDefinitionXMLs = rootDefinitionXML.findChildrenByName("import").filter {
        it.attributes.containsKey("location")
    }.map { importTag ->
        val wsdlFilename = importTag.getAttributeValue("location")
        val wsdlFile = File(wsdlFilename).let {
            when {
                it.isAbsolute -> it
                else -> parentWSDL.absoluteFile.parentFile.resolve(it)
            }
        }

        val definition = toXMLNode(wsdlFile.readText())
        val subDefinitions = definitionsFrom(definition, wsdlFile)
        listOf(definition).plus(subDefinitions)
    }.flatten()

    return listOf(rootDefinitionXML).plus(importedDefinitionXMLs)
}

fun getSchemaNodesFromDefinition(definition: XMLNode, parentFile: File): List<XMLNode> {
    val typesNode = definition.findFirstChildByName("types") ?: return emptyList()
    val schemasWithinDefinition =  typesNode.findChildrenByName("schema")

    val importedSchemas = schemasWithinDefinition.map { schema ->
        loadSchemaImports(schema, parentFile, definition)
    }.flatten()

    return schemasWithinDefinition.plus(importedSchemas)
}

fun loadSchemaImports(schema: XMLNode, parentFile: File, definition: XMLNode): List<XMLNode> {
    val importNodes = schema.findChildrenByName("import").filter { it.attributes.containsKey("schemaLocation") }

    return importNodes.map { importNode ->
        val filename = importNode.getAttributeValue("schemaLocation")

        val schemaFile = File(filename).let {
            when {
                it.isAbsolute -> it
                else -> parentFile.absoluteFile.parentFile.resolve(it)
            }
        }

        val importedSchema = toXMLNode(schemaFile.readText(), definition.namespaces)
        listOf(importedSchema).plus(loadSchemaImports(importedSchema, schemaFile, definition))
    }.flatten()
}

private fun schemasFrom(definition: XMLNode, parentFile: File): Map<String, XMLNode> {
    val schemas = getSchemaNodesFromDefinition(definition, parentFile)

    return schemas.filter { it.attributes.containsKey("targetNamespace") } .associateBy { schema ->
        schema.getAttributeValue("targetNamespace")
    }
}

fun WSDL(rootDefinition: XMLNode, wsdlPath: String): WSDL {
    val definitions = definitionsFrom(rootDefinition, File(wsdlPath)).associateBy { definition ->
        definition.getAttributeValue("targetNamespace")
    }

    val schemas: Map<String, XMLNode> = listOf(rootDefinition).plus(definitions.values).map { definition ->
        schemasFrom(definition, File(wsdlPath))
    }.fold(emptyMap()) { accumulatedSchemas, schema ->
        accumulatedSchemas.plus(schema)
    }

    val populatedSchemas = addSchemasToNodes(schemas)

    val typesNode = rootDefinition.findFirstChildByName("types") ?: toXMLNode("<types/>")

    val schemaPrefixes: Map<String, String> = schemaPrefixesFrom(schemas)
    val reversedSchemaPrefixes: Map<String, String> = schemaPrefixes.entries.associate { it.value to it.key }
    val rootPrefixes: Map<String, String> = prefixToNamespaceMap(rootDefinition)

    return WSDL(rootDefinition, definitions, populatedSchemas, typesNode, schemaPrefixes.plus(namespaceToPrefixMap(rootDefinition)), reversedSchemaPrefixes.plus(rootPrefixes), prefixToNamespaceMap(rootDefinition))
}

fun schemaPrefixesFrom(schemas: Map<String, XMLNode>): Map<String, String> {
    if (schemas.isEmpty()) {
        return emptyMap()
    }

    val namespaces = schemas.keys.toSet().toList()

    return toURLPrefixMap(namespaces, MappedURLType.INCLUDES_DOMAIN)
}

enum class MappedURLType(val index: Int) {
    INCLUDES_DOMAIN(1),
    PATH_ONLY(0)

}

fun toURLPrefixMap(urls: List<String>, mappedURLType: MappedURLType): Map<String, String> {
    val normalisedURL = urls.map { url ->
        url.removeSuffix("/").removePrefix("http://").removePrefix("https://")
    }

    val minLength = normalisedURL.minOfOrNull {
        it.split("/").size
    }
        ?: throw ContractException("No schema namespaces found")

    val segmentCount = 1.until(minLength + 1).first { length ->
        val segments = normalisedURL.map { url ->
            url.split("/").filterNot { it.isEmpty() }.takeLast(length).joinToString("_")
        }

        segments.toSet().size == urls.size
    }

    val prefixes = normalisedURL.map { url ->
        url.split("/").filterNot { it.isEmpty() }.takeLast(segmentCount).joinToString("_") { it.capitalizeFirstChar() }
    }

    return urls.zip(prefixes).toMap()
}

fun addSchemasToNodes(schemas: Map<String, XMLNode>): Map<String, XMLNode> {
    return schemas.mapValues { (_, schema) ->
        schema.copy(childNodes = schema.childNodes.map { it.addSchema(schema) })
    }
}

private data class DefinitionLookupKey(
    val tagName: String,
    val namespace: String,
    val localName: String
)

private data class SchemaLookupKey(
    val tagName: String,
    val namespace: String,
    val localName: String
)

private data class WsdlIndexes(
    val definitions: Map<DefinitionLookupKey, XMLNode>,
    val schemas: Map<SchemaLookupKey, XMLNode>,
    val namedSchemas: Map<NamedSchemaLookupKey, XMLNode>,
    val substitutionGroups: Map<NamedSchemaLookupKey, List<XMLNode>>
)

private data class NamedSchemaLookupKey(
    val namespace: String,
    val localName: String
)

data class WSDL(private val rootDefinition: XMLNode, val definitions: Map<String, XMLNode>, val schemas: Map<String, XMLNode>, private val typesNode: XMLNode, val namespaceToPrefix: Map<String, String>, val prefixToNamespace: Map<String, String>, val rootPrefixesToNamespace: Map<String, String>) {
    private val indexes = WsdlIndexes(
        definitions = indexDefinitions(definitions),
        schemas = indexSchemas(schemas),
        namedSchemas = indexNamedSchemas(schemas),
        substitutionGroups = indexSubstitutionGroups(schemas)
    )

    fun allNamespaces(): Map<String, String> {
        return prefixToNamespace.plus(rootPrefixesToNamespace)
    }
    fun getServiceName() =
        rootDefinition.findFirstChildByName("service")?.attributes?.get("name")
            ?: throw ContractException("Couldn't find attribute name in node service")

    fun getPortType(): XMLNode {
        val binding = getBinding()
        val portTypeQName = binding.getAttributeValue("type")

        return findInDefinition("portType", binding, portTypeQName)
    }

    private fun getBinding(): XMLNode {
        val servicePort = getServicePort()
        val bindingQName = servicePort.getAttributeValue("binding")

        return findInDefinition("binding", servicePort, bindingQName)
    }

    private fun findInDefinition(
        tagName: String,
        node: XMLNode,
        qname: String
    ): XMLNode {
        val namespace = node.resolveNamespace(qname)
        val localName = qname.localName()
        return findInDefinition(
            tagName,
            namespace,
            localName,
            "Tried to lookup $tagName named $qname, resolved namespace prefix to $namespace, but could not find a definition with that namespace"
        )
    }

    private fun findInDefinition(
        tagName: String,
        namespace: String,
        localName: String,
        definitionMissingMessage: String,
        missingMessage: String? = null
    ): XMLNode {
        val key = DefinitionLookupKey(tagName, namespace, localName)
        definitions[namespace] ?: throw ContractException(definitionMissingMessage)
        return indexes.definitions[key]
            ?: throw ContractException(missingMessage ?: "Couldn't find a node named $tagName with attribute name=\"$localName\"")
    }

    private fun getServicePort() = rootDefinition.getXMLNodeByPath("service.port")

    fun getNamespaces(typeInfo: WSDLTypeInfo): Map<String, String> {
        return typeInfo.getNamespaces(prefixToNamespace)
    }

    fun mapNamespaceToPrefix(targetNamespace: String): String {
        return namespaceToPrefix[targetNamespace]
                ?: throw ContractException("The target namespace $targetNamespace was not found in the WSDL definitions tag.")
    }

    val operations: List<XMLNode>
        get() {
        return getBinding().findChildrenByName("operation")
    }

    fun convertToGherkin(): String {
        val (url, soapParser) = endpoint()

        return soapParser.convertToGherkin(url)
    }

    fun toFeature(path: String, specmaticConfig: SpecmaticConfig = SpecmaticConfig()): Feature {
        return Feature(
            scenarios = toScenarioInfos(specmaticConfig).map { scenarioInfo ->
                Scenario(scenarioInfo.copy(specification = path))
            },
            name = getServiceName().toStringLiteral(),
            path = path,
            specmaticConfig = specmaticConfig,
            protocol = SpecmaticProtocol.SOAP,
        )
    }

    fun toScenarioInfos(specmaticConfig: SpecmaticConfig = SpecmaticConfig()): List<ScenarioInfo> {
        val (url, soapParser) = endpoint()
        return soapParser.toScenarioInfos(url, specmaticConfig)
    }

    private fun endpoint(): Pair<String, SOAPParser> {
        val port = rootDefinition.getXMLNodeOrNull("service.port")
        val endpoint = rootDefinition.getXMLNodeOrNull("service.endpoint")

        return when {
            port != null -> Pair(port.addressLocationOrEmpty(), SOAP11Parser(this))
            endpoint != null -> Pair(endpoint.addressLocationOrEmpty(), SOAP20Parser())
            else -> throw ContractException("Could not find the service endpoint")
        }
    }

    private fun XMLNode.addressLocationOrEmpty(): String {
        return getXMLNodeOrNull("address")?.attributes?.get("location")?.toStringLiteral()?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun findComplexType(
        element: XMLNode,
        attributeName: String
    ): XMLNode {
        val fullTypeName = element.attributes.getValue(attributeName).toStringLiteral()
        return findComplexTypeOrNull(element, attributeName)
            ?: throw ContractException("Couldn't find a node named complexType with attribute name=\"${fullTypeName.localName()}\"")
    }

    fun findComplexTypeOrNull(
        element: XMLNode,
        attributeName: String
    ): XMLNode? {
        val fullTypeName = element.attributes.getValue(attributeName).toStringLiteral()
        return findSchemaNodeOrNull("complexType", namespace(fullTypeName, element), fullTypeName.localName(), element.schema)
    }

    fun findSimpleType(
        element: XMLNode,
        attributeName: String
    ): XMLNode? {
        val fullTypeName = (element.attributes[attributeName] ?: throw ContractException("Node ${element.realName} does not have an attribute named $attributeName")).toStringLiteral()
        return findSchemaNodeOrNull("simpleType", namespace(fullTypeName, element), fullTypeName.localName(), element.schema)
    }

    fun findTypeFromAttribute(
        element: XMLNode,
        attributeName: String
    ): XMLNode {
        val fullTypeName = element.attributes.getValue(attributeName).toStringLiteral()
        return findElement(fullTypeName.localName(), namespace(fullTypeName, element), element.schema)
    }

    private fun namespace(fullTypeName: String, element: XMLNode): String {
        val namespacePrefix = fullTypeName.namespacePrefix()
        return if (namespacePrefix.isBlank())
            ""
        else
            element.namespaces[namespacePrefix]
                ?: throw ContractException("Could not find namespace with prefix $namespacePrefix in xml node $element")
    }

    private fun findElement(typeName: String, namespace: String, localSchema: XMLNode? = null): XMLNode {
        return findNamedSchemaNode(namespace, typeName, localSchema)
    }

    private fun findGlobalElement(typeName: String, namespace: String, localSchema: XMLNode? = null): XMLNode {
        return findSchemaNode("element", namespace, typeName, localSchema)
    }

    fun getSOAPElement(fullyQualifiedName: FullyQualifiedName, localSchema: XMLNode? = null, otherRefAttributes: Map<String, StringValue> = emptyMap()): WSDLElement {
        val schema = findSchema(fullyQualifiedName.namespace, localSchema)

        val node = findGlobalElement(fullyQualifiedName.localName, fullyQualifiedName.namespace, localSchema).addSchema(schema).let {
            it.copy(attributes = it.attributes.plus(otherRefAttributes))
        }

        return if(hasSimpleTypeAttribute(node)) {
            SimpleElement(fullyQualifiedName.qName, node, this)
        } else {
            ReferredType(fullyQualifiedName.qName, node, this)
        }
    }

    fun getSubstitutionGroupMembers(fullyQualifiedName: FullyQualifiedName, localSchema: XMLNode? = null): List<XMLNode> {
        val resolvedNamespace = resolvedSchemaNamespace(fullyQualifiedName.namespace, localSchema)
        return indexes.substitutionGroups[NamedSchemaLookupKey(resolvedNamespace, fullyQualifiedName.localName)].orEmpty()
    }

    fun namedTypeNodes(namespace: String, localSchema: XMLNode? = null): List<XMLNode> {
        val resolvedNamespace = namespaceOrSchemaNamespace(namespace, localSchema) ?: return emptyList()
        if (resolvedNamespace.isBlank()) {
            return emptyList()
        }

        val schema = schemas[resolvedNamespace] ?: return emptyList()
        return schema.childNodes.filterIsInstance<XMLNode>().filter { typeNode ->
            typeNode.name == "complexType" || typeNode.name == "simpleType"
        }
    }

    fun findAttributeGroup(fullyQualifiedName: FullyQualifiedName, localSchema: XMLNode? = null): XMLNode {
        return findSchemaNode("attributeGroup", fullyQualifiedName.namespace, fullyQualifiedName.localName, localSchema)
    }

    fun findAttribute(fullyQualifiedName: FullyQualifiedName, localSchema: XMLNode? = null): XMLNode {
        return findSchemaNode("attribute", fullyQualifiedName.namespace, fullyQualifiedName.localName, localSchema)
    }

    private fun findSchema(namespace: String, schema: XMLNode?): XMLNode {
        val resolvedNamespace = resolvedSchemaNamespace(namespace, schema)
        return schemas[resolvedNamespace]
            ?: throw ContractException("Couldn't find schema with targetNamespace $resolvedNamespace")
    }

    private fun findSchemaNode(
        tagName: String,
        namespace: String,
        localName: String,
        schema: XMLNode?,
        missingMessage: String? = null
    ): XMLNode {
        findSchema(namespace, schema)
        return findSchemaNodeOrNull(tagName, namespace, localName, schema)
            ?: throw ContractException(missingMessage ?: "Couldn't find a node named $tagName with attribute name=\"$localName\"")
    }

    private fun findSchemaNodeOrNull(tagName: String, namespace: String, localName: String, schema: XMLNode?): XMLNode? {
        val resolvedNamespace = resolvedSchemaNamespace(namespace, schema)
        val key = SchemaLookupKey(tagName, resolvedNamespace, localName)
        return indexes.schemas[key]
    }

    private fun findNamedSchemaNode(namespace: String, localName: String, schema: XMLNode?): XMLNode {
        findSchema(namespace, schema)
        val resolvedNamespace = resolvedSchemaNamespace(namespace, schema)
        return indexes.namedSchemas[NamedSchemaLookupKey(resolvedNamespace, localName)]
            ?: throw ContractException("Couldn't find a node with attribute name=$localName")
    }

    private fun resolvedSchemaNamespace(namespace: String, schema: XMLNode?): String {
        val resolvedNamespace = namespaceOrSchemaNamespace(namespace, schema)
        if (resolvedNamespace.isNullOrBlank())
            throw ContractException("Cannot look for an empty schema namespace. Please report this to the Specmatic Builders at $SPECMATIC_GITHUB_ISSUES")

        return resolvedNamespace
    }

    fun getComplexTypeNode(element: XMLNode): ComplexType {
        val node = when {
            element.name == "complexType" -> element
            element.attributes.containsKey("type") -> findComplexType(element, "type")
            else -> element.childNodes.filterIsInstance<XMLNode>().filterNot { it.name == "annotation" }.first()
        }.also {
            if (it.name != "complexType")
                throw ContractException("Unexpected type node found\nSource: $element\nType: $it")
        }

        return ComplexType(node, this)
    }

    fun getSimpleTypeXMLNode(element: XMLNode): XMLNode? {
        return when {
            element.attributes.containsKey("type") -> findSimpleType(element, "type")
            else -> element.childNodes.filterIsInstance<XMLNode>().filterNot { it.name == "annotation" }.first()
        }
    }

    fun findMessageNode(fullyQualifiedName: FullyQualifiedName): XMLNode {
        return findInDefinition(
            "message",
            fullyQualifiedName.namespace,
            fullyQualifiedName.localName,
            "Could not find message named ${fullyQualifiedName.qName}. ${fullyQualifiedName.prefix} mapped to ${fullyQualifiedName.namespace}, but could not find a definition with this targetNamespace.",
            "Message node ${fullyQualifiedName.qName} not found"
        )
    }

    fun getWSDLElementType(parentTypeName: String, node: XMLNode): ChildElementType {
        return when {
            node.attributes.containsKey("ref") -> {
                ElementReference(node, this)
            }
            node.attributes.containsKey("type") -> {
                TypeReference(node, this)
            }
            else -> {
                InlineType(parentTypeName, node, this)
            }
        }
    }

    fun getQualification(element: XMLNode, wsdlTypeReference: String): NamespaceQualification {
        val namespace = element.resolveNamespace(wsdlTypeReference)

        val schema: XMLNode = if(namespace.isBlank())
            element.schema ?:
                throw ContractException("No type reference to indicate the schema, and the element node ${element.oneLineDescription} did not have a schema attached")
        else
            this.findSchema(namespace, element.schema)

        val schemaElementFormDefault = schema.attributes["elementFormDefault"]?.toStringLiteral()
        val elementForm = element.attributes["form"]?.toStringLiteral()

        return when(elementForm ?: schemaElementFormDefault) {
            "qualified" -> QualifiedNamespace(element, schema, wsdlTypeReference, this)
            else -> UnqualifiedNamespace(element.getAttributeValue("name"))
        }
    }

    fun getSchemaNamespacePrefix(namespace: String): String {
        return namespaceToPrefix[namespace] ?: throw ContractException("Tried to lookup a prefix for the namespace $namespace but could not find one")
    }

    fun getSOAPVersion(): SOAPVersion {
        val paranoidDefaultSoapVersion = SOAPVersion.SOAP12

        val wsdlBindingNode = getBinding()
        val bindingNode = wsdlBindingNode.findChildrenByName("binding").firstOrNull() ?: return paranoidDefaultSoapVersion
        val namespace = bindingNode.namespaces[bindingNode.namespacePrefix]

        return when (namespace) {
            "http://schemas.xmlsoap.org/wsdl/soap/" -> SOAPVersion.SOAP11
            "http://schemas.xmlsoap.org/wsdl/soap12/" -> SOAPVersion.SOAP12
            else -> paranoidDefaultSoapVersion
        }
    }
}

fun specmaticTypeName(typeName: String): String = typeName.replace(':', '_')

fun namespaceOrSchemaNamespace(namespace: String, schema: XMLNode?) =
    namespace.ifBlank {
        schema?.attributes?.get("targetNamespace")?.toStringLiteral()
            ?: schema?.attributes?.get("xmlns")?.toStringLiteral()
    }

private fun indexDefinitions(definitions: Map<String, XMLNode>): Map<DefinitionLookupKey, XMLNode> {
    val definitionTags = setOf("message", "binding", "portType")
    return definitions.flatMap { (namespace, definition) ->
        definition.childNodes.filterIsInstance<XMLNode>()
            .filter { it.name in definitionTags }
            .mapNotNull { node ->
                node.attributes["name"]?.toStringLiteral()?.let { localName ->
                    DefinitionLookupKey(node.name, namespace, localName) to node
                }
            }
    }.firstByKey()
}

private fun indexSchemas(schemas: Map<String, XMLNode>): Map<SchemaLookupKey, XMLNode> {
    val schemaTags = setOf("element", "complexType", "simpleType", "attribute", "attributeGroup")
    return schemas.flatMap { (namespace, schema) ->
        schema.childNodes.filterIsInstance<XMLNode>()
            .filter { it.name in schemaTags }
            .mapNotNull { node ->
                node.attributes["name"]?.toStringLiteral()?.let { localName ->
                    SchemaLookupKey(node.name, namespace, localName) to node
                }
            }
    }.firstByKey()
}

private fun indexNamedSchemas(schemas: Map<String, XMLNode>): Map<NamedSchemaLookupKey, XMLNode> {
    return schemas.flatMap { (namespace, schema) ->
        schema.childNodes.filterIsInstance<XMLNode>()
            .mapNotNull { node ->
                node.attributes["name"]?.toStringLiteral()?.let { localName ->
                    NamedSchemaLookupKey(namespace, localName) to node
                }
            }
    }.firstByKey()
}

private fun indexSubstitutionGroups(schemas: Map<String, XMLNode>): Map<NamedSchemaLookupKey, List<XMLNode>> {
    return schemas.values
        .flatMap { schema -> schema.childNodes.filterIsInstance<XMLNode>() }
        .filter { node -> node.name == "element" && node.attributes.containsKey("substitutionGroup") }
        .groupBy { node ->
            val head = node.fullyQualifiedNameFromAttribute("substitutionGroup")
            NamedSchemaLookupKey(head.namespace, head.localName)
        }
}

private fun <K, V> List<Pair<K, V>>.firstByKey(): Map<K, V> =
    LinkedHashMap<K, V>().also { indexedValues ->
        forEach { entry ->
            indexedValues.putIfAbsent(entry.first, entry.second)
        }
    }
