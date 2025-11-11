package io.specmatic.core.jsonoperator

import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.Value

sealed interface OperatorCapability {
    object Mutable : OperatorCapability
    object Immutable : OperatorCapability
}

interface JsonPointerOperator<V, Cap : OperatorCapability> {
    fun get(segments: List<PathSegment>): ReturnValue<Optional<RootMutableJsonOperator<out Value>>>
    fun finalize(): ReturnValue<V>

    fun get(pointer: String): ReturnValue<Optional<RootMutableJsonOperator<out Value>>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return get(segments)
    }
}

interface RootMutableJsonOperator<V> : JsonPointerOperator<V, OperatorCapability.Mutable> {
    fun insert(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>>
    fun update(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>>
    fun delete(segments: List<PathSegment>): ReturnValue<Optional<JsonPointerOperator<V, OperatorCapability.Mutable>>>

    fun insert(pointer: String, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return insert(segments, value)
    }

    fun update(pointer: String, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return update(segments, value)
    }

    fun upsert(pointer: String, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return upsert(segments, value)
    }

    fun upsert(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        return update(segments, value).realise(
            hasValue = { it, _ -> HasValue(it) },
            orFailure = { _ -> insert(segments, value) },
            orException = { it },
        )
    }

    fun delete(pointer: String): ReturnValue<Optional<JsonPointerOperator<V, OperatorCapability.Mutable>>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return delete(segments)
    }

    companion object {
        fun ReturnValue<Optional<RootMutableJsonOperator<out Value>>>.finalizeValue(): ReturnValue<Optional<Value>> {
            val optional = this.unwrapOrReturn { return it.cast() }
            val operator = optional.getOrNull() ?: return HasValue(Optional.None)
            return operator.finalize().ifValue { Optional.Some(it) }
        }
    }
}

interface RootImmutableJsonOperator<V> : JsonPointerOperator<V, OperatorCapability.Immutable> {
    fun insert(segments: List<PathSegment>, value: Value): ReturnValue<RootImmutableJsonOperator<V>>
    fun update(segments: List<PathSegment>, value: Value): ReturnValue<RootImmutableJsonOperator<V>>
    fun delete(segments: List<PathSegment>): ReturnValue<Optional<RootImmutableJsonOperator<V>>>

    fun insert(pointer: String, value: Value): ReturnValue<RootImmutableJsonOperator<V>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return insert(segments, value)
    }

    fun update(pointer: String, value: Value): ReturnValue<RootImmutableJsonOperator<V>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return update(segments, value)
    }

    fun upsert(pointer: String, value: Value): ReturnValue<RootImmutableJsonOperator<V>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return upsert(segments, value)
    }

    fun upsert(segments: List<PathSegment>, value: Value): ReturnValue<RootImmutableJsonOperator<V>> {
        return update(segments, value).realise(
            hasValue = { it, _ -> HasValue(it) },
            orFailure = { _ -> insert(segments, value) },
            orException = { it },
        )
    }

    fun delete(pointer: String): ReturnValue<Optional<RootImmutableJsonOperator<V>>> {
        val segments = PathSegment.parsePath(pointer).unwrapOrReturn { return it.cast() }
        return delete(segments)
    }
}
