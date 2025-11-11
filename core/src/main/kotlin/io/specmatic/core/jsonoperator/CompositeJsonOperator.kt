package io.specmatic.core.jsonoperator

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.Value

interface CompositeJsonOperator<T> : RootImmutableJsonOperator<T> {
    val routes: Map<String, RootMutableJsonOperator<out Value>>

    fun copyWithRoute(key: String, operator: RootMutableJsonOperator<out Value>): ReturnValue<RootImmutableJsonOperator<T>>

    override fun get(segments: List<PathSegment>): ReturnValue<Optional<RootMutableJsonOperator<out Value>>> {
        return route(segments) { operator, tail -> operator.get(tail) }
    }

    override fun insert(segments: List<PathSegment>, value: Value): ReturnValue<RootImmutableJsonOperator<T>> {
        return routeAndModify(segments = segments) { operator, tail -> operator.insert(tail, value) }
    }

    override fun update(segments: List<PathSegment>, value: Value): ReturnValue<RootImmutableJsonOperator<T>> {
        return routeAndModify(segments = segments) { operator, tail -> operator.update(tail, value) }
    }

    override fun delete(segments: List<PathSegment>): ReturnValue<Optional<RootImmutableJsonOperator<T>>> {
        if (segments.isEmpty()) return HasValue(Optional.None)
        val (key, tail) = extractTarget(segments).unwrapOrReturn { return it.cast() }
        val operator = routes[key] ?: return HasFailure("Unknown target: $key , must be oneof ${routes.keys.joinToString(separator = ", ")}")
        return operator.delete(tail).ifHasValue { returnValue ->
            returnValue.value.flatMapReturnValue { newOp ->
                copyWithRoute(key, newOp as RootMutableJsonOperator<Value>).ifValue {
                    Optional.Some(it)
                }
            }
        }
    }

    private fun extractTarget(segments: List<PathSegment>): ReturnValue<Pair<String, List<PathSegment>>> {
        if (segments.isEmpty()) return HasFailure("Unexpected Empty segments")
        val nextSegment = segments.takeNextAs<PathSegment.Key>().unwrapOrReturn { return it.cast() }
        return HasValue(nextSegment.key to segments.drop(1))
    }

    private fun route(
        segments: List<PathSegment>,
        operation: (RootMutableJsonOperator<out Value>, List<PathSegment>) -> ReturnValue<Optional<RootMutableJsonOperator<out Value>>>,
    ): ReturnValue<Optional<RootMutableJsonOperator<out Value>>> {
        val (key, tail) = extractTarget(segments).unwrapOrReturn { return it.cast() }
        val operator = routes[key] ?: return HasValue(Optional.None)
        return operation(operator, tail)
    }

    private fun routeAndModify(
        segments: List<PathSegment>,
        operation: (RootMutableJsonOperator<out Value>, List<PathSegment>) -> ReturnValue<RootMutableJsonOperator<out Value>>,
    ): ReturnValue<RootImmutableJsonOperator<T>> {
        val (key, tail) = extractTarget(segments).unwrapOrReturn { return it.cast() }
        val operator = routes[key] ?: return HasFailure("Invalid key $key, must be oneof ${routes.keys.joinToString(separator = ", ")}")
        return operation(operator, tail).ifHasValue { copyWithRoute(key, it.value) }
    }
}
