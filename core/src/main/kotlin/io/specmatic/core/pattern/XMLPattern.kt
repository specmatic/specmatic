package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.mapZip
import io.specmatic.core.utilities.parseXML
import io.specmatic.core.value.*
import io.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME

const val SPECMATIC_XML_ATTRIBUTE_PREFIX = "${APPLICATION_NAME_LOWER_CASE}_"
const val TYPE_ATTRIBUTE_NAME = "specmatic_type"
const val SOAP_BODY = "body"
const val SOAP_FAULT = "fault"
private const val XML_RANDOM_NUMBER_CEILING = 3
private const val SOAP_ENVELOPE = "Envelope"
private const val SOAP_HEADER = "Header"
private const val SOAP_ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"

private sealed class WSDLTypeSelection {
    data class Use(val pattern: Pattern) : WSDLTypeSelection()
    data class Unknown(val assertedType: WSDLTypeName, val declaredType: WSDLTypeName) : WSDLTypeSelection()
    data class Invalid(val assertedType: WSDLTypeName, val declaredType: WSDLTypeName) : WSDLTypeSelection()
    data class Abstract(val assertedType: WSDLTypeName, val declaredType: WSDLTypeName) : WSDLTypeSelection()
}

private data class XMLHeaderName(
    val namespaceUri: String?,
    val localName: String
)

fun toTypeData(node: XMLNode, isSOAP: Boolean? = null, isSOAPHeader: Boolean = false): XMLTypeData {
    val attributes = attributeTypeMap(node)
    val isSOAP = isSOAP ?: (attributes["xmlns"] == ExactValuePattern(StringValue("http://schemas.xmlsoap.org/wsdl/")))
    return XMLTypeData(
        name = node.name,
        realName = node.realName,
        attributes = attributes,
        nodes = nodeTypes(node, isSOAP),
        isSOAP = isSOAP,
        namespaceUri = node.elementNamespaceUriOrNull(),
        isSOAPHeader = isSOAPHeader,
        attributeNamespaceUris = attributeNamespaceUriMap(node)
    )
}

private fun nodeTypes(node: XMLNode, isSOAP: Boolean): List<Pattern> {
    return node.childNodes.map { value ->
        when (value) {
            is XMLNode -> XMLPattern(value, isSOAP = isSOAP, isSOAPHeader = isSOAPHeader(node, value, isSOAP))
            is StringValue, is CDATAValue, is BinaryValue -> value.exactMatchElseType()
        }
    }
}

private fun isSOAPHeader(parentNode: XMLNode, childNode: XMLNode, isSOAP: Boolean): Boolean {
    return isSOAP &&
            parentNode.name.equals(SOAP_ENVELOPE, ignoreCase = true) &&
            childNode.name.equals(SOAP_HEADER, ignoreCase = true)
}

private fun attributeTypeMap(node: XMLNode): Map<String, Pattern> {
    return node.attributes.mapValues { (key, value) ->
        when {
            value.isPatternToken() -> DeferredPattern(value.trimmed().toStringLiteral(), key)
            else -> ExactValuePattern(value)
        }
    }
}

private fun attributeNamespaceUriMap(node: XMLNode): Map<String, String?> {
    return node.attributes.keys
        .filterNot(::isNamespaceDeclarationAttribute)
        .associate { attributeName ->
            withoutOptionality(attributeName) to node.attributeNamespaceUri(attributeName)
        }
}

private fun isNamespaceDeclarationAttribute(attributeName: String): Boolean =
    attributeName == "xmlns" || attributeName.startsWith("xmlns:")

data class XMLPattern(
    override val pattern: XMLTypeData = XMLTypeData(realName = ""),
    override val typeAlias: String? = null,
    val schemaPointer: String? = null,
    val attributePointers: Map<String, String> = emptyMap()
) : Pattern, SequenceType, XMLChildGenerationPattern {
    constructor(
        node: XMLNode,
        typeAlias: String? = null,
        isSOAP: Boolean? = null,
        isSOAPHeader: Boolean = false
    ) : this(toTypeData(node, isSOAP, isSOAPHeader), typeAlias)

    constructor(
        xmlString: String,
        typeAlias: String? = null,
        isSOAP: Boolean? = null,
        isSOAPHeader: Boolean = false
    ) : this(toXMLNode(parseXML(xmlString)), typeAlias, isSOAP, isSOAPHeader)

    fun toPrettyString(): String {
        return pattern.toGherkinishNode().toPrettyStringValue()
    }

    fun plusNamespaceUri(namespaceUri: String?): XMLPattern {
        return copy(pattern = pattern.copy(namespaceUri = namespaceUri?.takeIf { it.isNotBlank() }))
    }

    fun withCurrentWSDLTypeOnly(): XMLPattern =
        copy(pattern = pattern.copy(wsdlTypeSelectionMode = WSDLTypeSelectionMode.CurrentTypeOnly))

    override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> {
        val xmlValues = sampleData.filterIsInstance<XMLValue>()
        if (xmlValues.size != sampleData.size)
            return ConsumeResult(Failure("XMLPattern can only match XML values"))

        if (canSkipRecursiveXMLReference(xmlValues, resolver)) {
            return ConsumeResult(Success(), sampleData)
        }

        return when {
            pattern.isOptionalNode() -> matchesOptionalNode(xmlValues, resolver)
            pattern.isMultipleNode() -> matchesMultipleNodes(xmlValues, resolver)
            else -> matchesRequiredNode(xmlValues, sampleData, resolver)
        }
    }

    private fun canSkipRecursiveXMLReference(xmlValues: List<XMLValue>, resolver: Resolver): Boolean {
        if (!hasXMLReferenceCycle(resolver)) {
            return false
        }

        val nextXMLValue = xmlValues.firstOrNull() ?: return true
        return nextXMLValue !is XMLNode || !prefixLessComparison(nextXMLValue)
    }

    private fun matchesRequiredNode(
        xmlValues: List<XMLValue>,
        sampleData: List<Value>,
        resolver: Resolver
    ): ConsumeResult<Value, Value> = if (xmlValues.isEmpty())
        ConsumeResult(Failure("Didn't get enough values").breadCrumb(this.pattern.name, resolver.locate(schemaPointer)), sampleData)
    else
        ConsumeResult(matches(xmlValues.first(), resolver), xmlValues.drop(1))

    private fun matchesMultipleNodes(
        xmlValues: List<XMLValue>,
        resolver: Resolver
    ): ConsumeResult<Value, Value> {
        val remainder = xmlValues.dropWhile {
            matches(it, resolver) is Success
        }

        return if (remainder.isNotEmpty() && remainder.first().let { it is XMLNode && prefixLessComparison(it) }) {
            ConsumeResult(matches(remainder.first(), resolver), remainder)
        } else if (remainder.isNotEmpty()) {
            val provisionalError = ProvisionalError<Value>(
                matches(remainder.first(), resolver) as Failure,
                this,
                remainder.first()
            )
            ConsumeResult(Success(), remainder, provisionalError)
        } else {
            ConsumeResult(Success(), remainder)
        }
    }

    private fun matchesOptionalNode(
        xmlValues: List<XMLValue>,
        resolver: Resolver
    ): ConsumeResult<Value, Value> = if (xmlValues.isEmpty())
        ConsumeResult(Success())
    else {
        val xmlValue = xmlValues.first()
        when (val result = matches(xmlValue, resolver)) {
            is Success -> ConsumeResult(Success(), xmlValues.drop(1))
            is Failure -> when {
                xmlValue is XMLNode && prefixLessComparison(xmlValue) -> ConsumeResult(result, xmlValues)
                else -> ConsumeResult(Success(), xmlValues, ProvisionalError(result, this, xmlValue))
            }
        }
    }

    private fun prefixLessComparison(xmlValue: XMLNode) =
        xmlValue.name.substringAfter(":") == this.pattern.name

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData !is XMLNode) return dataTypeMismatchResult(
            this,
            sampleData,
            resolver.mismatchMessages
        ).breadCrumb(pattern.name, resolver.locate(schemaPointer))

        val cyclePreventionPattern = cyclePreventionPattern()
        if (cyclePreventionPattern != this && !resolver.hasCycle(cyclePreventionPattern)) {
            return resolver.withCyclePrevention(cyclePreventionPattern) { cyclePreventedResolver ->
                matchesXMLNode(sampleData, cyclePreventedResolver)
            }
        }

        return matchesXMLNode(sampleData, resolver)
    }

    private fun matchesXMLNode(sampleData: XMLNode, resolver: Resolver): Result {
        val matchingType = dereferenceType(resolver)
        val sampleDataWithoutEmptyHeader = dropEmptySOAPHeader(sampleData)

        if (matchingType.wsdlTypeSelectionMode() == WSDLTypeSelectionMode.Polymorphic) {
            selectWSDLType(sampleData, matchingType, resolver)?.let { selectedType ->
                val matchResult = when (selectedType) {
                    is WSDLTypeSelection.Unknown,
                    is WSDLTypeSelection.Invalid,
                    is WSDLTypeSelection.Abstract -> failureForWSDLTypeSelection(selectedType)

                    is WSDLTypeSelection.Use -> matchWithSelectedType(selectedType.pattern, sampleDataWithoutEmptyHeader, resolver)
                }

                return matchResult.breadCrumb(pattern.name, resolver.locate(schemaPointer))
            }

            if (matchingType.isAbstractWSDLType()) {
                val declaredType = matchingType.wsdlTypeName()
                if (declaredType != null) {
                    return missingXSITypeForAbstractTypeResult(declaredType)
                        .breadCrumb(pattern.name, resolver.locate(schemaPointer))
                }
            }
        }

        if (pattern.isNillable()) {
            if (sampleData.childNodes.isEmpty())
                return Success()
        }

        return when (matchingType) {
            is XMLPattern -> {
                matchName(sampleDataWithoutEmptyHeader, resolver).ifSuccess {
                    matchingType.matchNamespaces(sampleDataWithoutEmptyHeader)
                }.ifSuccess {
                    matchingType.matchAttributes(sampleDataWithoutEmptyHeader, resolver)
                }.ifSuccess {
                    matchingType.matchNodes(sampleDataWithoutEmptyHeader, resolver)
                }
            }

            is AnyPattern -> matchingType.matches(sampleDataWithoutEmptyHeader, resolver)
            else -> {
                if (sampleDataWithoutEmptyHeader.childNodes.size != 1) {
                    return valueMismatchResult("single node", sampleDataWithoutEmptyHeader, resolver.mismatchMessages)
                }

                val valueToMatch =
                    matchingType.parse(sampleDataWithoutEmptyHeader.firstChild().toStringLiteral(), resolver)
                matchingType.matches(valueToMatch, resolver)
            }
        }.breadCrumb(pattern.name, resolver.locate(schemaPointer))
    }

    private fun matchWithSelectedType(matchingType: Pattern, sampleData: XMLNode, resolver: Resolver): Result {
        return when (matchingType) {
            is XMLPattern -> {
                matchingType.matchName(sampleData, resolver).ifSuccess {
                    matchingType.matchNamespaces(sampleData)
                }.ifSuccess {
                    matchingType.matchAttributes(sampleData, resolver)
                }.ifSuccess {
                    matchingType.matchNodes(sampleData, resolver)
                }
            }

            else -> matchWithType(matchingType, sampleData, resolver)
        }
    }

    private fun matchWithType(matchingType: Pattern, sampleData: XMLNode, resolver: Resolver): Result {
        return when (matchingType) {
            is XMLPattern -> matchWithSelectedType(matchingType.withElementNameFrom(pattern), sampleData, resolver)

            is AnyPattern -> matchingType.matches(sampleData, resolver)
            else -> {
                if (sampleData.childNodes.size != 1) {
                    return valueMismatchResult("single node", sampleData, resolver.mismatchMessages)
                }

                val valueToMatch = matchingType.parse(sampleData.firstChild().toStringLiteral(), resolver)
                matchingType.matches(valueToMatch, resolver)
            }
        }
    }

    private fun invalidXSITypeResult(assertedType: WSDLTypeName, declaredType: WSDLTypeName): Failure {
        return Failure(
            "Invalid type ${assertedType.displayNameForError()}; " +
                "it is not compatible with base type ${declaredType.displayNameForError()}."
        )
    }

    private fun unknownXSITypeResult(assertedType: WSDLTypeName, declaredType: WSDLTypeName): Failure {
        return Failure(
            "Unknown type ${assertedType.displayNameForError()}; " +
                "no matching type was found in the WSDL/schema set for base type ${declaredType.displayNameForError()}."
        )
    }

    private fun abstractXSITypeResult(assertedType: WSDLTypeName, declaredType: WSDLTypeName): Failure {
        return Failure(
            "Invalid type ${assertedType.displayNameForError()}; " +
                "it is abstract and cannot be used as a concrete WSDL type for the ${declaredType.displayNameForError()} element."
        )
    }

    private fun missingXSITypeForAbstractTypeResult(declaredType: WSDLTypeName): Failure {
        return Failure(
            "Missing type for abstract WSDL type ${declaredType.displayNameForError()}; a concrete subtype is required."
        )
    }

    private fun failureForWSDLTypeSelection(selectedType: WSDLTypeSelection): Failure {
        return when (selectedType) {
            is WSDLTypeSelection.Unknown -> unknownXSITypeResult(selectedType.assertedType, selectedType.declaredType)
            is WSDLTypeSelection.Invalid -> invalidXSITypeResult(selectedType.assertedType, selectedType.declaredType)
            is WSDLTypeSelection.Abstract -> abstractXSITypeResult(selectedType.assertedType, selectedType.declaredType)
            is WSDLTypeSelection.Use -> error("Cannot convert a valid WSDL type selection to a failure")
        }
    }

    private fun selectWSDLType(sampleData: XMLNode, declaredType: Pattern, resolver: Resolver): WSDLTypeSelection? {
        sampleData.xsiTypeName()?.let { assertedType ->
            return selectWSDLType(assertedType, declaredType, resolver)
        }

        return selectWSDLTypeByElementName(sampleData, declaredType, resolver)
    }

    private fun selectWSDLType(assertedType: WSDLTypeName?, declaredType: Pattern, resolver: Resolver): WSDLTypeSelection? {
        assertedType ?: return null

        if (assertedType.namespace == XML_SCHEMA_NAMESPACE) {
            return null
        }

        val declaredWSDLType = declaredType.wsdlTypeName() ?: return null
        val payloadAssertedTypePattern = resolver.findPatternForWSDLType(assertedType)
        val payloadAssertedTypePatternCompatibleWithDeclaredType = declaredType.findPatternForWSDLType(assertedType, resolver)
            ?: payloadAssertedTypePattern
                ?.takeIf { it.wsdlTypeName() == declaredWSDLType || it.isDerivedFrom(declaredWSDLType, resolver) }
        val assertedTypeIsKnown = declaredType.knowsWSDLType(assertedType) ||
                payloadAssertedTypePattern != null

        return when {
            assertedType == declaredWSDLType && declaredType.isAbstractWSDLType() ->
                WSDLTypeSelection.Abstract(assertedType, declaredWSDLType)

            payloadAssertedTypePatternCompatibleWithDeclaredType == null && !assertedTypeIsKnown ->
                WSDLTypeSelection.Unknown(assertedType, declaredWSDLType)

            payloadAssertedTypePatternCompatibleWithDeclaredType == null ->
                WSDLTypeSelection.Invalid(assertedType, declaredWSDLType)

            payloadAssertedTypePatternCompatibleWithDeclaredType.isAbstractWSDLType() ->
                WSDLTypeSelection.Abstract(assertedType, declaredWSDLType)

            else ->
                WSDLTypeSelection.Use(mergeReferredPattern(payloadAssertedTypePatternCompatibleWithDeclaredType))
        }
    }

    private fun selectWSDLTypeByElementName(sampleData: XMLNode, declaredType: Pattern, resolver: Resolver): WSDLTypeSelection? {
        val declaredWSDLType = declaredType.wsdlTypeName() ?: return null
        val payloadElementName = sampleData.wsdlElementName()
        val declaredXMLPattern = declaredType as? XMLPattern ?: return null
        val selectedPattern = declaredType.concreteDerivedWSDLPatterns(resolver)
            .firstOrNull { (typeName, _) -> typeName == payloadElementName }
            ?.second
            ?: return null

        val mergedPattern = declaredXMLPattern.mergeWSDLDerivedPattern(selectedPattern)
            ?.withElementNameFrom(sampleData)
            ?: return WSDLTypeSelection.Invalid(payloadElementName, declaredWSDLType)

        return when {
            mergedPattern.pattern.wsdlTypeIsAbstract -> WSDLTypeSelection.Abstract(payloadElementName, declaredWSDLType)
            else -> WSDLTypeSelection.Use(mergedPattern)
        }
    }

    private fun dropEmptySOAPHeader(sampleData: XMLNode): XMLNode {
        if (!pattern.isSOAP) {
            return sampleData
        }

        if (pattern.nodes.any { it is XMLPattern && it.pattern.name == "Header" }) {
            return sampleData
        }

        val soapHeader =
            sampleData
                .childNodes
                .asSequence()
                .filterIsInstance<XMLNode>()
                .firstOrNull { mightBeSOAPHeader ->
                    mightBeSOAPHeader.name == "Header" && mightBeSOAPHeader.childNodes.isEmpty()
                }

        val sampleDataWithoutEmptyHeader = soapHeader?.let { sampleData.remove(it) } ?: sampleData
        return sampleDataWithoutEmptyHeader
    }

    private fun matchNodes(
        sampleData: XMLNode,
        resolver: Resolver,
    ): Result {
        if (sampleData.name.lowercase() == SOAP_BODY && sampleData.firstNode() is XMLNode && sampleData.firstNode()?.name?.lowercase() == SOAP_FAULT)
            return Success()

        val sampleChildNodes = childNodesForMatching(sampleData)

        val results = pattern.nodes.scanIndexed(
            ConsumeResult<XMLValue, Value>(
                Success(),
                sampleChildNodes,
            ),
        ) { index, consumeResult, type ->
            when (val resolvedType = resolvedHop(type, resolver)) {
                is ListPattern -> ConsumeResult(
                    resolvedType.matches(
                        this.listOf(
                            consumeResult.remainder,
                            resolver
                        ), resolver
                    ),
                    emptyList()
                )

                else -> {
                    try {
                        if (sampleChildNodes.size == 1 && consumeResult.remainder.size == 1 && sampleChildNodes.first() is StringValue) {
                            val childValue = when (val childNode = sampleChildNodes[index]) {
                                is StringValue -> when {
                                    childNode.isPatternToken() -> childNode.trimmed()
                                    else -> {
                                        attempt(
                                            "Couldn't read value ${childNode.string} as type ${resolvedType.pattern}",
                                            breadCrumb = breadCrumbIfXMLTag(resolvedType)
                                        ) { resolvedType.parse(childNode.string, resolver) }
                                    }
                                }

                                else -> childNode
                            }

                            val factKey = if (childValue is XMLNode) childValue.name else null
                            ConsumeResult(resolver.matchesPattern(factKey, resolvedType, childValue), emptyList())
                        } else if (expectingEmpty(sampleData, resolvedType, resolver)) {
                            ConsumeResult(Success())
                        } else {
                            resolvedType.matches(consumeResult.remainder, resolver).cast("xml")
                        }
                    } catch (e: ContractException) {
                        ConsumeResult(e.failure(), consumeResult.remainder)
                    }
                }
            }
        }

        return failureFrom(results) ?: Success()
    }

    private fun childNodesForMatching(sampleData: XMLNode): List<XMLValue> {
        if (!pattern.isSOAPHeader || !sampleData.name.equals(SOAP_HEADER, ignoreCase = true)) {
            return sampleData.childNodes
        }

        val headerOrder = soapHeaderOrder() ?: return sampleData.childNodes

        if (sampleData.childNodes.any { it !is XMLNode }) {
            return sampleData.childNodes
        }

        return sampleData.childNodes.withIndex().sortedWith(
            compareBy<IndexedValue<XMLValue>> { indexedValue ->
                val childNode = indexedValue.value as XMLNode
                headerOrder[childNode.headerName()] ?: Int.MAX_VALUE
            }.thenBy { indexedValue ->
                indexedValue.index
            }
        ).map { it.value }
    }

    private fun soapHeaderOrder(): Map<XMLHeaderName, Int>? {
        val headerPatterns = pattern.nodes.filterIsInstance<XMLPattern>()

        if (headerPatterns.size != pattern.nodes.size) {
            return null
        }

        return headerPatterns.map { it.headerName() }.distinct().withIndex().associate { indexedValue ->
            indexedValue.value to indexedValue.index
        }
    }

    private fun headerName(): XMLHeaderName = XMLHeaderName(pattern.namespaceUri, pattern.name)

    private fun XMLNode.headerName(): XMLHeaderName = XMLHeaderName(elementNamespaceUriOrNull(), name)

    private fun breadCrumbIfXMLTag(resolvedType: Pattern): String {
        return when (resolvedType) {
            is XMLPattern -> resolvedType.pattern.name
            else -> ""
        }
    }

    private fun failureFrom(results: List<ConsumeResult<XMLValue, Value>>): Result? {
        val nodeStructureMismatchError = results.find {
            it.result is Failure
        }?.result

        val nothingEvenCameCloseError = lazy {
            when {
                results.isNotEmpty() && results.last().remainder.isNotEmpty() -> {
                    val unexpectedValue = results.last().remainder.first()
                    unexpectedValue.matchFailure()
                }

                else -> null
            }
        }

        return (nodeStructureMismatchError ?: nothingEvenCameCloseError.value)
    }

    private fun matchNamespaces(sampleData: XMLNode): Result {
        return PLACEHOLDER_USE_GIT_BLAME_TO_FIND_RELEVANT_COMMIT(
            Success(),
            "Removed namespace validation but we should put it back"
        )
    }

    private fun matchAttributes(sampleData: XMLNode, resolver: Resolver): Result {
        val patternAttributesWithoutXmlns = pattern.attributes.filterNot {
            it.key == "xmlns" || it.key.startsWith("xmlns:") || it.key.startsWith(SPECMATIC_XML_ATTRIBUTE_PREFIX)
        }.let { attributesWithoutXmlns ->
            dropSOAP11MetadataAttributes(attributesWithoutXmlns, pattern::attributeNamespaceUri)
        }.let { attributesWithoutXmlns ->
            dropPolymorphicXSITypeAttribute(attributesWithoutXmlns, pattern::attributeNamespaceUri)
        }
        val sampleAttributesWithoutXmlns: Map<String, StringValue> = sampleData.attributes.filterNot {
            it.key == "xmlns" ||
                    it.key.startsWith("xmlns:") ||
                    it.key.startsWith(SPECMATIC_XML_ATTRIBUTE_PREFIX)
        }.let { attributesWithoutXmlns ->
            dropSOAP11MetadataAttributes(attributesWithoutXmlns, sampleData::attributeNamespaceUri)
        }.let { attributesWithoutXmlns ->
            dropPolymorphicXSITypeAttribute(attributesWithoutXmlns, sampleData::attributeNamespaceUri)
        }

        val sampleAttributesForKeyCheck = sampleAttributesWithoutXmlns.filterNot { (key, _) ->
            isAllowedByAttributeWildcard(key, sampleData, patternAttributesWithoutXmlns)
        }

        val missingKey = resolver.findKeyError(
            ignoreXMLNamespaces(patternAttributesWithoutXmlns),
            ignoreXMLNamespaces(sampleAttributesForKeyCheck)
        )
        if (missingKey != null) {
            val keyLocation = resolver.locate(attributePointers[missingKey.name] ?: attributePointers[withoutOptionality(missingKey.name)])
            return missingKey.missingKeyToResult("attribute", resolver.mismatchMessages)
                .breadCrumb(missingKey.name, keyLocation)
        }

        return matchAttributes(patternAttributesWithoutXmlns, sampleAttributesWithoutXmlns, resolver)
    }

    private fun isAllowedByAttributeWildcard(
        attributeName: String,
        sampleData: XMLNode,
        declaredAttributes: Map<String, Pattern>
    ): Boolean {
        val declaredAttributeNames = declaredAttributes.keys.map(::withoutOptionality).toSet()

        return withoutOptionality(attributeName) !in declaredAttributeNames &&
                pattern.attributeWildcards.any { it.allows(attributeName, sampleData) }
    }

    private fun <T> dropSOAP11MetadataAttributes(
        attributes: Map<String, T>,
        attributeNamespaceUri: (String) -> String?,
    ): Map<String, T> =
        attributes.filterNot { (key, _) ->
            val namespaceUri = runCatching { attributeNamespaceUri(key) }.getOrNull()
            isSOAP11MetadataAttribute(key, namespaceUri)
        }

    private fun isSOAP11MetadataAttribute(attributeName: String, namespaceUri: String?): Boolean {
        val name = attributeName.substringAfter(":")

        return when (name) {
            "encodingStyle" -> namespaceUri == SOAP_ENVELOPE_NAMESPACE
            else -> false
        }
    }

    private fun <T> dropPolymorphicXSITypeAttribute(
        attributes: Map<String, T>,
        attributeNamespaceUri: (String) -> String?,
    ): Map<String, T> {
        if (!shouldDropPolymorphicXSITypeAttribute()) {
            return attributes
        }

        return attributes.filterNot { (key, _) ->
            val namespaceUri = runCatching { attributeNamespaceUri(key) }.getOrNull()
            isXMLSchemaInstanceTypeAttribute(key, namespaceUri)
        }
    }

    private fun shouldDropPolymorphicXSITypeAttribute(): Boolean =
        pattern.wsdlTypeSelectionMode == WSDLTypeSelectionMode.Polymorphic

    private fun matchName(sampleData: XMLNode, resolver: Resolver): Result {
        if (sampleData.name != pattern.name)
            return valueMismatchResult(pattern.name, sampleData.name, resolver.mismatchMessages)

        return Success()
    }

    private fun matchAttributes(
        patternAttributesWithoutXmlns: Map<String, Pattern>,
        sampleAttributesWithoutXmlns: Map<String, StringValue>,
        resolver: Resolver
    ): Result =
        mapZip(
            ignoreXMLNamespaces(patternAttributesWithoutXmlns),
            ignoreXMLNamespaces(sampleAttributesWithoutXmlns)
        ).asSequence().map { (key, patternValue, sampleValue) ->
            try {
                val resolvedValue: Value = when {
                    sampleValue.isPatternToken() -> sampleValue.trimmed()
                    else -> patternValue.parse(sampleValue.string, resolver)
                }
                resolver.matchesPattern(key, patternValue, resolvedValue)
            } catch (e: ContractException) {
                e.failure()
            }.breadCrumb(key)
        }.find { it is Failure } ?: Success()

    private fun <ValueType> ignoreXMLNamespaces(attributes: Map<String, ValueType>): Map<String, ValueType> =
        attributes.filterNot { it.key.lowercase().startsWith("xmlns:") }

    private fun expectingEmpty(sampleData: XMLNode, type: Pattern, resolver: Resolver): Boolean {
        val resolvedPatternSet = type.patternSet(resolver).map { resolvedHop(it, resolver) }
        return sampleData.childNodes.isEmpty() && pattern.nodes.size == 1 && (EmptyStringPattern in resolvedPatternSet || StringPattern() in resolvedPatternSet)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return XMLNode("", "", emptyMap(), valueList.map { it as XMLNode }, "", emptyMap())
    }

    override fun generate(resolver: Resolver): XMLNode {
        val cyclePreventionPattern = cyclePreventionPattern()
        if (cyclePreventionPattern != this) {
            if (resolver.hasCycle(cyclePreventionPattern)) {
                if (canReturnNullOnCycle()) {
                    return XMLNode(pattern.realName, emptyMap(), emptyList())
                }

                throw recursiveGenerationException(this)
            }

            return resolver.withCyclePrevention(
                cyclePreventionPattern,
                returnNullOnCycle = canReturnNullOnCycle()
            ) { cyclePreventedResolver ->
                generateXML(cyclePreventedResolver)
            } ?: XMLNode(pattern.realName, emptyMap(), emptyList())
        }

        return generateXML(resolver)
    }

    private fun generateXML(resolver: Resolver): XMLNode {
        if (!pattern.isConcrete()) {
            val dereferenced = dereferenceType(resolver)
            if (dereferenced !is XMLPattern) {
                return dereferenced.generate(resolver) as XMLNode
            }
        }

        val name = pattern.name

        val concreteWSDLTypeCandidates = concreteWSDLTypeCandidates(resolver)
        if (concreteWSDLTypeCandidates.isNotEmpty()) {
            return concreteWSDLTypeCandidates.random().generateXML(resolver)
        }

        val resolvedPattern = dereferenceType(resolver) as XMLPattern

        val nonSpecmaticAttributes =
            resolvedPattern.pattern.attributes.filterNot {
                it.key.startsWith(SPECMATIC_XML_ATTRIBUTE_PREFIX)
            }

        val newAttributes = nonSpecmaticAttributes.mapKeys { entry ->
            withoutOptionality(entry.key)
        }.mapValues { (key, pattern) ->
            attempt(breadCrumb = "$name.$key") {
                resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
                    cyclePreventedResolver.generate(key, pattern)
                }
            }
        }.mapValues {
            StringValue(it.value.toStringLiteral())
        }

        val nodes = resolvedPattern.pattern.nodes.asSequence().map {
            resolvedHop(it, resolver)
        }.map { pattern ->
            attempt(breadCrumb = name) {
                val generatedNodes = when {
                    pattern.hasXMLReferenceCycle(resolver) -> emptyList()
                    pattern is XMLPattern && pattern.hasTypeReference() -> pattern.generateNodes(resolver)
                    else -> resolver.withCyclePrevention(
                        pattern.cyclePreventionPattern(),
                        returnNullOnCycle = pattern.canReturnNullOnCycle()
                    ) { cyclePreventedResolver ->
                        pattern.generateNodes(cyclePreventedResolver)
                    }
                }

                generatedNodes ?: recursiveGenerationFallback(pattern)
            }
        }.flatten().map {
            when (it) {
                is XMLValue -> it
                else -> StringValue(it.toStringLiteral())
            }
        }.toList()

        return XMLNode(pattern.realName, newAttributes, nodes, inheritNamespacesInChildren = true)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        if (!pattern.isConcrete()) {
            val dereferenced = dereferenceType(resolver)
            if (dereferenced !is XMLPattern) {
                return resolver.withCyclePrevention(dereferenced) { cyclePreventedResolver ->
                    dereferenced.newBasedOn(row, cyclePreventedResolver)
                }
            }
        }

        val concreteWSDLTypeCandidates = concreteWSDLTypeCandidates(resolver)
        if (concreteWSDLTypeCandidates.isNotEmpty()) {
            return concreteWSDLTypeCandidates.asSequence().flatMap { selectedPattern ->
                selectedPattern.newBasedOn(row, resolver)
            }
        }

        return forEachKeyCombinationIn(
            pattern.attributes,
            row, returnValues { attributePattern: Map<String, Pattern> ->
                attempt(breadCrumb = pattern.name) {
                    newMapBasedOn(attributePattern, row, resolver).map { it.value }.map {
                        it.mapKeys { entry -> withoutOptionality(entry.key) }
                    }
                }
            }).map { it.value }.flatMap { newAttributes ->
            val newNodesList = when {
                row.containsField(pattern.name) -> {
                    attempt(breadCrumb = pattern.name) {
                        val dereferenced = dereferenceType(resolver)
                        val parsedData = when (dereferenced) {
                            is XMLPattern -> {
                                if (dereferenced.pattern.nodes.isEmpty())
                                    throw ContractException("Node ${pattern.name} is empty but an example with this name exists")

                                dereferenced.pattern.nodes[0].parse(row.getField(dereferenced.pattern.name), resolver)
                            }

                            else -> dereferenced.parse(row.getField(pattern.name), resolver)
                        }
                        val matchResult = when (dereferenced) {
                            is XMLPattern -> dereferenced.pattern.nodes[0].matches(parsedData, resolver)
                            else -> dereferenced.matches(parsedData, resolver)
                        }

                        if (matchResult is Failure)
                            throw ContractException(matchResult.toFailureReport())

                        sequenceOf(listOf(ExactValuePattern(parsedData)))
                    }
                }

                else -> {
                    listCombinations(pattern.nodes.map { childPattern ->
                        attempt(breadCrumb = pattern.name) {
                            when (childPattern) {
                                is XMLPattern -> {
                                    val returnNullOnCycle = childPattern.canBeOmittedAfterCycle()
                                    val generatedPatterns = when {
                                        childPattern.hasXMLReferenceCycle(resolver) -> sequenceOf(null)
                                        else -> resolver.withCyclePrevention(
                                            childPattern.cyclePreventionPattern(),
                                            returnNullOnCycle = returnNullOnCycle
                                        ) { childResolver ->
                                            val dereferenced = childPattern.dereferenceType(childResolver)

                                            childResolver.withCyclePrevention(
                                                childPattern.cyclePreventionPattern(),
                                                returnNullOnCycle = returnNullOnCycle
                                            ) { cyclePreventedResolver ->
                                                when (dereferenced) {
                                                    is XMLPattern -> when {
                                                        dereferenced.occurMultipleTimes() -> {
                                                            dereferenced.newBasedOn(row, cyclePreventedResolver)
                                                                .map {
                                                                    (it.value as XMLPattern).withTypeReferenceFrom(
                                                                        childPattern
                                                                    )
                                                                }
                                                        }

                                                        dereferenced.isOptional() -> {
                                                            dereferenced.newBasedOn(row, cyclePreventedResolver)
                                                                .map {
                                                                    (it.value as XMLPattern).withTypeReferenceFrom(
                                                                        childPattern
                                                                    )
                                                                }.plus(null)
                                                        }

                                                        else -> dereferenced.newBasedOn(row, cyclePreventedResolver)
                                                            .map {
                                                                (it.value as XMLPattern).withTypeReferenceFrom(
                                                                    childPattern
                                                                )
                                                            }
                                                    }

                                                    else -> dereferenced.newBasedOn(row, cyclePreventedResolver)
                                                        .map {
                                                            it.value
                                                        }
                                                }
                                            }
                                        }
                                    }

                                    generatedPatterns ?: recursiveExampleFallback(childPattern)
                                }

                                else -> resolver.withCyclePrevention(childPattern) { cyclePreventedResolver ->
                                    childPattern.newBasedOn(row, cyclePreventedResolver).map { it.value }
                                }
                            }
                        }
                    }.map { HasValue(it) }).map { it.value }
                }
            }

            newNodesList.map { newNodes ->
                XMLPattern(pattern.copy(attributes = newAttributes, nodes = newNodes))
            }
        }.map { HasValue(it) }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<XMLPattern> {
        val concreteWSDLTypeCandidates = concreteWSDLTypeCandidates(resolver)
        if (concreteWSDLTypeCandidates.isNotEmpty()) {
            return concreteWSDLTypeCandidates.asSequence().flatMap { selectedPattern ->
                selectedPattern.newBasedOn(resolver)
            }
        }

        return allOrNothingCombinationIn(
            pattern.attributes,
            Row(),
            null,
            null, returnValues { attributePattern: Map<String, Pattern> ->
                attempt(breadCrumb = this.pattern.name) {
                    newBasedOn(attributePattern, resolver).map {
                        it.mapKeys { entry -> withoutOptionality(entry.key) }
                    }
                }
            }).map { it.value }.flatMap { newAttributes ->
            val newNodesList = allOrNothingListCombinations(pattern.nodes.map { childPattern ->
                attempt(breadCrumb = this.pattern.name) {
                    when (childPattern) {
                        is XMLPattern -> {
                            val returnNullOnCycle = childPattern.canBeOmittedAfterCycle()
                            val generatedPatterns = when {
                                childPattern.hasXMLReferenceCycle(resolver) -> sequenceOf(null)
                                else -> resolver.withCyclePrevention(
                                    childPattern.cyclePreventionPattern(),
                                    returnNullOnCycle = returnNullOnCycle
                                ) { childResolver ->
                                    val dereferenced = childPattern.dereferenceType(childResolver)

                                    childResolver.withCyclePrevention(
                                        childPattern.cyclePreventionPattern(),
                                        returnNullOnCycle = returnNullOnCycle
                                    ) { cyclePreventedResolver ->
                                        when (dereferenced) {
                                            is XMLPattern -> when {
                                                dereferenced.occurMultipleTimes() -> {
                                                    dereferenced.newBasedOn(cyclePreventedResolver)
                                                        .map { it.withTypeReferenceFrom(childPattern) }
                                                }

                                                dereferenced.isOptional() -> {
                                                    dereferenced.newBasedOn(cyclePreventedResolver)
                                                        .map { it.withTypeReferenceFrom(childPattern) }
                                                        .plus(null)
                                                }

                                                else -> dereferenced.newBasedOn(cyclePreventedResolver)
                                                    .map { it.withTypeReferenceFrom(childPattern) }
                                            }

                                            else -> dereferenced.newBasedOn(cyclePreventedResolver)
                                        }
                                    }
                                }
                            }

                            generatedPatterns ?: recursiveExampleFallback(childPattern)
                        }

                        else -> resolver.withCyclePrevention(childPattern) { cyclePreventedResolver ->
                            childPattern.newBasedOn(cyclePreventedResolver)
                        }
                    }
                }
            }
            )

            newNodesList.map { newNodes ->
                XMLPattern(pattern.copy(attributes = newAttributes, nodes = newNodes.toList()))
            }
        }
    }

    private fun concreteWSDLTypeCandidates(resolver: Resolver): List<XMLPattern> {
        val declaredPattern = try {
            dereferenceType(resolver)
        } catch (e: ContractException) {
            return emptyList()
        }

        if (declaredPattern !is XMLPattern) {
            return emptyList()
        }

        val lookupPattern = when {
            declaredPattern.hasWSDLTypeLookupMetadata() -> declaredPattern
            hasWSDLTypeLookupMetadata() -> this
            else -> declaredPattern
        }

        val declaredWSDLType = lookupPattern.pattern.wsdlTypeName() ?: return emptyList()

        if (lookupPattern.pattern.wsdlTypeSelectionMode == WSDLTypeSelectionMode.CurrentTypeOnly) {
            return emptyList()
        }

        when (val selectedType = selectWSDLType(pattern.xsiTypeName(), lookupPattern, resolver)) {
            is WSDLTypeSelection.Use -> {
                if (pattern.xsiTypeName() == declaredWSDLType) {
                    return emptyList()
                }

                return listOfNotNull((selectedType.pattern as? XMLPattern)?.withCurrentWSDLTypeOnly())
            }

            is WSDLTypeSelection.Unknown,
            is WSDLTypeSelection.Invalid,
            is WSDLTypeSelection.Abstract -> throw ContractException(
                failureForWSDLTypeSelection(selectedType).toFailureReport()
            )

            null -> Unit
        }

        val basePattern = concreteBaseWSDLTypeCandidate(lookupPattern)

        val derivedPatterns = lookupPattern.concreteDerivedWSDLPatterns(resolver).mapNotNull { (derivedType, derivedPattern) ->
            val mergedPattern = lookupPattern.mergeWSDLDerivedPattern(derivedPattern) ?: return@mapNotNull null
            mergedPattern.withXSIType(derivedType).withCurrentWSDLTypeOnly()
        }

        val candidatePatterns = listOfNotNull(basePattern) + derivedPatterns

        if (candidatePatterns.isEmpty() && lookupPattern.pattern.wsdlTypeIsAbstract) {
            throw ContractException("Cannot select a concrete WSDL type for abstract type ${declaredWSDLType.namespace}#${declaredWSDLType.localName}; no concrete derived types were found.")
        }

        return candidatePatterns
    }

    private fun concreteBaseWSDLTypeCandidate(declaredPattern: XMLPattern): XMLPattern? {
        if (declaredPattern.pattern.wsdlTypeIsAbstract) {
            return null
        }

        val declaredType = declaredPattern.pattern.wsdlTypeName() ?: return null
        if (declaredType.namespace == XML_SCHEMA_NAMESPACE) {
            return null
        }

        return declaredPattern.withCurrentWSDLTypeOnly()
    }

    private fun hasWSDLTypeLookupMetadata(): Boolean =
        pattern.wsdlKnownTypeKeys.isNotEmpty() ||
                pattern.wsdlCompatibleTypeKeys.isNotEmpty() ||
                pattern.wsdlConcreteSubtypeKeys.isNotEmpty()

    private fun mergeWSDLDerivedPattern(derivedPattern: Pattern): XMLPattern? {
        return when (derivedPattern) {
            is XMLPattern -> mergeReferredPattern(derivedPattern) as? XMLPattern
            else -> wrapDerivedSimpleContentInDeclaredElement(derivedPattern)
        }
    }

    private fun wrapDerivedSimpleContentInDeclaredElement(derivedPattern: Pattern): XMLPattern =
        copy(pattern = pattern.copy(nodes = listOf(derivedPattern)))

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> {
        return newBasedOn(row, resolver).map { HasValue(it.value) }
    }

    private fun dereferenceType(resolver: Resolver): Pattern {
        if (pattern.isConcrete()) {
            return this
        }

        val specmaticType = pattern.attributes[TYPE_ATTRIBUTE_NAME]
        val resolved = resolver.getPattern("($specmaticType)")
        return mergeReferredPattern(resolved)
    }

    private fun mergeReferredPattern(referred: Pattern): Pattern {
        return when (referred) {
            is XMLPattern -> {
                val attributesFromReferring = this.pattern.attributes.filterKeys { it != TYPE_ATTRIBUTE_NAME }
                val attributesFromReferred = referred.pattern.attributes.filterKeys { it != TYPE_ATTRIBUTE_NAME }
                val attributes = attributesFromReferred + attributesFromReferring
                referred.copy(
                    pattern = referred.pattern.copy(
                        name = this.pattern.name,
                        realName = this.pattern.realName,
                        attributes = attributes,
                        namespaceUri = this.pattern.namespaceUri,
                    ),
                )
            }

            is AnyPattern -> referred.copy(pattern = referred.pattern.map(::mergeReferredPattern))
            else -> referred
        }
    }

    private fun withTypeReferenceFrom(original: XMLPattern): XMLPattern {
        val referencedType = original.pattern.attributes[TYPE_ATTRIBUTE_NAME] ?: return this
        return copy(pattern = pattern.copy(attributes = pattern.attributes + mapOf(TYPE_ATTRIBUTE_NAME to referencedType)))
    }

    fun occurMultipleTimes(): Boolean = pattern.getNodeOccurrence() == NodeOccurrence.Multiple

    private fun isOptional(): Boolean = pattern.getNodeOccurrence() == NodeOccurrence.Optional

    private fun recursiveGenerationFallback(pattern: Pattern): List<Value> {
        if (pattern is XMLPattern && pattern.canBeOmittedAfterCycle()) {
            return emptyList()
        }

        throw recursiveGenerationException(pattern)
    }

    private fun recursiveExampleFallback(pattern: Pattern): Sequence<Pattern?> {
        if (pattern is XMLPattern && pattern.canBeOmittedAfterCycle()) {
            return sequenceOf(null)
        }

        throw recursiveGenerationException(pattern)
    }

    private fun XMLPattern.canBeOmittedAfterCycle(): Boolean = occurMultipleTimes() || isOptional()

    private fun Pattern.canReturnNullOnCycle(): Boolean = this is XMLPattern && canBeOmittedAfterCycle()

    private fun Pattern.hasXMLReferenceCycle(resolver: Resolver): Boolean {
        val cyclePreventionPattern = cyclePreventionPattern()
        return cyclePreventionPattern != this && resolver.hasCycle(cyclePreventionPattern)
    }

    override fun generateXMLChildValues(resolver: Resolver): List<XMLValue> {
        return when {
            occurMultipleTimes() ->
                0.until(randomNumber(XML_RANDOM_NUMBER_CEILING)).flatMap {
                    generatedValueAsXMLChildValues(generate(resolver))
                }

            else -> generatedValueAsXMLChildValues(generate(resolver))
        }
    }

    private fun Pattern.generateNodes(resolver: Resolver): List<Value> {
        return when {
            this is XMLChildGenerationPattern -> generateXMLChildValues(resolver)
            else -> listOf(generate(resolver))
        }
    }

    private fun XMLPattern.hasTypeReference(): Boolean = referredType != null

    private fun Pattern.cyclePreventionPattern(): Pattern {
        val referredType = (this as? XMLPattern)?.referredType ?: return this
        return DeferredPattern(withPatternDelimiters(referredType))
    }

    private fun recursiveGenerationException(pattern: Pattern): ContractException {
        return when (pattern) {
            is XMLPattern -> ContractException("Cannot generate XML for required recursive node ${pattern.pattern.realName}")
            else -> ContractException("Cannot generate recursive XML value for $pattern")
        }
    }

    override fun parse(value: String, resolver: Resolver): Value {
        return toXMLNode(parseXML(value))
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val otherResolvedPattern = resolvedHop(otherPattern, otherResolver)

        return when (otherResolvedPattern) {
            is ExactValuePattern -> otherResolvedPattern.fitsWithin(
                listOf(this),
                otherResolver,
                thisResolver,
                typeStack
            )

            // TODO: BCC blind spot for top-level $ref XML bodies.
            // When `application/xml` request/response bodies are `$ref`s to component schemas,
            // toXMLPattern() returns a stub XMLPattern that only carries the `specmatic_type`
            // attribute and has no child nodes. encompasses() then compares the two stubs:
            // nodeNames match, attributes (both just `specmatic_type=Customer`) match, and
            // memberList iteration is over empty lists — so any breakage *inside* the
            // referenced component (e.g. a child property going from required to optional)
            // goes undetected. Request matches() is unaffected because matchesXMLNode() calls
            // dereferenceType() before deep-matching.
            //
            // Sketch of the fix (attempted in this PR, reverted because it needs more work):
            //   1. In `encompasses`, before the deep compare, call
            //      `this.dereferenceType(thisResolver)` and
            //      `otherResolvedPattern.dereferenceType(otherResolver)`. If either changes,
            //      recurse with the dereferenced patterns.
            //   2. That alone yields correct *detection* but the source-location anchor lands
            //      on the component header, not the child property. The component patterns
            //      cached via `cacheComponentPattern` (see OpenApiSpecification.handleXmlReference
            //      → convertAndCacheResolvedRef) are built by `toXMLPattern` but never passed
            //      through `annotateXMLPattern`, so the cached Customer.name XMLPattern has no
            //      `schemaPointer`. Fix: annotate at cache time using
            //      `/components/schemas/<name>` as the base pointer (the existing JSON path
            //      already does this — see annotateJsonObjectPattern call sites for shared
            //      components).
            //   3. mergeReferredPattern() in XMLPattern.kt copies `referred` and overrides
            //      name/attributes from the referring stub — make sure it preserves the
            //      referred pattern's `schemaPointer` and `attributePointers` (data-class copy
            //      already does, but worth verifying after step 2).
            //   4. Add BCC test fixtures back: ref-name-mandatory-old.yaml / -new.yaml with
            //      response body $ref to a Customer component whose `name` toggles required.
            //      Expected anchor: the new spec's `components.schemas.Customer.properties.name`
            //      line (was 30:11 in the test fixture I drafted).
            is XMLPattern -> nodeNamesShouldBeEqual(otherResolvedPattern).ifSuccess {
                attributesEncompass(otherResolvedPattern, thisResolver, otherResolver, typeStack)
            }.ifSuccess {
                thisOccurrenceEncompassesTheOther(this, otherResolvedPattern)
            }.ifSuccess {
                val theseMembers = this.memberList
                val otherMembers = otherResolvedPattern.memberList

                val others = otherMembers.getEncompassables(otherResolver)
                val these =
                    adapt(adaptFromList(theseMembers.getEncompassables(thisResolver), thisResolver), thisResolver)

                val adaptedOthers = adapt(others, otherResolver)

                val results =
                    these.runningFold(ConsumeResult<Pattern, Pattern>(adaptedOthers)) { consumeResult, thisOne ->
                        val unmatched = dropOneIfMatching(consumeResult.remainder) { otherOne ->
                            encompassResult(thisOne, otherOne, thisResolver, otherResolver, typeStack)
                        }

                        if (unmatched.size == consumeResult.remainder.size) {
                            val failure = encompassResult(
                                thisOne,
                                unmatched.first(),
                                thisResolver,
                                otherResolver,
                                typeStack
                            ) as Failure
                            ConsumeResult(failure, unmatched)
                        } else {
                            ConsumeResult(Success(), unmatched)
                        }
                    }

                val failureResult = results.find { it.result is Failure }?.result

                failureResult ?: provisionalFailure(results) ?: Success()
            }

            else -> patternMismatchResult(this, otherResolvedPattern, thisResolver.mismatchMessages)
        }.breadCrumb(this.pattern.name, locateForEncompassFailure(otherResolvedPattern, thisResolver, otherResolver))
    }

    private fun locateForEncompassFailure(otherResolvedPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver): SourceLocation? {
        val otherPointer = (otherResolvedPattern as? XMLPattern)?.schemaPointer
        return otherResolver.locate(otherPointer) ?: thisResolver.locate(schemaPointer)
    }

    private fun attributesEncompass(
        otherResolvedPattern: XMLPattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack,
    ): Result {
        val thisAttributes =
            pattern.attributes.filterNot { it.key.startsWith(SPECMATIC_XML_ATTRIBUTE_PREFIX) || it.key.startsWith("xmlns:") }
        val otherAttributes = otherResolvedPattern.pattern.attributes.filterNot {
            it.key.startsWith(SPECMATIC_XML_ATTRIBUTE_PREFIX) || it.key.startsWith("xmlns:")
        }

        val declaredAttributeResult = mapEncompassesMap(
            thisAttributes,
            otherAttributes,
            thisResolver,
            otherResolver,
            typeStack,
            otherPropertyPointers = otherResolvedPattern.attributePointers,
            thisPropertyPointers = this.attributePointers
        )

        val thisAttributeNames = thisAttributes.keys.map(::withoutOptionality).toSet()
        val extraAttributeFailures =
            otherAttributes.keys.filter { withoutOptionality(it) !in thisAttributeNames }.mapNotNull { attributeName ->
                val namespaceUri = otherResolvedPattern.pattern.attributeNamespaceUri(attributeName)
                val rawName = withoutOptionality(attributeName)
                val otherPointer = otherResolvedPattern.attributePointers[attributeName] ?: otherResolvedPattern.attributePointers[rawName]
                when {
                    pattern.attributeWildcards.any { it.namespaceConstraint.allows(namespaceUri) } -> null
                    else -> Failure("XML attribute compatibility failed: attribute \"$attributeName\" is present in the other pattern, but this pattern does not declare it and has no anyAttribute wildcard that allows it.")
                        .breadCrumb(rawName, otherResolver.locate(otherPointer))
                }
            }

        val wildcardFailures = otherResolvedPattern.pattern.attributeWildcards.mapNotNull { otherWildcard ->
            when {
                pattern.attributeWildcards.any { it.encompasses(otherWildcard) } -> null
                else -> Failure("XML attribute compatibility failed: wildcard ${otherWildcard.namespaceConstraint.description} is present in the other pattern, but this pattern has no compatible anyAttribute wildcard.")
            }
        }

        return Result.fromResults(listOf(declaredAttributeResult) + extraAttributeFailures + wildcardFailures)
    }

    private fun provisionalFailure(results: List<ConsumeResult<Pattern, Pattern>>): Failure? {
        if (results.isEmpty() || results.last().remainder.isEmpty())
            return null

        val provisionalError = results.map { it.provisionalError?.result }.filterIsInstance<Failure>().firstOrNull()

        return provisionalError ?: genericProvisionalFailure(results)
    }

    private fun genericProvisionalFailure(results: List<ConsumeResult<Pattern, Pattern>>): Failure {
        val unmatched = results.last().remainder

        val nodeDescriptor = when (unmatched.size) {
            1 -> "Node"
            else -> "Nodes"
        }

        val names = unmatched.joinToString(", ") {
            when (it) {
                is XMLPattern -> it.pattern.name
                else -> it.typeName
            }
        }

        return Failure("$nodeDescriptor named $names were not matched")
    }

    private fun dropOneIfMatching(remainder: List<Pattern>, test: (Pattern) -> Result): List<Pattern> {
        if (remainder.isEmpty())
            return remainder

        val first = remainder.first()

        return when (test(first)) {
            is Success -> remainder.drop(1)
            is Failure -> remainder
        }
    }

    private fun encompassResult(
        thisOne: Pattern,
        otherOne: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return try {
            val otherOneAdjustedForExactValue = when {
                otherOne is ExactValuePattern && otherOne.pattern is StringValue -> ExactValuePattern(
                    thisOne.parse(
                        otherOne.pattern.string,
                        thisResolver
                    )
                )

                else -> otherOne
            }

            thisOne.encompasses(otherOneAdjustedForExactValue, thisResolver, otherResolver, typeStack)
        } catch (e: Throwable) {
            Failure(e.message ?: e.javaClass.name)
        }

    }

    private fun getNodeOccurrence(): NodeOccurrence {
        return pattern.getNodeOccurrence()
    }

    private fun thisOccurrenceEncompassesTheOther(thisOne: XMLPattern, otherOne: XMLPattern): Result {
        val thisTypeOccurrence = thisOne.pattern.getAttributeValue(OCCURS_ATTRIBUTE_NAME)
        val otherTypeOccurrence = otherOne.pattern.getAttributeValue(OCCURS_ATTRIBUTE_NAME)

        return when (thisTypeOccurrence) {
            otherTypeOccurrence -> Success()
            else -> thisOne.getNodeOccurrence().encompasses(otherOne.getNodeOccurrence())
        }
    }

    private fun nodeNamesShouldBeEqual(otherResolvedPattern: XMLPattern) = when {
        pattern.name != otherResolvedPattern.pattern.name ->
            Failure("Expected a node named ${pattern.name}, but got ${otherResolvedPattern.pattern.name} instead.")

        else -> Success()
    }

    private fun adapt(types: List<Pattern>, resolver: Resolver): List<Pattern> {
        if (types.isEmpty())
            return listOf(EmptyStringPattern)

        return adaptFromList(types, resolver)
    }

    private fun adaptFromList(types: List<Pattern>, resolver: Resolver): List<Pattern> {
        val first = types.firstOrNull()

        if (first is ListPattern) {
            val typeName = first.pattern.pattern.toString().removeSurrounding("(", ")")
            val type = resolver.getPattern("($typeName)")
            if (type !is XMLPattern)
                throw ContractException("A list specification inside an XML definition must refer to an XML type. But $typeName is not an XML type.")

            val multipleOccursAttribute =
                mapOf(OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(MULTIPLE_ATTRIBUTE_VALUE)))

            return listOf(
                type.copy(
                    pattern = type.pattern.copy(
                        attributes = type.pattern.attributes.plus(
                            multipleOccursAttribute
                        )
                    )
                )
            )
        }

        return types
    }

    val referredType: String?
        get() {
            return pattern.attributes[TYPE_ATTRIBUTE_NAME]?.let {
                it as ExactValuePattern
                it.pattern.toStringLiteral()
            }
        }

    override val memberList: MemberList
        get() = MemberList(pattern.nodes, null)

    override val typeName: String = "xml"

    // TODO not sure if this is still needed
    fun toGherkinString(additionalIndent: String = "", indent: String = ""): String {
        return pattern.toGherkinString(additionalIndent, indent)
    }

    fun toGherkinXMLNode(): XMLNode {
        return pattern.toGherkinishNode()
    }

    fun toGherkinStatement(specmaticTypeName: String): String {
        val typeString = this.toGherkinXMLNode().toPrettyStringValue().trim()
        return "And type $specmaticTypeName\n\"\"\"\n$typeString\n\"\"\""
    }
}

private fun XMLNode.xsiTypeName(): WSDLTypeName? {
    val value = attributeValueByNamespace(XML_SCHEMA_INSTANCE_NAMESPACE, "type")?.toStringLiteral() ?: return null
    return runCatching {
        val namespace = when {
            value.namespacePrefix().isBlank() -> elementNamespaceUriOrNull().orEmpty()
            else -> resolveNamespace(value)
        }
        WSDLTypeName(namespace, value.localName(), value.namespacePrefix().ifBlank { null })
    }.getOrNull()
}

private fun XMLNode.wsdlElementName(): WSDLTypeName =
    WSDLTypeName(elementNamespaceUriOrNull().orEmpty(), name)

private fun Pattern.findPatternForWSDLType(typeName: WSDLTypeName, resolver: Resolver): Pattern? {
    val typeKey = wsdlCompatibleTypeKeys()[typeName] ?: return null
    return resolver.patternForWSDLKey(typeKey)
}

private fun Resolver.findPatternForWSDLType(typeName: WSDLTypeName): Pattern? {
    return newPatterns.values.firstOrNull { pattern ->
        pattern.wsdlTypeName() == typeName
    }
}

private fun Pattern.knowsWSDLType(typeName: WSDLTypeName): Boolean =
    wsdlKnownTypeKeys().containsKey(typeName)

private fun Pattern.concreteDerivedWSDLPatterns(resolver: Resolver): List<Pair<WSDLTypeName, Pattern>> {
    return wsdlConcreteSubtypeKeys().mapNotNull { (typeName, typeKey) ->
        if (typeName.namespace == XML_SCHEMA_NAMESPACE) return@mapNotNull null

        val pattern = resolver.patternForWSDLKey(typeKey) ?: return@mapNotNull null
        if (pattern.isAbstractWSDLType()) return@mapNotNull null

        typeName to pattern
    }
}

private fun Resolver.patternForWSDLKey(typeKey: String): Pattern? {
    val rawTypeKey = withoutPatternDelimiters(typeKey)
    return newPatterns[typeKey] ?: newPatterns[withPatternDelimiters(rawTypeKey)] ?: newPatterns[rawTypeKey]
}

private fun Pattern.wsdlKnownTypeKeys(): Map<WSDLTypeName, String> {
    return when (this) {
        is XMLPattern -> pattern.wsdlKnownTypeKeys
        is AnyPattern -> pattern.flatMap { it.wsdlKnownTypeKeys().entries }.associate { it.toPair() }
        else -> emptyMap()
    }
}

private fun Pattern.wsdlCompatibleTypeKeys(): Map<WSDLTypeName, String> {
    return when (this) {
        is XMLPattern -> pattern.wsdlCompatibleTypeKeys
        is AnyPattern -> pattern.flatMap { it.wsdlCompatibleTypeKeys().entries }.associate { it.toPair() }
        else -> emptyMap()
    }
}

private fun Pattern.wsdlConcreteSubtypeKeys(): Map<WSDLTypeName, String> {
    return when (this) {
        is XMLPattern -> pattern.wsdlConcreteSubtypeKeys
        is AnyPattern -> pattern.flatMap { it.wsdlConcreteSubtypeKeys().entries }.associate { it.toPair() }
        else -> emptyMap()
    }
}

private fun Pattern.wsdlTypeName(): WSDLTypeName? {
    return when (this) {
        is XMLPattern -> pattern.wsdlTypeName()
        is AnyPattern -> pattern.asSequence().mapNotNull { it.wsdlTypeName() }.firstOrNull()
        else -> null
    }
}

private fun Pattern.isAbstractWSDLType(): Boolean {
    return when (this) {
        is XMLPattern -> pattern.wsdlTypeIsAbstract
        is AnyPattern -> pattern.any { it.isAbstractWSDLType() }
        else -> false
    }
}

private fun Pattern.wsdlTypeSelectionMode(): WSDLTypeSelectionMode {
    return when (this) {
        is XMLPattern -> pattern.wsdlTypeSelectionMode
        is AnyPattern -> pattern.asSequence()
            .map { it.wsdlTypeSelectionMode() }
            .firstOrNull { it == WSDLTypeSelectionMode.CurrentTypeOnly }
            ?: WSDLTypeSelectionMode.Polymorphic

        else -> WSDLTypeSelectionMode.Polymorphic
    }
}

private fun Pattern.isDerivedFrom(baseType: WSDLTypeName, resolver: Resolver): Boolean {
    return when (this) {
        is XMLPattern -> pattern.isDerivedFrom(baseType, resolver)
        is AnyPattern -> pattern.any { it.isDerivedFrom(baseType, resolver) }
        else -> false
    }
}

private fun XMLTypeData.isDerivedFrom(baseType: WSDLTypeName, resolver: Resolver): Boolean {
    val directBase = wsdlBaseTypeName?.let { baseName ->
        WSDLTypeName(wsdlBaseTypeNamespace.orEmpty(), baseName)
    } ?: return false

    if (directBase == baseType) {
        return true
    }

    val basePattern = resolver.findPatternForWSDLType(directBase) ?: return false
    return basePattern.isDerivedFrom(baseType, resolver)
}

private fun XMLPattern.withXSIType(typeName: WSDLTypeName): XMLPattern {
    val typePrefix = pattern.prefixForNamespace(typeName.namespace) ?: pattern.prefixForElementNamespace(typeName.namespace) ?: pattern.availableNamespacePrefix()
    val typeAttributeValue = listOf(typePrefix, typeName.localName).filter(String::isNotBlank).joinToString(":")
    val schemaInstancePrefix = pattern.prefixForNamespace(XML_SCHEMA_INSTANCE_NAMESPACE) ?: pattern.availableNamespacePrefix("xsi")
    val schemaInstanceTypeAttributeName = "$schemaInstancePrefix:type"
    val namespaceAttributes = pattern.namespaceAttributesForXSIType(typeName.namespace, typePrefix, schemaInstancePrefix)
    val xsiTypeAttribute = schemaInstanceTypeAttributeName to ExactValuePattern(StringValue(typeAttributeValue))

    return copy(
        pattern = pattern.copy(
            attributes = pattern.attributes + namespaceAttributes + xsiTypeAttribute,
            attributeNamespaceUris = pattern.attributeNamespaceUris + mapOf(schemaInstanceTypeAttributeName to XML_SCHEMA_INSTANCE_NAMESPACE)
        )
    )
}

private fun XMLPattern.withElementNameFrom(node: XMLNode): XMLPattern =
    withElementName(node.name, node.realName, node.elementNamespaceUriOrNull())

private fun XMLPattern.withElementNameFrom(typeData: XMLTypeData): XMLPattern =
    withElementName(typeData.name, typeData.realName, typeData.namespaceUri)

private fun XMLPattern.withElementName(name: String, realName: String, namespaceUri: String?): XMLPattern =
    copy(
        pattern = pattern.copy(
            name = name,
            realName = realName,
            namespaceUri = namespaceUri,
        )
    )

private fun <T> PLACEHOLDER_USE_GIT_BLAME_TO_FIND_RELEVANT_COMMIT(value: T, s: String): T {
    return value
}
