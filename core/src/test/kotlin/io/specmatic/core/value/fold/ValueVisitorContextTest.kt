package io.specmatic.core.value.fold

import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class ValueVisitorContextTest {
    @Nested
    inner class UnitContext {
        @Test
        fun `value can be visited with unit context`() {
            val result = UnknownValue().accept(object : ValueVisitor<Unit, String> {
                override val rootContext: Unit = Unit

                override fun opaque(case: OpaqueValueCase<out Value, Unit>): String {
                    return "opaque:${case.kind}"
                }
            })

            assertThat(result).isEqualTo("opaque:UnknownValue")
        }
    }

    @Nested
    inner class PathAwareContext {
        @Test
        fun `projection helpers derive child path context through visitor hooks`() {
            val value = FakeObjectValue(
                rawFields = listOf(
                    Field("id", LeafValue("10")),
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

            val result = value.accept(PathTraceVisitor())

            assertThat(result).containsExactly(
                "object:[]",
                "field:id",
                "leaf:10:[Field(name=id)]",
                "field:items",
                "list:[Field(name=items)]",
                "item:0",
                "leaf:a:[Field(name=items), Index(index=0)]",
                "item:1",
                "leaf:b:[Field(name=items), Index(index=1)]"
            )
        }

        @Test
        fun `xml attribute and child projection use dedicated xml hooks`() {
            val value = XMLNode(
                realName = "root",
                attributes = mapOf("lang" to StringValue("en")),
                childNodes = listOf(StringValue("hello"))
            )

            val result = value.accept(PathTraceVisitor())

            assertThat(result).containsExactly(
                "xml:[]",
                "attr:lang",
                "leaf:en:[XmlAttribute(name=lang)]",
                "xmlChild:0",
                "leaf:hello:[XmlChild(index=0)]"
            )
        }
    }

    @Nested
    inner class CustomContext {
        @Test
        fun `projection helpers can update non path context`() {
            val value = FakeObjectValue(
                rawFields = listOf(
                    Field(
                        "nested",
                        FakeListValue(
                            rawItems = listOf(Item(0, LeafValue("a")))
                        )
                    )
                )
            )

            val result = value.accept(object : ValueVisitor<DepthContext, List<String>> {
                override val rootContext: DepthContext = DepthContext(level = 0, trail = "root")

                override fun opaque(case: OpaqueValueCase<out Value, DepthContext>): List<String> {
                    return listOf("${case.value.toStringLiteral()}@${case.context.level}:${case.context.trail}")
                }

                override fun fieldObject(case: FieldObjectValueCase<out Value, DepthContext>): List<String> {
                    return listOf("object@${case.context.level}") + case.projectFields(this).flatMap(Field<List<String>>::value)
                }

                override fun indexedList(case: IndexedListValueCase<out Value, DepthContext>): List<String> {
                    return listOf("list@${case.context.level}") + case.projectItems(this).flatMap(Item<List<String>>::value)
                }

                override fun contextForField(currentContext: DepthContext, name: String): DepthContext {
                    return currentContext.child("field:$name")
                }

                override fun contextForIndex(currentContext: DepthContext, index: Int): DepthContext {
                    return currentContext.child("index:$index")
                }
            })

            assertThat(result).containsExactly(
                "object@0",
                "list@1",
                "a@2:index:0"
            )
        }

        @Test
        fun `manual traversal can use contextForOpaque`() {
            val value = FakeObjectValue(
                rawFields = listOf(
                    Field("leaf", UnknownValue())
                )
            )

            val result = value.accept(object : ValueVisitor<DepthContext, List<String>> {
                override val rootContext: DepthContext = DepthContext(level = 0, trail = "root")

                override fun opaque(case: OpaqueValueCase<out Value, DepthContext>): List<String> {
                    return listOf("${case.kind}@${case.context.level}:${case.context.trail}")
                }

                override fun fieldObject(case: FieldObjectValueCase<out Value, DepthContext>): List<String> {
                    return case.fields().flatMap { field ->
                        field.value.accept(this, contextForOpaque(case.context, field.name))
                    }
                }

                override fun contextForOpaque(currentContext: DepthContext, name: String): DepthContext {
                    return currentContext.child("opaque:$name")
                }
            })

            assertThat(result).containsExactly("UnknownValue@1:opaque:leaf")
        }
    }
}
