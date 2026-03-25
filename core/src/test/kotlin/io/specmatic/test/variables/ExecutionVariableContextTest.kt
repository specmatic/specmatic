package io.specmatic.test.variables

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExecutionVariableContextTest {
    @Test
    fun `should flatten stored json values for lookup`() {
        val context = ExecutionVariableContext()

        context.store(
            "FIXTURE.BEFORE.1.RESPONSE",
            JSONObjectValue(
                mapOf(
                    "id" to NumberValue(10),
                    "name" to StringValue("Jane")
                )
            )
        )

        assertThat(context.getValue("FIXTURE.BEFORE.1.RESPONSE.id")).isEqualTo(NumberValue(10))
        assertThat(context.getValue("FIXTURE.BEFORE.1.RESPONSE.name")).isEqualTo(StringValue("Jane"))
        assertThat(context.allValues()).containsKeys(
            "FIXTURE.BEFORE.1.RESPONSE",
            "FIXTURE.BEFORE.1.RESPONSE.id",
            "FIXTURE.BEFORE.1.RESPONSE.name"
        )
    }

    @Test
    fun `should resolve exact substitution to original typed value`() {
        val context = ExecutionVariableContext()
        context.store(
            "FIXTURE.BEFORE.1.RESPONSE",
            JSONObjectValue(mapOf("id" to NumberValue(10)))
        )

        val resolvedValue = context.resolveValue(StringValue("$(FIXTURE.BEFORE.1.RESPONSE)"))

        assertThat(resolvedValue).isEqualTo(JSONObjectValue(mapOf("id" to NumberValue(10))))
    }

    @Test
    fun `should resolve inline substitutions inside strings`() {
        val context = ExecutionVariableContext()
        context.store("FIXTURE.BEFORE.1.RESPONSE.id", NumberValue(10))

        assertThat(context.resolveString("/orders/$(FIXTURE.BEFORE.1.RESPONSE.id)"))
            .isEqualTo("/orders/10")
    }

    @Test
    fun `should resolve nested maps and lists preserving json types`() {
        val context = ExecutionVariableContext()
        context.store(
            "FIXTURE.BEFORE.1.RESPONSE",
            JSONObjectValue(
                mapOf(
                    "id" to NumberValue(10),
                    "tags" to JSONArrayValue(listOf(StringValue("a"), StringValue("b")))
                )
            )
        )

        val resolved = context.resolveAny(
            mapOf(
                "id" to "$(FIXTURE.BEFORE.1.RESPONSE.id)",
                "nested" to listOf("$(FIXTURE.BEFORE.1.RESPONSE.tags)")
            )
        ) as Map<*, *>

        assertThat(resolved["id"]).isEqualTo(10)
        assertThat(resolved["nested"]).isEqualTo(listOf(listOf("a", "b")))
    }

    @Test
    fun `should preserve non string values while resolving Value trees`() {
        val context = ExecutionVariableContext()

        val resolved = context.resolveValue(
            JSONObjectValue(
                mapOf(
                    "flag" to BooleanValue(true),
                    "count" to NumberValue(2),
                    "list" to JSONArrayValue(listOf(NumberValue(3)))
                )
            )
        )

        assertThat(resolved).isEqualTo(
            JSONObjectValue(
                mapOf(
                    "flag" to BooleanValue(true),
                    "count" to NumberValue(2),
                    "list" to JSONArrayValue(listOf(NumberValue(3)))
                )
            )
        )
    }

    @Test
    fun `should leave plain strings unchanged when no substitutions are present`() {
        val context = ExecutionVariableContext()

        assertThat(context.resolveString("/orders/static")).isEqualTo("/orders/static")
        assertThat(context.resolveAny("plain-value")).isEqualTo("plain-value")
    }

    @Test
    fun `should return native values for exact substitutions in resolveAny`() {
        val context = ExecutionVariableContext()
        context.store("FIXTURE.BEFORE.1.RESPONSE.id", NumberValue(10))
        context.store("FIXTURE.BEFORE.1.RESPONSE.active", BooleanValue(true))

        assertThat(context.resolveAny("$(FIXTURE.BEFORE.1.RESPONSE.id)")).isEqualTo(10)
        assertThat(context.resolveAny("$(FIXTURE.BEFORE.1.RESPONSE.active)")).isEqualTo(true)
    }

    @Test
    fun `should leave non collection values unchanged in resolveAny`() {
        val context = ExecutionVariableContext()

        assertThat(context.resolveAny(10)).isEqualTo(10)
        assertThat(context.resolveAny(true)).isEqualTo(true)
        assertThat(context.resolveAny(null)).isNull()
    }

    @Test
    fun `should fail with a clear error when key is missing`() {
        val context = ExecutionVariableContext()

        val exception = assertThrows<ContractException> {
            context.resolveString("$(FIXTURE.BEFORE.1.RESPONSE.id)")
        }

        assertThat(exception.message).contains("Could not resolve \"FIXTURE.BEFORE.1.RESPONSE.id\"")
    }
}
