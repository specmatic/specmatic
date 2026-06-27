package io.specmatic.core.value.fold

import io.specmatic.core.value.Value
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ValueVisitorTraversalControlTest {
    @Test
    fun `visitor can choose which children to traverse`() {
        val value = FakeObjectValue(
            rawFields = listOf(
                Field("keep", LeafValue("yes")),
                Field("skip", CountingValue { throw IllegalStateException("must not visit skipped child") })
            )
        )

        val result = value.accept(object : ValueVisitor<Unit, List<String>> {
            override val rootContext: Unit = Unit

            override fun opaque(case: OpaqueValueCase<out Value, Unit>): List<String> {
                return listOf(case.value.toStringLiteral())
            }

            override fun fieldObject(case: FieldObjectValueCase<out Value, Unit>): List<String> {
                return case.fields().filter { it.name == "keep" }.flatMap { field ->
                    field.value.accept(this).map { valueText -> "${field.name}=$valueText" }
                }
            }
        })

        assertThat(result).containsExactly("keep=yes")
    }
}
