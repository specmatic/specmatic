package io.specmatic.core.jsonoperator

import io.specmatic.core.HttpResponse
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.jsonoperator.value.ValueOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class ResponseOperator(
    private val originalHttpResponse: HttpResponse,
    private val headersOperator: ObjectValueOperator,
    private val body: RootMutableJsonOperator<out Value>,
) : CompositeJsonOperator<HttpResponse> {
    override val routes: Map<String, RootMutableJsonOperator<out Value>> = buildMap {
        put("header", headersOperator)
        put("statusCode", ValueOperator(NumberValue(originalHttpResponse.status)))
        put("body", body)
    }

    override fun copyWithRoute(key: String, operator: RootMutableJsonOperator<out Value>): ReturnValue<RootImmutableJsonOperator<HttpResponse>> {
        return when(key) {
            "body" -> HasValue(copy(body = operator))
            "header" -> if (operator is ObjectValueOperator) {
                HasValue(copy(headersOperator = operator))
            } else {
                HasFailure("header must stay object")
            }
            else -> HasFailure("Invalid key $key, must be oneof header, body")
        }
    }

    override fun finalize(): ReturnValue<HttpResponse> {
        val finalizedBody = body.finalize().unwrapOrReturn { return it.cast() }
        val finalizedHeaders = headersOperator.finalize().unwrapOrReturn { return it.cast() }

        return HasValue(
            originalHttpResponse.copy(
                headers = finalizedHeaders.jsonObject.mapValues { it.value.toStringLiteral() },
                body = finalizedBody,
            ),
        )
    }

    companion object {
        fun from(response: HttpResponse): ResponseOperator {
            return ResponseOperator(
                originalHttpResponse = response,
                headersOperator = ObjectValueOperator(response.headers.mapValues { StringValue(it.value) }),
                body = ValueOperator.from(response.body),
            )
        }
    }
}
