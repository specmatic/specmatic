package io.specmatic.core

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.GherkinSection.When
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.toTabularPattern
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.value.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

internal class HttpRequestKtTest {
    @Test
    fun `json body should end up as a JSONObjectValue object`() {
        val jsonRequest = """
            {
                "method": "POST",
                "path": "/",
                "body": {
                    "a": 1
                }
            }
        """.trimIndent()

        val request = requestFromJSON(jsonStringToValueMap(jsonRequest))
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/")
        assertThat(request.body).isInstanceOf(JSONObjectValue::class.java)

        val body = (request.body as JSONObjectValue).jsonObject

        val valueOfA = body.getValue("a")
        assertThat(valueOfA).isInstanceOf(NumberValue::class.java)
        assertThat(valueOfA.toStringLiteral()).isEqualTo("1")
    }

    @Test
    fun `gherkin clauses from request with number body`() {
        val request = HttpRequest("POST", "/data", emptyMap(), StringValue("10"))
        val (clauses, _) = toGherkinClauses(request)

        assertThat(clauses).hasSize(2)

        assertThat(clauses.first().section).isEqualTo(When)
        assertThat(clauses.first().content).isEqualTo("POST /data")

        assertThat(clauses[1].section).isEqualTo(When)
        assertThat(clauses[1].content).isEqualTo("request-body (RequestBody: string)")
    }

    @Test
    fun `gherkin clauses from request with query`() {
        val request = HttpRequest("GET", "/data", queryParametersMap = mapOf("count" to "1"))
        val (clauses, _) = toGherkinClauses(request)

        assertThat(clauses).hasSize(1)

        assertThat(clauses.first().section).isEqualTo(When)
        assertThat(clauses.first().content).isEqualTo("GET /data?count=(integer)")
    }

    @Test
    fun `gherkin clauses from request with headers`() {
        val request = HttpRequest("POST", "/data", mapOf("X-Custom" to "data"), EmptyString)
        val (clauses, _) = toGherkinClauses(request)

        assertThat(clauses).hasSize(2)

        assertThat(clauses.first().section).isEqualTo(When)
        assertThat(clauses.first().content).isEqualTo("POST /data")

        assertThat(clauses[1].section).isEqualTo(When)
        assertThat(clauses[1].content).isEqualTo("request-header X-Custom (string)")
    }

    @Test
    fun `gherkin clauses from request with form fields`() {
        val request = HttpRequest("POST", "/data", formFields = mapOf("field" to "10"))
        val (clauses, _) = toGherkinClauses(request)

        assertThat(clauses).hasSize(2)

        assertThat(clauses.first().section).isEqualTo(When)
        assertThat(clauses.first().content).isEqualTo("POST /data")

        assertThat(clauses[1].section).isEqualTo(When)
        assertThat(clauses[1].content).isEqualTo("form-field field (integer)")
    }

    @Test
    fun `gherkin clauses from request with form data`() {
        val request = HttpRequest("POST", "/data", multiPartFormData = listOf(MultiPartContentValue("field", NumberValue(20))))
        val (clauses, _) = toGherkinClauses(request)

        assertThat(clauses).hasSize(2)

        assertThat(clauses.first().section).isEqualTo(When)
        assertThat(clauses.first().content).isEqualTo("POST /data")

        assertThat(clauses[1].section).isEqualTo(When)
        assertThat(clauses[1].content).isEqualTo("request-part field (integer)")
    }

    @Test
    fun `query param pattern value should not be added as an example`() {
        val request = HttpRequest(method = "POST", path = "/customer", queryParametersMap = mapOf("key" to "(string)"))

        val (clauses, _, examples) = toGherkinClauses(request)
        assertThat(clauses.first().content).isEqualTo("POST /customer?key=(string)")
        assertThat(examples.examples).isEmpty()
    }

    @Test
    fun `header pattern value should not be added as an example`() {
        val request = HttpRequest(method = "POST", path = "/customer", headers = mapOf("key" to "(string)"))

        val (clauses, _, examples) = toGherkinClauses(request)
        assertThat(clauses[0].content).isEqualTo("POST /customer")
        assertThat(clauses[1].content).isEqualTo("request-header key (string)")
        assertThat(examples.examples).isEmpty()
    }

    @Test
    fun `form field value should not be added as an example`() {
        val request = HttpRequest(method = "POST", path = "/customer", formFields = mapOf("key" to "(string)"))

        val (clauses, _, examples) = toGherkinClauses(request)
        assertThat(clauses[0].content).isEqualTo("POST /customer")
        assertThat(clauses[1].content).isEqualTo("form-field key (string)")
        assertThat(examples.examples).isEmpty()
    }

    @Test
    fun `form data value should not be added as an example`() {
        val request = HttpRequest(method = "POST", path = "/customer", multiPartFormData = listOf(MultiPartContentValue("key", StringValue("(string)"))))

        val (clauses, _, examples) = toGherkinClauses(request)
        assertThat(clauses[0].content).isEqualTo("POST /customer")
        assertThat(clauses[1].content).isEqualTo("request-part key (string)")
        assertThat(examples.examples).isEmpty()
    }

    @Test
    fun `examples of conflicting keys within the request body should be resolved by introducing a new key`() {
        val request = HttpRequest(method = "POST", path = "/customer", body = parsedValue("""{"one": {"key": "1"}, "two": {"key": "2"}}"""))

        val (clauses, types, examples) = toGherkinClauses(request)

        assertThat(clauses).hasSize(2)
        assertThat(clauses).contains(GherkinClause("POST /customer", When))
        assertThat(clauses).contains(GherkinClause("request-body (RequestBody)", When))

        println(types)
        assertThat(types).hasSize(3)
        assertThat(types.getValue("One")).isEqualTo(toTabularPattern(mapOf("key" to DeferredPattern("(string)"))))
        assertThat(types.getValue("Two")).isEqualTo(toTabularPattern(mapOf("key" to DeferredPattern("(key_: string)"))))
        assertThat(types.getValue("RequestBody")).isEqualTo(toTabularPattern(mapOf("one" to DeferredPattern("(One)"), "two" to DeferredPattern("(Two)"))))

        assertThat(examples.examples).hasSize(2)
        assertThat(examples.examples.getValue("key")).isEqualTo("1")
        assertThat(examples.examples.getValue("key_")).isEqualTo("2")
    }

    @Test
    fun `examples of conflicting keys between header and query param should be resolved by introducing a new key`() {
        val request = HttpRequest(method = "POST", path = "/customer", queryParametersMap = mapOf("one" to "one query"), headers = mapOf("one" to "one header"))

        val (clauses, _, examples) = toGherkinClauses(request)

        assertThat(examples.examples).hasSize(2)
        assertThat(examples.examples.getValue("one")).isEqualTo("one query")
        assertThat(examples.examples.getValue("one_")).isEqualTo("one header")

        assertThat(clauses).hasSize(2)
        assertThat(clauses).contains(GherkinClause("POST /customer?one=(string)", When))
        assertThat(clauses).contains(GherkinClause("request-header one (one_: string)", When))
    }

    @Test
    fun `examples of conflicting keys between query param and json body should be resolved by introducing a new key`() {
        val request = HttpRequest(method = "POST", path = "/customer", queryParametersMap = mapOf("one" to "one query"), body = parsedValue("""{"one": "one json"}"""))

        val (clauses, types, examples) = toGherkinClauses(request)

        printToConsole(clauses, examples)

        assertThat(clauses).hasSize(2)
        assertThat(clauses).contains(GherkinClause("POST /customer?one=(string)", When))
        assertThat(clauses).contains(GherkinClause("request-body (RequestBody)", When))

        assertThat(types.getValue("RequestBody")).isEqualTo(toTabularPattern(mapOf("one" to DeferredPattern("(one_: string)"))))

        assertThat(examples.examples).hasSize(2)
        assertThat(examples.examples.getValue("one")).isEqualTo("one query")
        assertThat(examples.examples.getValue("one_")).isEqualTo("one json")
    }

    @Test
    fun `examples of conflicting keys between query param and form fields should be resolved by introducing a new key`() {
        val request = HttpRequest(method = "POST", path = "/customer", queryParametersMap = mapOf("one" to "one query"), formFields = mapOf("one" to "one field"))

        val (clauses, _, examples) = toGherkinClauses(request)

        printToConsole(clauses, examples)

        assertThat(examples.examples).hasSize(2)
        assertThat(examples.examples.getValue("one")).isEqualTo("one query")
        assertThat(examples.examples.getValue("one_")).isEqualTo("one field")

        assertThat(clauses).hasSize(2)
        assertThat(clauses).contains(GherkinClause("POST /customer?one=(string)", When))
        assertThat(clauses).contains(GherkinClause("form-field one (one_: string)", When))
    }

    @Test
    fun `examples of conflicting keys between query param and a request part should be resolved by introducing a new key`() {
        val request = HttpRequest(method = "POST", path = "/customer", queryParametersMap = mapOf("one" to "one query"), multiPartFormData = listOf(MultiPartContentValue(name = "one", content = StringValue("one part"))))

        val (clauses, _, examples) = toGherkinClauses(request)

        printToConsole(clauses, examples)

        assertThat(examples.examples).hasSize(2)
        assertThat(examples.examples.getValue("one")).isEqualTo("one query")
        assertThat(examples.examples.getValue("one_")).isEqualTo("one part")

        assertThat(clauses).hasSize(2)
        assertThat(clauses).contains(GherkinClause("POST /customer?one=(string)", When))
        assertThat(clauses).contains(GherkinClause("request-part one (one_: string)", When))
    }

    @Test
    fun `when generating the request from JSON contentType of multipart form data should be optional`() {
        val requestStubData = parsedJSON("""
        {
            "method": "POST",
            "path": "/",
            "multipart-formdata": [
                {"name": "name", "filename": "@test.csv"}
            ]
        }
        """) as JSONObjectValue

        val request = requestFromJSON(requestStubData.jsonObject)
        assertThat(request.multiPartFormData.single().name).isEqualTo("name")
        val filePart = request.multiPartFormData.single() as MultiPartFileValue
        assertThat(filePart.filename).isEqualTo("test.csv")
    }

    @ParameterizedTest(name = "existing host header ''{0}'' should not be forwarded")
    @ValueSource(strings = ["Host", "host", "HOST"])
    fun `host header should not be forwarded regardless of casing`(existingHeaderName: String) {
        val builder = HttpRequestBuilder().apply {
            url("https://old.example.com")
            headers.append(existingHeaderName, "some.other.host")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).isNull()
        assertThat(builder.url.host).isEqualTo("old.example.com")
    }

    @ParameterizedTest(name = "hop-by-hop header ''{0}'' should not be forwarded")
    @ValueSource(strings = ["Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "TE", "Trailer", "Transfer-Encoding", "Upgrade"])
    fun `hop by hop headers should not be forwarded`(excludedHeaderName: String) {
        val builder = HttpRequestBuilder().apply {
            url("https://old.example.com")
        }

        val httpRequest = HttpRequest(
            path = "/test",
            method = "GET",
            headers = mapOf(excludedHeaderName to "value")
        )
        httpRequest.buildKTORRequest(builder)

        assertThat(builder.headers.getAll(excludedHeaderName)).isNull()
        assertThat(builder.url.host).isEqualTo("old.example.com")
    }

    @Test
    fun `host header should not be set from builder url when no host header exists`() {
        val builder = HttpRequestBuilder().apply {
            url("https://old.example.com")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).isNull()
        assertThat(builder.url.host).isEqualTo("old.example.com")
    }

    @Test
    fun `multiple host headers should be removed before forwarding`() {
        val builder = HttpRequestBuilder().apply {
            url("https://old.example.com")
            headers.append("host", "a.example.com")
            headers.append("Host", "b.example.com")
            headers.append("HOST", "c.example.com")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).isNull()
        assertThat(builder.url.host).isEqualTo("old.example.com")
    }

    @ParameterizedTest(name = "builder url {0} should not force Host header ''{1}''")
    @MethodSource("builderHostCases")
    fun `host header should not be synthesized from builder url host including port and ip`(builderUrl: String, @Suppress("UNUSED_PARAMETER") expectedHost: String) {
        val builder = HttpRequestBuilder().apply {
            url(builderUrl)
            headers.append("Host", "wrong.host")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).isNull()
    }

    companion object {
        @JvmStatic
        fun builderHostCases() = listOf(
            Arguments.of("https://old.example.com", "old.example.com"),
            Arguments.of("https://old.example.com:8443", "old.example.com:8443"),
            Arguments.of("http://localhost", "localhost"),
            Arguments.of("http://127.0.0.1:8080", "127.0.0.1:8080")
        )
    }
}

fun printToConsole(clauses: List<GherkinClause>, exampleDeclarations: ExampleDeclarations) {
    for(clause in clauses) println(clause)
    for(example in exampleDeclarations.examples) println(example)
}
