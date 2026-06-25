package io.specmatic.core.substitution

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
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
        assertThat(substitution.substitute(StringValue("$(age)"), StringPattern(), null).value).isEqualTo(StringValue("33"))
        assertThat(substitution.substitute(StringValue("$(code)"), StringPattern(), null).value).isEqualTo(StringValue("X1"))
        assertThat(substitution.substitute(StringValue("$(name)"), StringPattern(), null).value).isEqualTo(StringValue("Ada"))
        assertThat(substitution.substitute(StringValue("$(userId)"), StringPattern(), null).value).isEqualTo(StringValue("123"))
        assertThat(substitution.substitute(StringValue("$(orderId)"), StringPattern(), null).value).isEqualTo(StringValue("456"))
        assertThat(substitution.substitute(StringValue("$(pageNumber)"), StringPattern(), null).value).isEqualTo(StringValue("2"))
        assertThat(substitution.substitute(StringValue("$(traceId)"), StringPattern(), null).value).isEqualTo(StringValue("trace-1"))
        assertThat(
            substitution.resolveIfLookup(StringValue("$(lookupData.dictionary[traceId].message)"), StringPattern())
        ).isEqualTo(StringValue("resolved-from-data"))
    }

    @Test
    fun `upsertStoreUsing returns new substitution and keeps original unchanged`() {
        val resolver = Resolver()
        val original = SubstitutionImpl.empty(resolver)

        val updated = original.upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(10)
        )

        assertThat(updated).isNotSameAs(original)
        assertThat(original.substitute(StringValue("$(ID)"), NumberPattern(), null)).isInstanceOf(HasException::class.java)

        val updatedResult = updated.substitute(StringValue("$(ID)"), NumberPattern(), null)
        assertThat(updatedResult).isInstanceOf(HasValue::class.java)
        assertThat((updatedResult as HasValue<*>).value).isEqualTo(NumberValue(10))
    }

    @Test
    fun `upsertStoreUsing preserves existing variables and adds new ones`() {
        val resolver = Resolver()
        val original = SubstitutionImpl.empty(resolver).upsertStoreUsing(
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
        val resolver = Resolver()
        val first = SubstitutionImpl.empty(resolver).upsertStoreUsing(
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
            substitution.resolveIfLookup(StringValue("$(lookupData.dictionary[ID].message)"), StringPattern())
        ).isEqualTo(StringValue("resolved-from-data"))
    }

    @Test
    fun `upsertStoreUsing supports interpolated extraction and substitution end to end`() {
        val resolver = Resolver()
        val substitution = SubstitutionImpl.empty(resolver)

        val updated = substitution.upsertStoreUsing(
            originalValue = StringValue("order-(ORDER_ID:number)"),
            runningValue = StringValue("order-123")
        )

        val result = updated.substitute(
            value = StringValue("resolved-$(ORDER_ID)"),
            pattern = StringPattern(),
            key = null
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
            key = null
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(StringValue("status-ready"))
    }

    @Test
    fun `interpolated simple lookup resolves whole string as number`() {
        val resolver = Resolver()
        val substitution = SubstitutionImpl.empty(resolver).upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(123)
        )

        val result = substitution.substitute(
            value = StringValue("$(ID)"),
            pattern = NumberPattern(),
            key = null
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(NumberValue(123))
    }

    @Test
    fun `interpolated simple lookup resolves embedded text`() {
        val substitution = SubstitutionImpl.empty(Resolver()).upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(123)
        )

        val result = substitution.substitute(
            value = StringValue("order-$(ID)"),
            pattern = StringPattern(),
            key = null
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(StringValue("order-123"))
    }

    @Test
    fun `multiple interpolated simple lookups resolve embedded text`() {
        val substitution = SubstitutionImpl.empty(Resolver())
            .upsertStoreUsing(StringValue("(A:string)"), StringValue("one"))
            .upsertStoreUsing(StringValue("(B:string)"), StringValue("two"))

        val result = substitution.substitute(
            value = StringValue("$(A)-$(B)"),
            pattern = StringPattern(),
            key = null
        )

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(StringValue("one-two"))
    }

    @Test
    fun `interpolated lookup returns exception when missing`() {
        val result = SubstitutionImpl.empty(Resolver()).substitute(
            value = StringValue("order-$(MISSING)"),
            pattern = StringPattern(),
            key = null
        )

        assertThat(result).isInstanceOf(HasException::class.java)
    }

    private fun assertResolvedValue(substitution: Substitution, lookup: String, expectedValue: NumberValue) {
        val result = substitution.substitute(StringValue(lookup), NumberPattern(), null)
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue<*>).value).isEqualTo(expectedValue)
    }
}
