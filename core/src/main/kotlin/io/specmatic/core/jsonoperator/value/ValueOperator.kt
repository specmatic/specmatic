package io.specmatic.core.jsonoperator.value

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.jsonoperator.JsonPointerOperator
import io.specmatic.core.jsonoperator.OperatorCapability
import io.specmatic.core.jsonoperator.RootMutableJsonOperator
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.PathSegment
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

data class ValueOperator(private val value: Value) : RootMutableJsonOperator<Value> {
    override fun get(segments: List<PathSegment>): ReturnValue<Optional<RootMutableJsonOperator<out Value>>> {
        if (segments.isNotEmpty()) return HasFailure("Unexpected remaining segments $segments")
        return HasValue(Optional.Some(this))
    }

    override fun insert(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        return update(segments, value)
    }

    override fun update(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        if (segments.isNotEmpty()) return HasFailure("Unexpected remaining segments $segments")
        return HasValue(copy(value = value))
    }

    override fun delete(segments: List<PathSegment>): ReturnValue<Optional<JsonPointerOperator<Value, OperatorCapability.Mutable>>> {
        if (segments.isNotEmpty()) return HasFailure("Unexpected remaining segments $segments")
        return HasValue(Optional.None)
    }

    override fun finalize(): ReturnValue<Value> = HasValue(value)

    companion object {
        fun <T : Value> from(value: T): RootMutableJsonOperator<T> {
            @Suppress("UNCHECKED_CAST")
            return when (value) {
                is JSONObjectValue -> ObjectValueOperator.from(value)
                is JSONArrayValue -> ArrayValueOperator.from(value)
                else -> ValueOperator(value as Value)
            } as RootMutableJsonOperator<T>
        }

        fun List<PathSegment>.nextDefaultOperator(orElse: RootMutableJsonOperator<out Value> = ObjectValueOperator()): RootMutableJsonOperator<out Value> {
            @Suppress("UNCHECKED_CAST")
            if (this.isEmpty()) return orElse
            return when (this.first()) {
                is PathSegment.Key -> ObjectValueOperator()
                is PathSegment.Index -> ArrayValueOperator()
            } as RootMutableJsonOperator<out Value>
        }
    }
}
