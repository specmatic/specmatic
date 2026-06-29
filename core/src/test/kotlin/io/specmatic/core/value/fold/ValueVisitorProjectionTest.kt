package io.specmatic.core.value.fold

import io.specmatic.core.value.Value
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class ValueVisitorProjectionTest {
    @Nested
    inner class GenericCases {
        @Test
        fun `scalar value carries visitor owned context`() {
            val result = TestScalarValue(10).accept(
                visitor = object : ValueVisitor<DepthContext, String> {
                    override val rootContext: DepthContext = DepthContext(level = 0, trail = "root")

                    override fun opaque(case: OpaqueValueCase<out Value, DepthContext>): String = "opaque"

                    override fun scalar(case: ScalarValueCase<out Value, DepthContext>): String {
                        return "${case.nativeValue}@${case.context.trail}"
                    }
                }
            )

            assertThat(result).isEqualTo("10@root")
        }

        @Test
        fun `text case keeps replacement behavior`() {
            val replaced = io.specmatic.core.value.StringValue("hello").accept(
                visitor = object : ValueVisitor<Unit, Value> {
                    override val rootContext: Unit = Unit

                    override fun opaque(case: OpaqueValueCase<out Value, Unit>): Value = case.value

                    override fun text(case: TextValueCase<out Value, Unit>): Value {
                        return case.replaceText(case.text.uppercase())
                    }
                }
            )

            assertThat(replaced).isEqualTo(io.specmatic.core.value.StringValue("HELLO"))
        }
    }

    @Nested
    inner class RawChildAccess {
        @Test
        fun `field object exposes raw children without recursion`() {
            var childVisits = 0
            val value = FakeObjectValue(
                rawFields = listOf(
                    Field("id", CountingValue { childVisits++ }),
                    Field("name", CountingValue { childVisits++ })
                )
            )

            val result = value.accept(object : ValueVisitor<Unit, List<String>> {
                override val rootContext: Unit = Unit

                override fun opaque(case: OpaqueValueCase<out Value, Unit>): List<String> = emptyList()

                override fun fieldObject(case: FieldObjectValueCase<out Value, Unit>): List<String> {
                    return case.fields().map(Field<Value>::name)
                }
            })

            assertThat(result).containsExactly("id", "name")
            assertThat(childVisits).isZero()
        }

        @Test
        fun `indexed list exposes raw children without recursion`() {
            var childVisits = 0
            val value = FakeListValue(
                rawItems = listOf(
                    Item(0, CountingValue { childVisits++ }),
                    Item(1, CountingValue { childVisits++ })
                )
            )

            val result = value.accept(object : ValueVisitor<Unit, List<Int>> {
                override val rootContext: Unit = Unit

                override fun opaque(case: OpaqueValueCase<out Value, Unit>): List<Int> = emptyList()

                override fun indexedList(case: IndexedListValueCase<out Value, Unit>): List<Int> {
                    return case.items().map(Item<Value>::index)
                }
            })

            assertThat(result).containsExactly(0, 1)
            assertThat(childVisits).isZero()
        }
    }

    @Nested
    inner class ProjectionHelpers {
        @Test
        fun `projection helpers keep recursive conversion ergonomic`() {
            val value = FakeObjectValue(
                rawFields = listOf(
                    Field("name", LeafValue("Jane")),
                    Field(
                        "items",
                        FakeListValue(
                            rawItems = listOf(
                                Item(0, LeafValue("a")),
                                Item(1, LeafValue("b"))
                            )
                        )
                    )
                )
            )

            val result = value.accept(object : ValueVisitor<Unit, String> {
                override val rootContext: Unit = Unit

                override fun opaque(case: OpaqueValueCase<out Value, Unit>): String {
                    return case.value.toStringLiteral()
                }

                override fun fieldObject(case: FieldObjectValueCase<out Value, Unit>): String {
                    return case.projectFields(this).joinToString(prefix = "{", postfix = "}") {
                        "${it.name}=${it.value}"
                    }
                }

                override fun indexedList(case: IndexedListValueCase<out Value, Unit>): String {
                    return case.projectItems(this).joinToString(prefix = "[", postfix = "]") {
                        "${it.index}:${it.value}"
                    }
                }
            })

            assertThat(result).isEqualTo("{name=Jane, items=[0:a, 1:b]}")
        }
    }
}
