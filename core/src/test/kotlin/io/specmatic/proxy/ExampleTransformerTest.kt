package io.specmatic.proxy

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExampleTransformerTest {
    @Test
    fun `should return same stub when no transformations are provided`() {
        val stub = ScenarioStub(request = HttpRequest(path = "/hello"), response = HttpResponse(status = 200))
        val transformer = ExampleTransformer.from(emptyMap())
        val result = transformer.applyTo(stub)
        assertThat(result).isEqualTo(stub)
    }

    @Test
    fun `should apply request transformation based on path`() {
        val stub = ScenarioStub(
            request = HttpRequest(path = "/hello", body = JSONObjectValue(mapOf("id" to NumberValue(10)))),
            response = HttpResponse(status = 200),
        )

        val transformer = ExampleTransformer.from(mapOf("http-request/body/id" to ValueTransformer.GeneralizeToType()))
        val result = transformer.applyTo(stub)
        val body = result.request.body as JSONObjectValue
        assertThat(body.jsonObject["id"])
            .isInstanceOf(StringValue::class.java)
            .extracting { (it as StringValue).nativeValue }
            .isEqualTo("(number)")
    }

    @Test
    fun `should apply response transformation based on path`() {
        val stub = ScenarioStub(
            request = HttpRequest(path = "/hello"),
            response = HttpResponse(status = 200, headers = mapOf("Set-Cookie" to "A=1; Max-Age=10")),
        )

        val transformer = ExampleTransformer.from(mapOf("http-response/header/Set-Cookie" to ValueTransformer.CookieExpiryTransformer))
        val result = transformer.applyTo(stub)
        val cookie = result.response.headers["Set-Cookie"]
        assertThat(cookie).isEqualTo("A=1")
    }

    @Test
    fun `should apply transformation to partial stub when stub is partial`() {
        val partial = ScenarioStub(
            request = HttpRequest(path = "/hello", body = JSONObjectValue(mapOf("x" to NumberValue(1)))),
            response = HttpResponse(status = 200),
        )

        val stub = ScenarioStub(request = HttpRequest(path = "/ignore"), response = HttpResponse(status = 404), partial = partial)
        val transformer = ExampleTransformer.from(mapOf("http-request/body/x" to ValueTransformer.GeneralizeToType()))
        val result = transformer.applyTo(stub)
        val body = result.partial!!.request.body as JSONObjectValue
        assertThat(body.jsonObject["x"])
            .isInstanceOf(StringValue::class.java)
            .extracting { (it as StringValue).nativeValue }
            .isEqualTo("(number)")
    }
}
