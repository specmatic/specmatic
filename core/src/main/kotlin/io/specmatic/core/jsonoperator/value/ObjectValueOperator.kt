package io.specmatic.core.jsonoperator.value

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.jsonoperator.JsonPointerOperator
import io.specmatic.core.jsonoperator.OperatorCapability
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.PathSegment
import io.specmatic.core.jsonoperator.RootMutableJsonOperator
import io.specmatic.core.jsonoperator.takeNextAs
import io.specmatic.core.jsonoperator.value.ValueOperator.Companion.nextDefaultOperator
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

data class ObjectValueOperator(private val map: Map<String, Value> = emptyMap()) : RootMutableJsonOperator<JSONObjectValue> {
    override fun get(segments: List<PathSegment>): ReturnValue<Optional<RootMutableJsonOperator<out Value>>> {
        if (segments.isEmpty()) return HasValue(Optional.Some(this))

        val headSegment = segments.takeNextAs<PathSegment.Key>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)

        val headSegmentValue = map[headSegment.key] ?: return HasValue(Optional.None)
        val headSegmentOperator = ValueOperator.from(headSegmentValue)
        return headSegmentOperator.get(tailSegments)
    }

    override fun insert(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        if (segments.isEmpty()) return HasValue(ValueOperator.from(value))
        return modifyNested(segments, allowMissing = true) { operator, tail -> operator.insert(tail, value) }
    }

    override fun update(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        if (segments.isEmpty()) return HasValue(ValueOperator.from(value))
        return modifyNested(segments, allowMissing = false) { operator, tail -> operator.update(tail, value) }
    }

    override fun delete(segments: List<PathSegment>): ReturnValue<Optional<JsonPointerOperator<JSONObjectValue, OperatorCapability.Mutable>>> {
        if (segments.isEmpty()) return HasValue(Optional.None)

        val headSegment = segments.takeNextAs<PathSegment.Key>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)
        val headSegmentValue = map[headSegment.key] ?: return HasFailure("Key ${headSegment.key} not found", headSegment.parsedPath)

        if (tailSegments.isEmpty()) {
            val updatedMap = map.minus(headSegment.key)
            return HasValue(Optional.Some(copy(map = updatedMap)))
        }

        val headSegmentOperator = ValueOperator.from(headSegmentValue)
        return headSegmentOperator.delete(tailSegments).ifHasValue { returnValue ->
            returnValue.value.flatMapReturnValue { newValueOperator ->
                newValueOperator.finalize().ifHasValue { newValue ->
                    val updatedMap = map.plus(headSegment.key to newValue.value)
                    HasValue(Optional.Some(copy(map = updatedMap)))
                }
            }
        }
    }

    override fun finalize(): ReturnValue<JSONObjectValue> = HasValue(JSONObjectValue(map))

    private fun modifyNested(
        segments: List<PathSegment>,
        allowMissing: Boolean,
        operation: (RootMutableJsonOperator<out Value>, List<PathSegment>) -> ReturnValue<RootMutableJsonOperator<out Value>>,
    ): ReturnValue<RootMutableJsonOperator<out Value>> {
        val headSegment = segments.takeNextAs<PathSegment.Key>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)

        val headSegmentOperator = when {
            allowMissing -> map[headSegment.key]?.let(ValueOperator::from) ?: tailSegments.nextDefaultOperator()
            else -> {
                val headSegmentValue = map[headSegment.key] ?: return HasFailure("Key ${headSegment.key} not found", headSegment.parsedPath)
                ValueOperator.from(headSegmentValue)
            }
        }

        return operation(headSegmentOperator, tailSegments).ifHasValue { modifiedOperator ->
            modifiedOperator.value.finalize().ifHasValue { newValue ->
                val updatedMap = map.plus(headSegment.key to newValue.value)
                HasValue(copy(map = updatedMap))
            }
        }
    }

    companion object {
        fun from(value: JSONObjectValue): ObjectValueOperator = ObjectValueOperator(value.jsonObject)
    }
}
