package io.specmatic.core.wsdl.parser

enum class SOAPMessageType {
    Input,
    Output;

    val messageTypeName: String = this.name.lowercase()
}
