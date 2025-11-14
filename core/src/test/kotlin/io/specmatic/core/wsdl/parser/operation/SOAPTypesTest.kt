package io.specmatic.core.wsdl.parser.operation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.XMLPattern

internal class SOAPTypesTest {
    @Test
    fun `generates gherkin statements with one type`() {
        val types = SOAPTypes(mapOf("Name" to XMLPattern("<name>(string)</name>")))
        val statements = types.statements()
        assertThat(statements).hasSize(1)
        assertThat(statements.first()).startsWith("Given ")
    }

    @Test
    fun `generates gherkin statements with one multiple types`() {
        val types = SOAPTypes(mapOf("Name" to XMLPattern("<name>(string)</name>"), "Address" to XMLPattern("<address>(string)</address>")))
        val statements = types.statements()
        assertThat(statements).hasSize(2)
        assertThat(statements.first()).startsWith("Given ")
    }

    @Test
    fun `trims trailing whitespace from generated gherkin statements`() {
        val types = SOAPTypes(mapOf("Name" to XMLPattern("<name>(string)</name>")))
        val statements = types.statements()
        assertThat(statements).hasSize(1)
        // Verify no trailing whitespace exists
        statements.forEach { statement ->
            assertThat(statement).isEqualTo(statement.trimEnd())
            assertThat(statement).doesNotEndWith(" ")
        }
    }
}