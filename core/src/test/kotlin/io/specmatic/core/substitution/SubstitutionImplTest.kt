package io.specmatic.core.substitution

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
