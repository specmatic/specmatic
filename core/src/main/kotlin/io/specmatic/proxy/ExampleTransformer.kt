package io.specmatic.proxy

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Resolver
import io.specmatic.core.jsonoperator.PathSegment
import io.specmatic.core.jsonoperator.RequestOperator
import io.specmatic.core.jsonoperator.ResponseOperator
import io.specmatic.core.jsonoperator.RootImmutableJsonOperator
import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.pattern.mapFoldKeys
import io.specmatic.core.pattern.withPatternDelimiters
import io.specmatic.core.utilities.OpenApiPath
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.MOCK_HTTP_REQUEST
import io.specmatic.mock.MOCK_HTTP_RESPONSE
import io.specmatic.mock.ScenarioStub

private typealias Path = List<PathSegment>

interface ValueTransformer {
    fun transform(value: Value?): Value?

    data class GeneralizeToType(private val valueToType: (Value) -> String = defaultValueToTypeConverter): ValueTransformer {
        override fun transform(value: Value?): Value? {
            if (value == null) return null
            return StringValue(valueToType(value))
        }

        companion object {
            private val defaultValueToTypeConverter: (Value) -> String = {
                when (it) {
                    is ScalarValue -> withPatternDelimiters(it.type().typeName)
                    else -> "(anyvalue)"
                }
            }
        }
    }

    data object CookieExpiryTransformer: ValueTransformer {
        override fun transform(value: Value?): Value? {
            if (value !is StringValue) return value
            val transformed = value.nativeValue.split("\u0000").joinToString("\u0000", transform = ::stripExpiryAttributes)
            return StringValue(transformed)
        }

        private fun stripExpiryAttributes(cookie: String): String {
            return cookie.split(";").map { it.trim() }.filterNot(::isExpiryAttribute).joinToString("; ")
        }

        private fun isExpiryAttribute(attribute: String): Boolean {
            val lower = attribute.lowercase()
            return lower.startsWith("expires=") || lower.startsWith("max-age=")
        }
    }
}

class ExampleTransformer(private val transformations: Map<Path, ValueTransformer>) {
    fun applyTo(scenarioStub: ScenarioStub): ScenarioStub {
        if (scenarioStub.partial != null && scenarioStub.isPartial()) {
            return scenarioStub.copy(partial = applyTo(scenarioStub.partial))
        }

        val updatedRequest = scenarioStub.request.apply(transformations.filterMatching(
            predicate = { it.firstOrNull()?.toString() == MOCK_HTTP_REQUEST },
            then = {  it.drop(1) }
        ))

        val updatedResponse = scenarioStub.response.apply(transformations.filterMatching(
            predicate = { it.firstOrNull()?.toString() == MOCK_HTTP_RESPONSE },
            then = {  it.drop(1) }
        ))

        return scenarioStub.copy(request = updatedRequest, response = updatedResponse)
    }

    private fun HttpRequest.apply(transformations: Map<Path, ValueTransformer>): HttpRequest {
        if (transformations.isEmpty()) return this
        val pathPattern = OpenApiPath.from(this.path.orEmpty()).normalize().toHttpPathPattern(path.orEmpty())
        val requestOperator = RequestOperator.from(this, pathPattern, Resolver())
        return requestOperator.apply(transformations).finalize().withDefault(this) { it }.ifBlankPathUsePath(path.orEmpty())
    }

    private fun HttpResponse.apply(transformations: Map<Path, ValueTransformer>): HttpResponse {
        if (transformations.isEmpty()) return this
        val responseOperator = ResponseOperator.from(this)
        return responseOperator.apply(transformations).finalize().withDefault(this) { it }
    }

    private fun <V> RootImmutableJsonOperator<V>.apply(transformations: Map<Path, ValueTransformer>): RootImmutableJsonOperator<V> {
        if (transformations.isEmpty()) return this
        return transformations.entries.fold(this) { acc, (path, transformation) ->
            val value = acc.get(path).finalizeValue().withDefault(null) { it }?.getOrNull()
            val newValue = transformation.transform(value) ?: return acc
            acc.upsert(path, newValue).withDefault(acc) { it }
        }
    }

    private fun <T, U> Map<Path, T>.filterMatching(predicate: (List<PathSegment>) -> Boolean, then: (List<PathSegment>) -> U): Map<U, T> {
        return this.filter { predicate(it.key) }.mapKeys { then(it.key) }
    }

    companion object {
        fun from(transformations: Map<String, ValueTransformer>): ExampleTransformer {
            val parsedPaths = transformations.mapKeys { PathSegment.parsePath(it.key) }
            return ExampleTransformer(transformations = parsedPaths.mapFoldKeys().value)
        }
    }
}
