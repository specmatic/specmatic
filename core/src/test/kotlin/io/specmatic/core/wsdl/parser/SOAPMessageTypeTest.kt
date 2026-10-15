package io.specmatic.core.wsdl.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class SOAPMessageTypeTest {
    @Test
    fun `input type name is input`() {
        assertThat(SOAPMessageType.Input.messageTypeName).isEqualTo("input")
    }

    @Test
    fun `output type name is output`() {
        assertThat(SOAPMessageType.Output.messageTypeName).isEqualTo("output")
    }
}
