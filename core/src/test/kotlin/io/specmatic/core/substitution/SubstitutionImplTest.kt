package io.specmatic.core.substitution

import io.specmatic.core.Dictionary
import io.specmatic.core.HttpRequest
import io.specmatic.core.KeyWithPattern
import io.specmatic.core.Resolver
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.JSONArrayPattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SubstitutionImplTest {
    @Test
    fun `factory extracts request variables and preserves substitution behavior`() {
        val runningRequest = HttpRequest(
            method = "POST",
            path = "/users/123/orders/456",
            headers = mapOf("X-Trace" to "trace-1"),
            queryParametersMap = mapOf("page" to "2"),
            body = parsedValue("""{"name":"Ada","profile":{"age":33},"items":[{"code":"X1"}]}""")
        )

        val originalRequest = HttpRequest(
            method = "POST",
            path = "/users/(userId:string)/orders/(orderId:string)",
            headers = mapOf("X-Trace" to "(traceId:string)"),
            queryParametersMap = mapOf("page" to "(pageNumber:string)"),
            body = parsedValue("""{"name":"(name:string)","profile":{"age":"(age:string)"},"items":[{"code":"(code:string)"}]}""")
        )

        val data = JSONObjectValue(
            mapOf(
                "lookupData" to JSONObjectValue(
                    mapOf(
                        "dictionary" to JSONObjectValue(
                            mapOf(
                                "trace-1" to JSONObjectValue(
                                    mapOf(
                                        "message" to StringValue("resolved-from-data"),
                                        "dropMessage" to StringValue("$(drop)")
                                    )
                                ),
                                "*" to JSONObjectValue(
                                    mapOf("message" to StringValue("fallback"))
                                )
                            )
                        )
                    )
                )
            )
        )

        val substitution = SubstitutionImpl.from(
            data = data,
            resolver = Resolver(),
            runningRequest = runningRequest,
            originalRequest = originalRequest,
        )

        assertThat(substitution.isDropDirective(StringValue("no-drop"))).isFalse()
        assertThat(substitution.isDropDirective(StringValue("$(lookupData.dictionary[traceId].dropMessage)"))).isTrue()
        assertThat(substitution.substitute(StringValue("$(age)"), StringPattern()).value).isEqualTo(StringValue("33"))
        assertThat(substitution.substitute(StringValue("$(code)"), StringPattern()).value).isEqualTo(StringValue("X1"))
        assertThat(substitution.substitute(StringValue("$(name)"), StringPattern()).value).isEqualTo(StringValue("Ada"))
        assertThat(substitution.substitute(StringValue("$(userId)"), StringPattern()).value).isEqualTo(StringValue("123"))
        assertThat(substitution.substitute(StringValue("$(orderId)"), StringPattern()).value).isEqualTo(StringValue("456"))
        assertThat(substitution.substitute(StringValue("$(pageNumber)"), StringPattern()).value).isEqualTo(StringValue("2"))
        assertThat(substitution.substitute(StringValue("$(traceId)"), StringPattern()).value).isEqualTo(StringValue("trace-1"))
        assertThat(
            substitution.substitute(StringValue("$(lookupData.dictionary[traceId].message)"), StringPattern()).value
        ).isEqualTo(StringValue("resolved-from-data"))
    }

    @Test
    fun `upsertStoreUsing returns new substitution and keeps original unchanged`() {
        val original = SubstitutionImpl.empty(strictMode = true)
        val updated = original.upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(10)
        )

        assertThat(updated).isNotSameAs(original)
        assertThat(original.substitute(StringValue("$(ID)"), NumberPattern())).isInstanceOf(HasException::class.java)

        val updatedResult = updated.substitute(StringValue("$(ID)"), NumberPattern())
        assertThat(updatedResult).isInstanceOf(HasValue::class.java)
        assertThat((updatedResult as HasValue<*>).value).isEqualTo(NumberValue(10))
    }

    @Test
    fun `upsertStoreUsing preserves existing variables and adds new ones`() {
        val original = SubstitutionImpl.empty().upsertStoreUsing(
            originalValue = StringValue("(FIRST:number)"),
            runningValue = NumberValue(1)
        )

        val updated = original.upsertStoreUsing(
            originalValue = JSONObjectValue(mapOf("second" to StringValue("(SECOND:number)"))),
            runningValue = JSONObjectValue(mapOf("second" to NumberValue(2)))
        )

        assertResolvedValue(original, "$(FIRST)", NumberValue(1))
        assertResolvedValue(updated, "$(FIRST)", NumberValue(1))
        assertResolvedValue(updated, "$(SECOND)", NumberValue(2))
    }

    @Test
    fun `upsertStoreUsing overrides existing variables`() {
        val first = SubstitutionImpl.empty().upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(10)
        )

        val second = first.upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(20)
        )

        assertResolvedValue(second, "$(ID)", NumberValue(20))
    }

    @Test
    fun `upsertStoreUsing keeps data lookup behavior`() {
        val resolver = Resolver()
        val data = JSONObjectValue(
            mapOf(
                "lookupData" to JSONObjectValue(
                    mapOf(
                        "dictionary" to JSONObjectValue(
                            mapOf(
                                "10" to JSONObjectValue(
                                    mapOf("message" to StringValue("resolved-from-data"))
                                ),
                                "*" to JSONObjectValue(
                                    mapOf("message" to StringValue("fallback"))
                                )
                            )
                        )
                    )
                )
            )
        )

        val substitution = SubstitutionImpl.from(
            data = data,
            resolver = resolver,
            runningRequest = HttpRequest(method = "POST", path = "/orders/10"),
            originalRequest = HttpRequest(method = "POST", path = "/orders/(ID:number)")
        ).upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(10)
        )

        assertThat(
            substitution.substitute(StringValue("$(lookupData.dictionary[ID].message)"), StringPattern()).value
        ).isEqualTo(StringValue("resolved-from-data"))
    }

    @Test
    fun `upsertStoreUsing supports interpolated extraction and substitution end to end`() {
        val substitution = SubstitutionImpl.empty()

        val updated = substitution.upsertStoreUsing(
            originalValue = StringValue("order-(ORDER_ID:number)"),
            runningValue = StringValue("order-123")
        )

        val result = updated.substitute(
            value = StringValue("resolved-$(ORDER_ID)"),
            pattern = StringPattern(),
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(StringValue("resolved-123"))
    }

    @Test
    fun `interpolated data lookup resolves end to end`() {
        val resolver = Resolver()
        val data = JSONObjectValue(
            mapOf(
                "lookupData" to JSONObjectValue(
                    mapOf(
                        "statusByType" to JSONObjectValue(
                            mapOf(
                                "A" to JSONObjectValue(
                                    mapOf("status" to StringValue("ready"))
                                )
                            )
                        )
                    )
                )
            )
        )

        val substitution = SubstitutionImpl.from(
            data = data,
            resolver = resolver,
            runningRequest = HttpRequest(method = "POST", path = "/type/A"),
            originalRequest = HttpRequest(method = "POST", path = "/type/(TYPE:string)")
        ).upsertStoreUsing(
            originalValue = StringValue("(TYPE:string)"),
            runningValue = StringValue("A")
        )

        val result = substitution.substitute(
            value = StringValue("status-$(lookupData.statusByType[TYPE].status)"),
            pattern = StringPattern(),
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(StringValue("status-ready"))
    }

    @Test
    fun `interpolated simple lookup resolves whole string as number`() {
        val substitution = SubstitutionImpl.empty().upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(123)
        )

        val result = substitution.substitute(
            value = StringValue("$(ID)"),
            pattern = NumberPattern(),
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(NumberValue(123))
    }

    @Test
    fun `interpolated simple lookup resolves embedded text`() {
        val substitution = SubstitutionImpl.empty().upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(123)
        )

        val result = substitution.substitute(
            value = StringValue("order-$(ID)"),
            pattern = StringPattern(),
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(StringValue("order-123"))
    }

    @Test
    fun `multiple interpolated simple lookups resolve embedded text`() {
        val substitution = SubstitutionImpl.empty()
            .upsertStoreUsing(StringValue("(A:string)"), StringValue("one"))
            .upsertStoreUsing(StringValue("(B:string)"), StringValue("two"))

        val result = substitution.substitute(
            value = StringValue("$(A)-$(B)"),
            pattern = StringPattern(),
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(StringValue("one-two"))
    }

    @Nested
    inner class ValueOnlySubstitution {
        @Test
        fun `whole simple variable returns stored scalar value as is`() {
            val substitution = SubstitutionImpl.empty().upsertStoreUsing(
                originalValue = StringValue("(id:number)"),
                runningValue = NumberValue(10)
            )

            assertThat(substitution.substitute(StringValue("$(id)"))).isEqualTo(HasValue(NumberValue(10)))
        }

        @Test
        fun `whole simple variable returns stored object value as is`() {
            val resolver = Resolver(
                newPatterns = mapOf(
                    "(User)" to JSONObjectPattern(
                        pattern = mapOf(
                            "id" to NumberPattern(),
                            "name" to StringPattern()
                        ),
                        typeAlias = "(User)"
                    )
                )
            )

            val user = JSONObjectValue(
                mapOf(
                    "id" to NumberValue(10),
                    "name" to StringValue("John")
                )
            )

            val substitution = SubstitutionImpl.empty().upsertStoreUsing(
                resolver = resolver,
                originalValue = StringValue("(user:User)"),
                runningValue = StringValue(user.toUnformattedString())
            )

            assertThat(substitution.substitute(StringValue("$(user)"))).isEqualTo(HasValue(user))
        }

        @Test
        fun `interpolated simple variable returns string value`() {
            val substitution = SubstitutionImpl.empty().upsertStoreUsing(
                originalValue = StringValue("(name:string)"),
                runningValue = StringValue("John")
            )

            assertThat(substitution.substitute(StringValue("hello $(name)"))).isEqualTo(HasValue(StringValue("hello John")))
        }

        @Test
        fun `value only path does not parse whole token values`() {
            val resolver = Resolver(
                newPatterns = mapOf(
                    "(Orders)" to JSONArrayPattern(
                        pattern = listOf(NumberPattern(), NumberPattern()),
                        typeAlias = "(Orders)"
                    )
                )
            )

            val orders = JSONArrayValue(listOf(NumberValue(10), NumberValue(20)))
            val substitution = SubstitutionImpl.empty().upsertStoreUsing(
                resolver = resolver,
                originalValue = StringValue("(orders:Orders)"),
                runningValue = StringValue(orders.toUnformattedString())
            )

            val result = substitution.substitute(StringValue("$(orders)"))
            assertThat(result).isEqualTo(HasValue(orders))
        }

        @Test
        fun `missing variable returns exception`() {
            val result = SubstitutionImpl.empty().substitute(StringValue("$(missing)"))
            assertThat(result).isInstanceOf(HasException::class.java)
        }

        @Test
        fun `whole data lookup returns looked up value as is`() {
            val substitution = lookupSubstitution(strictMode = true)
            assertThat(substitution.substitute(StringValue("$(lookupData.dictionary[ID].payload)")))
                .isEqualTo(HasValue(JSONObjectValue(mapOf("id" to NumberValue(10)))))
        }

        @Test
        fun `interpolated data lookup returns string value`() {
            val substitution = lookupSubstitution(strictMode = true)
            assertThat(substitution.substitute(StringValue("hello $(lookupData.dictionary[ID].message)")))
                .isEqualTo(HasValue(StringValue("hello resolved")))
        }

        @Test
        fun `value only resolveIfLookup returns looked up value as is`() {
            val substitution = lookupSubstitution(strictMode = true)
            assertThat(substitution.substitute(StringValue("$(lookupData.dictionary[ID].payload)")).value)
                .isEqualTo(JSONObjectValue(mapOf("id" to NumberValue(10))))
        }

        @Test
        fun `pattern aware substitution still parses through pattern`() {
            val substitution = SubstitutionImpl.empty().upsertStoreUsing(
                originalValue = StringValue("(id:number)"),
                runningValue = NumberValue(10)
            )

            assertThat(substitution.substitute(StringValue("$(id)"), StringPattern()))
                .isEqualTo(HasValue(StringValue("10")))
        }
    }

    @Nested
    inner class LenientMode {
        @Test
        fun `simple variable missing uses pattern generate`() {
            val result = substitution(strictMode = false).substitute(
                value = StringValue("$(MISSING_VAR)"),
                pattern = ExactValuePattern(StringValue("generated")),
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue<*>).value).isEqualTo(StringValue("generated"))
        }

        @Test
        fun `simple variable missing uses dictionary backed generation`() {
            val pattern = JSONObjectPattern(
                typeAlias = "(Object)",
                pattern = mapOf("id" to NumberPattern()),
            )

            val resolver = Resolver(
                newPatterns = mapOf("(Object)" to pattern),
                dictionary = Dictionary.fromYaml("Object: { id: 123456 }")
            )

            val result = substitution(strictMode = false).substitute(
                pattern = pattern,
                resolver = resolver,
                value = StringValue("$(MISSING_VAR)"),
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue<*>).value).isEqualTo(JSONObjectValue(mapOf("id" to NumberValue(123456))))
        }

        @Test
        fun `parse failure falls back to pattern generate`() {
            val result = substitutionWithResolvedVariable(strictMode = false).substitute(
                value = StringValue("$(ID)"),
                pattern = NumberPattern(),
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue<*>).value).isInstanceOf(NumberValue::class.java)
        }

        @Test
        fun `parse failure falls back to dictionary backed generation`() {
            val resolver = Resolver(dictionary = Dictionary.fromYaml("""{Object: {Key: 123456}}"""))
            val updatedResolver = resolver.updateLookupPath("(Object)", KeyWithPattern("Key", NumberPattern()))

            val result = substitutionWithResolvedVariable(strictMode = false).substitute(
                value = StringValue("$(ID)"),
                pattern = NumberPattern(),
                resolver = updatedResolver,
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue<*>).value).isEqualTo(NumberValue(123456))
        }

        @Test
        fun `data lookup missing uses pattern generate`() {
            val result = lookupSubstitution(strictMode = false).substitute(
                value = StringValue("$(lookupData.dictionary[MISSING_VAR].message)"),
                pattern = ExactValuePattern(StringValue("generated")),
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue<*>).value).isEqualTo(StringValue("generated"))
        }

        @Test
        fun `pattern generation should use dictionary if present`() {
            val resolver = Resolver(dictionary = Dictionary.fromYaml("""{Object: {Key: 123456}}"""))
            val updatedResolver = resolver.updateLookupPath("(Object)", KeyWithPattern("Key", NumberPattern()))
            val result = substitution(strictMode = false).substitute(
                pattern = NumberPattern(),
                resolver = updatedResolver,
                value = StringValue("$(MISSING_VAR)"),
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue<*>).value).isEqualTo(NumberValue(123456))
        }

        @Test
        fun `missing variable with key uses resolver dictionary fallback`() {
            val resolver = Resolver(dictionary = Dictionary.fromYaml("""{Object: {Key: 123456}}"""))
            val result = substitution(strictMode = false).substitute(
                key = "Key",
                pattern = NumberPattern(),
                value = StringValue("$(MISSING_VAR)"),
                resolver = resolver.updateLookupPath("(Object)"),
            )

            assertThat(result).isInstanceOf(HasValue::class.java)
            assertThat((result as HasValue<*>).value).isEqualTo(NumberValue(123456))
        }
    }

    @Nested
    inner class StrictMode {
        @Test
        fun `missing simple variable returns exception`() {
            val result = substitution(strictMode = true).substitute(
                value = StringValue("order-$(MISSING)"),
                pattern = StringPattern(),
            )

            assertThat(result).isInstanceOf(HasException::class.java)
        }

        @Test
        fun `missing simple variable in exact pattern returns exception`() {
            val result = substitution(strictMode = true).substitute(
                value = StringValue("$(MISSING_VAR)"),
                pattern = ExactValuePattern(StringValue("generated")),
            )

            assertThat(result).isInstanceOf(HasException::class.java)
            assertThat((result as HasException).t.message).contains("no variable by the name MISSING_VAR")
        }

        @Test
        fun `parse failure returns exception`() {
            val result = substitutionWithResolvedVariable(strictMode = true).substitute(
                value = StringValue("$(ID)"),
                pattern = NumberPattern(),
            )

            assertThat(result).isInstanceOf(HasException::class.java)
        }

        @Test
        fun `data lookup missing returns exception`() {
            val result = lookupSubstitution(strictMode = true).substitute(
                value = StringValue("$(lookupData.dictionary[MISSING_VAR].message)"),
                pattern = ExactValuePattern(StringValue("generated")),
            )

            assertThat(result).isInstanceOf(HasException::class.java)
        }

        @Test
        fun `upsertStoreUsing preserves strict mode`() {
            val substitution = SubstitutionImpl.empty(strictMode = true).upsertStoreUsing(
                originalValue = StringValue("(ID:number)"),
                runningValue = NumberValue(10)
            )

            val result = substitution.substitute(
                value = StringValue("$(MISSING_VAR)"),
                pattern = ExactValuePattern(StringValue("generated")),
            )

            assertThat(result).isInstanceOf(HasException::class.java)
        }
    }

    private fun assertResolvedValue(substitution: Substitution, lookup: String, expectedValue: NumberValue) {
        val result = substitution.substitute(StringValue(lookup), NumberPattern())
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(expectedValue)
    }

    private fun substitution(strictMode: Boolean): SubstitutionImpl {
        return SubstitutionImpl.empty(strictMode = strictMode)
    }

    private fun substitutionWithResolvedVariable(strictMode: Boolean): SubstitutionImpl {
        return SubstitutionImpl.empty(strictMode = strictMode).upsertStoreUsing(
            originalValue = StringValue("(ID:string)"),
            runningValue = StringValue("not-a-number")
        )
    }

    private fun lookupSubstitution(strictMode: Boolean): SubstitutionImpl = SubstitutionImpl.from(
        resolver = Resolver(),
        strictMode = strictMode,
        runningRequest = HttpRequest(method = "POST", path = "/orders/1"),
        originalRequest = HttpRequest(method = "POST", path = "/orders/(ID:string)"),
        data = JSONObjectValue(
            mapOf(
                "lookupData" to JSONObjectValue(
                    mapOf(
                        "dictionary" to JSONObjectValue(
                            mapOf(
                                "1" to JSONObjectValue(
                                    mapOf(
                                        "message" to StringValue("resolved"),
                                        "payload" to JSONObjectValue(mapOf("id" to NumberValue(10)))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
    )
}
