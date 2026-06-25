package io.specmatic.core.substitution

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
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

        assertFalse(substitution.isDropDirective(StringValue("no-drop")))
        assertTrue(substitution.isDropDirective(StringValue("$(lookupData.dictionary[traceId].dropMessage)")))
        assertEquals(StringValue("33"), substitution.substitute(StringValue("$(age)"), StringPattern(), null).value)
        assertEquals(StringValue("X1"), substitution.substitute(StringValue("$(code)"), StringPattern(), null).value)
        assertEquals(StringValue("Ada"), substitution.substitute(StringValue("$(name)"), StringPattern(), null).value)
        assertEquals(StringValue("123"), substitution.substitute(StringValue("$(userId)"), StringPattern(), null).value)
        assertEquals(StringValue("456"), substitution.substitute(StringValue("$(orderId)"), StringPattern(), null).value)
        assertEquals(StringValue("2"), substitution.substitute(StringValue("$(pageNumber)"), StringPattern(), null).value)
        assertEquals(StringValue("trace-1"), substitution.substitute(StringValue("$(traceId)"), StringPattern(), null).value)
        assertEquals(
            StringValue("resolved-from-data"),
            substitution.resolveIfLookup(StringValue("$(lookupData.dictionary[traceId].message)"), StringPattern())
        )
    }

    @Test
    fun `upsertStoreUsing returns new substitution and keeps original unchanged`() {
        val resolver = Resolver()
        val original = SubstitutionImpl.empty(resolver)

        val updated = original.upsertStoreUsing(
            originalValue = StringValue("(ID:number)"),
            runningValue = NumberValue(10)
        )

        assertNotSame(original, updated)
        assertTrue(original.substitute(StringValue("$(ID)"), StringPattern(), null) is HasException)

        val updatedResult = updated.substitute(StringValue("$(ID)"), StringPattern(), null)
        assertTrue(updatedResult is HasValue<*>)
        assertEquals(StringValue("10"), (updatedResult as HasValue<*>).value)
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

        assertEquals(
            StringValue("resolved-from-data"),
            substitution.resolveIfLookup(StringValue("$(lookupData.dictionary[ID].message)"), StringPattern())
        )
    }

    private fun assertResolvedValue(substitution: Substitution, lookup: String, patternValue: NumberValue) {
        val result = substitution.substitute(StringValue(lookup), StringPattern(), null)

        assertTrue(result is HasValue<*>)
        assertEquals(StringValue(patternValue.number.toString()), (result as HasValue<*>).value)
    }
}
