package io.specmatic.core.jsonoperator

import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.QueryParameters
import io.specmatic.core.Resolver
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.jsonoperator.value.ValueOperator
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class RequestOperator(
    private val originalHttpRequest: HttpRequest,
    private val pathOperator: ObjectValueOperator,
    private val queryOperator: ObjectValueOperator,
    private val headerOperator: ObjectValueOperator,
    private val body: RootMutableJsonOperator<out Value>,
) : CompositeJsonOperator<HttpRequest> {
    override val routes: Map<String, RootMutableJsonOperator<out Value>> = buildMap {
        put("body", body)
        put("path", pathOperator)
        put("query", queryOperator)
        put("header", headerOperator)
        put("url", ValueOperator(StringValue(originalHttpRequest.path.orEmpty())))
    }

    override fun copyWithRoute(key: String, operator: RootMutableJsonOperator<out Value>): ReturnValue<RootImmutableJsonOperator<HttpRequest>> {
        return when (key) {
            "body" -> HasValue(copy(body = operator))
            "path" -> copyIfObjectOperator(key, operator) { copy(pathOperator = it) }
            "query" -> copyIfObjectOperator(key, operator) { copy(queryOperator = it) }
            "header" -> copyIfObjectOperator(key, operator) { copy(headerOperator = it) }
            else -> HasFailure("Unexpected key $key, must be one of header, body, path, query")
        }
    }

    private fun copyIfObjectOperator(key: String, operator: RootMutableJsonOperator<out Value>, copyOn: (ObjectValueOperator) -> RequestOperator): ReturnValue<RootImmutableJsonOperator<HttpRequest>> {
        if (operator !is ObjectValueOperator) return HasFailure("$key must stay object")
        return HasValue(copyOn(operator))
    }

    override fun finalize(): ReturnValue<HttpRequest> {
        val finalizedPathValues = pathOperator.finalize().unwrapOrReturn { return it.cast() }
        val finalizedHeaders = headerOperator.finalize().unwrapOrReturn { return it.cast() }
        val finalizedQuery = queryOperator.finalize().unwrapOrReturn { return it.cast() }
        val finalizedBody = body.finalize().unwrapOrReturn { return it.cast() }

        return HasValue(
            originalHttpRequest.copy(
                path = finalizedPathValues.jsonObject.values.joinToString(separator = "/"),
                queryParams = QueryParameters(finalizedQuery.jsonObject.mapValues { it.value.toStringLiteral() }),
                headers = finalizedHeaders.jsonObject.mapValues { it.value.toStringLiteral() },
                body = finalizedBody,
            ),
        )
    }

    companion object {
        fun from(request: HttpRequest, requestPattern: HttpRequestPattern, resolver: Resolver): RequestOperator {
            val pathOperator = if (requestPattern.httpPathPattern != null) {
                pathParametersFrom(request, requestPattern.httpPathPattern, resolver)
            } else {
                ObjectValueOperator()
            }

            return RequestOperator(
                originalHttpRequest = request,
                pathOperator = pathOperator,
                queryOperator = ObjectValueOperator(request.queryParams.asValueMap()),
                headerOperator = ObjectValueOperator(request.headers.mapValues { StringValue(it.value) }, caseInsensitive = true),
                body = ValueOperator.from(request.body),
            )
        }

        fun from(request: HttpRequest, pathPattern: HttpPathPattern, resolver: Resolver): RequestOperator {
            return RequestOperator(
                originalHttpRequest = request,
                pathOperator = pathParametersFrom(request, pathPattern, resolver),
                queryOperator = ObjectValueOperator(request.queryParams.asValueMap()),
                headerOperator = ObjectValueOperator(request.headers.mapValues { StringValue(it.value) }, caseInsensitive = true),
                body = ValueOperator.from(request.body),
            )
        }

        private fun pathParametersFrom(request: HttpRequest, pathPattern: HttpPathPattern, resolver: Resolver): ObjectValueOperator {
            val pathSegments = request.path?.trim('/')?.split("/")?.filter(String::isNotEmpty).orEmpty()
            val map = pathPattern.pathSegmentPatterns.withIndex().zip(pathSegments) { (idx, pattern), segment ->
                if (pattern.pattern is ExactValuePattern || pattern.key == null) return@zip idx.toString() to StringValue(segment)
                pattern.key to runCatching { pattern.parse(segment, resolver) }.getOrElse { StringValue(segment) }
            }.toMap()
            return ObjectValueOperator(map)
        }
    }
}
