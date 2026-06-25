package io.specmatic.core.substitution

import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SubstitutionVariableStoreUpdaterTest {
    @Nested
    inner class FromValues {
        @Nested
        inner class ScalarValues {
            @Test
            fun `extracts variable from simple string`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = StringValue("(ID:number)"),
                    runningValue = StringValue("10")
                )

                assertThat(result).isEqualTo(mapOf("ID" to "10"))
            }

            @Test
            fun `extracts non-string running value using toStringLiteral`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = StringValue("(ID:number)"),
                    runningValue = NumberValue(10)
                )

                assertThat(result).isEqualTo(mapOf("ID" to "10"))
            }

            @Test
            fun `ignores non-placeholder original strings`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = StringValue("abc"),
                    runningValue = StringValue("xyz")
                )

                assertThat(result).isEmpty()
            }
        }

        @Nested
        inner class ObjectValues {
            @Test
            fun `extracts nested object variables`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONObjectValue(
                        mapOf(
                            "user" to JSONObjectValue(
                                mapOf("id" to StringValue("(ID:number)"))
                            )
                        )
                    ),
                    runningValue = JSONObjectValue(
                        mapOf(
                            "user" to JSONObjectValue(
                                mapOf("id" to NumberValue(10))
                            )
                        )
                    )
                )

                assertThat(result).isEqualTo(mapOf("ID" to "10"))
            }

            @Test
            fun `skips missing object keys without throwing`() {
                assertThatCode {
                    SubstitutionVariableStoreUpdater.fromValues(
                        originalValue = JSONObjectValue(
                            mapOf("user" to JSONObjectValue(mapOf("id" to StringValue("(ID:number)"))))
                        ),
                        runningValue = JSONObjectValue(emptyMap())
                    )
                }.doesNotThrowAnyException()

                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONObjectValue(
                        mapOf("user" to JSONObjectValue(mapOf("id" to StringValue("(ID:number)"))))
                    ),
                    runningValue = JSONObjectValue(emptyMap())
                )

                assertThat(result).isEmpty()
            }

            @Test
            fun `skips mismatched object shapes without throwing`() {
                assertThatCode {
                    SubstitutionVariableStoreUpdater.fromValues(
                        originalValue = JSONObjectValue(
                            mapOf("user" to JSONObjectValue(mapOf("id" to StringValue("(ID:number)"))))
                        ),
                        runningValue = StringValue("not-an-object")
                    )
                }.doesNotThrowAnyException()

                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONObjectValue(
                        mapOf("user" to JSONObjectValue(mapOf("id" to StringValue("(ID:number)"))))
                    ),
                    runningValue = StringValue("not-an-object")
                )

                assertThat(result).isEmpty()
            }
        }

        @Nested
        inner class ArrayValues {
            @Test
            fun `extracts nested array variables`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONArrayValue(listOf(StringValue("(ID:number)"))),
                    runningValue = JSONArrayValue(listOf(NumberValue(10)))
                )

                assertThat(result).isEqualTo(mapOf("ID" to "10"))
            }

            @Test
            fun `skips mismatched array shapes without throwing`() {
                assertThatCode {
                    SubstitutionVariableStoreUpdater.fromValues(
                        originalValue = JSONArrayValue(listOf(StringValue("(ID:number)"))),
                        runningValue = StringValue("not-an-array")
                    )
                }.doesNotThrowAnyException()

                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONArrayValue(listOf(StringValue("(ID:number)"))),
                    runningValue = StringValue("not-an-array")
                )

                assertThat(result).isEmpty()
            }

            @Test
            fun `handles different array lengths without throwing`() {
                assertThatCode {
                    SubstitutionVariableStoreUpdater.fromValues(
                        originalValue = JSONArrayValue(
                            listOf(
                                StringValue("(FIRST:number)"),
                                StringValue("(SECOND:number)")
                            )
                        ),
                        runningValue = JSONArrayValue(listOf(NumberValue(10)))
                    )
                }.doesNotThrowAnyException()

                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONArrayValue(
                        listOf(
                            StringValue("(FIRST:number)"),
                            StringValue("(SECOND:number)")
                        )
                    ),
                    runningValue = JSONArrayValue(listOf(NumberValue(10)))
                )

                assertThat(result).isEqualTo(mapOf("FIRST" to "10"))
            }
        }

        @Test
        fun `latest extracted value wins within same call`() {
            val result = SubstitutionVariableStoreUpdater.fromValues(
                originalValue = JSONObjectValue(
                    mapOf(
                        "first" to StringValue("(ID:number)"),
                        "second" to StringValue("(ID:number)")
                    )
                ),
                runningValue = JSONObjectValue(
                    mapOf(
                        "first" to NumberValue(10),
                        "second" to NumberValue(20)
                    )
                )
            )

            assertThat(result).isEqualTo(mapOf("ID" to "20"))
        }
    }

    @Nested
    inner class FromMap {
        @Test
        fun `extracts variables from maps`() {
            val result = SubstitutionVariableStoreUpdater.fromMap(
                originalMap = mapOf("X-ID" to "(ID:number)"),
                runningMap = mapOf("X-ID" to "10")
            )

            assertThat(result).isEqualTo(mapOf("ID" to "10"))
        }
    }

    @Nested
    inner class FromPath {
        @Test
        fun `extracts variables from paths`() {
            val result = SubstitutionVariableStoreUpdater.fromPath(
                originalPath = "/orders/(ORDER_ID:number)",
                runningPath = "/orders/123"
            )

            assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123"))
        }
    }
}
