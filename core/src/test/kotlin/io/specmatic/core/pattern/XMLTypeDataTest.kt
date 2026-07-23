package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class XMLTypeDataTest {
    @Test
    fun `prints a WSDL type name without a prefix using the namespace fallback`() {
        val typeName = WSDLTypeName("http://example.com/animals", "Animal")

        assertThat(typeName.displayNameForError()).isEqualTo("Animal (namespace: http://example.com/animals)")
    }

    @Test
    fun `prints a WSDL type name without a prefix or namespace using the local name`() {
        val typeName = WSDLTypeName("", "Animal")

        assertThat(typeName.displayNameForError()).isEqualTo("Animal")
    }

}
