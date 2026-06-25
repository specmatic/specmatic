package io.specmatic.core.substitution

import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
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
            fun `extracts interpolated nested object variables`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONObjectValue(
                        mapOf(
                            "order" to StringValue("order-(ORDER_ID:number)")
                        )
                    ),
                    runningValue = JSONObjectValue(
                        mapOf(
                            "order" to StringValue("order-123")
                        )
                    )
                )

                assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123"))
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
            fun `extracts interpolated nested array variables`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = JSONArrayValue(listOf(StringValue("order-(ORDER_ID:number)"))),
                    runningValue = JSONArrayValue(listOf(StringValue("order-123")))
                )

                assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123"))
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

        @Nested
        inner class InterpolatedValues {
            @Test
            fun `extracts interpolated placeholder from simple string`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = StringValue("order-(ORDER_ID:number)"),
                    runningValue = StringValue("order-123")
                )

                assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123"))
            }

            @Test
            fun `extracts multiple interpolated placeholders from simple string`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = StringValue("order-(ORDER_ID:number)-item-(ITEM_ID:number)"),
                    runningValue = StringValue("order-123-item-456")
                )

                assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123", "ITEM_ID" to "456"))
            }

            @Test
            fun `fails when interpolated string does not match`() {
                assertThatThrownBy {
                    SubstitutionVariableStoreUpdater.fromValues(
                        originalValue = StringValue("prefix-(ID:number)-suffix"),
                        runningValue = StringValue("does-not-match")
                    )
                }.isInstanceOf(ContractException::class.java)
                    .hasMessageContaining("Could not extract substitution variables")
            }

            @Test
            fun `returns empty map when original has no placeholder`() {
                val result = SubstitutionVariableStoreUpdater.fromValues(
                    originalValue = StringValue("prefix-abc-suffix"),
                    runningValue = StringValue("prefix-123-suffix")
                )

                assertThat(result).isEmpty()
            }

            @Test
            fun `fails for adjacent placeholders`() {
                assertThatThrownBy {
                    SubstitutionVariableStoreUpdater.fromValues(
                        originalValue = StringValue("(A:string)(B:string)"),
                        runningValue = StringValue("onetwo")
                    )
                }.isInstanceOf(ContractException::class.java)
                    .hasMessageContaining("Ambiguous interpolation")
            }

            @Test
            fun `fails on conflicting duplicate interpolated values`() {
                assertThatThrownBy {
                    SubstitutionVariableStoreUpdater.fromValues(
                        originalValue = StringValue("(ID:number)-again-(ID:number)"),
                        runningValue = StringValue("10-again-20")
                    )
                }.isInstanceOf(ContractException::class.java)
                    .hasMessageContaining("Conflicting extracted values")
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

        @Test
        fun `extracts interpolated variables from maps`() {
            val result = SubstitutionVariableStoreUpdater.fromMap(
                originalMap = mapOf("X-ID" to "order-(ID:number)"),
                runningMap = mapOf("X-ID" to "order-10")
            )

            assertThat(result).isEqualTo(mapOf("ID" to "10"))
        }

        @Test
        fun `fails on adjacent placeholders in maps`() {
            assertThatThrownBy {
                SubstitutionVariableStoreUpdater.fromMap(
                    originalMap = mapOf("X-ID" to "(A:string)(B:string)"),
                    runningMap = mapOf("X-ID" to "onetwo")
                )
            }.isInstanceOf(ContractException::class.java)
                .hasMessageContaining("Ambiguous interpolation")
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

        @Test
        fun `extracts interpolated variables from paths`() {
            val result = SubstitutionVariableStoreUpdater.fromPath(
                originalPath = "/orders/order-(ORDER_ID:number)",
                runningPath = "/orders/order-123"
            )

            assertThat(result).isEqualTo(mapOf("ORDER_ID" to "123"))
        }

        @Test
        fun `fails on adjacent placeholders in paths`() {
            assertThatThrownBy {
                SubstitutionVariableStoreUpdater.fromPath(
                    originalPath = "/orders/(A:string)(B:string)",
                    runningPath = "/orders/onetwo"
                )
            }.isInstanceOf(ContractException::class.java)
                .hasMessageContaining("Ambiguous interpolation")
        }
    }
}
