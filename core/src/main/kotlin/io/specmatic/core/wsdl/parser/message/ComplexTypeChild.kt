package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

interface ComplexTypeChild {
    fun process(wsdlTypeInfos: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>, typeStack: Set<String>): List<WSDLTypeInfo>
}
