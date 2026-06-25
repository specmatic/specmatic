package io.specmatic.core.substitution

import io.specmatic.core.HttpRequest
import io.specmatic.core.pattern.parsedValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubstitutionVariableExtractorTest {
    @Test
    fun `fromRequest extracts variables from all request parts`() {
        val runningRequest = HttpRequest(
            method = "POST",
            path = "/orders/123",
            headers = mapOf("X-Trace" to "trace-1", "X-Request-Id" to "run-header"),
            queryParametersMap = mapOf("page" to "2"),
            body = parsedValue("""{"name":"Ada","profile":{"age":33}}""")
        )

        val originalRequest = HttpRequest(
            method = "POST",
            path = "/orders/(orderId:string)",
            headers = mapOf("X-Trace" to "(traceId:string)", "X-Request-Id" to "(requestId:string)"),
            queryParametersMap = mapOf("page" to "(pageNumber:string)"),
            body = parsedValue("""{"name":"(name:string)","profile":{"age":"(age:string)"}}""")
        )

        val result = SubstitutionVariableExtractor.fromRequest(runningRequest, originalRequest)
        assertEquals(
            mapOf(
                "traceId" to "trace-1",
                "requestId" to "run-header",
                "name" to "Ada",
                "age" to "33",
                "pageNumber" to "2",
                "orderId" to "123"
            ),
            result
        )
    }

    @Test
    fun `fromRequest retains precedence`() {
        val runningRequest = HttpRequest(
            method = "POST",
            path = "/users/path-value",
            headers = mapOf("X-Value" to "header-value"),
            queryParametersMap = mapOf("value" to "query-value"),
            body = parsedValue("""{"value":"body-value"}""")
        )

        val originalRequest = HttpRequest(
            method = "POST",
            path = "/users/(value:string)",
            headers = mapOf("X-Value" to "(value:string)"),
            queryParametersMap = mapOf("value" to "(value:string)"),
            body = parsedValue("""{"value":"(value:string)"}""")
        )

        val result = SubstitutionVariableExtractor.fromRequest(runningRequest, originalRequest)
        assertEquals(mapOf("value" to "path-value"), result)
    }
}
