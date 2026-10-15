package io.specmatic.core.wsdl.parser.message

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class UnqualifiedNamespaceTest {
    private val nodeName = "person"
    private val qualification = UnqualifiedNamespace(nodeName)

    @Test
    fun `unqualified namespace means there is on namespace prefix for the node`() {
        assertThat(qualification.namespacePrefix).isEmpty()
    }

    @Test
    fun `unqualified namespace returns the name as is`() {
        assertThat(qualification.name).isEqualTo(nodeName)
    }
}
