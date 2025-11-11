package io.specmatic.core.jsonoperator.value

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.listFold
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.jsonoperator.JsonPointerOperator
import io.specmatic.core.jsonoperator.OperatorCapability
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.PathSegment
import io.specmatic.core.jsonoperator.RootMutableJsonOperator
import io.specmatic.core.jsonoperator.value.ValueOperator.Companion.nextDefaultOperator
import io.specmatic.core.jsonoperator.takeNextAs
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.Value

data class ArrayValueOperator(private val value: List<Value> = emptyList()) : RootMutableJsonOperator<JSONArrayValue> {
    override fun get(segments: List<PathSegment>): ReturnValue<Optional<RootMutableJsonOperator<out Value>>> {
        if (segments.isEmpty()) return HasValue(Optional.Some(this))
        val headSegment = segments.takeNextAs<PathSegment.Index>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)

        if (headSegment.index == ALL_ELEMENTS) return HasFailure("All Elements access for get operation is unsupported")
        if (headSegment.index < 0 || headSegment.index >= value.size) return HasValue(Optional.None)

        val headSegmentValue = value[headSegment.index]
        val headSegmentOperator = ValueOperator.from(headSegmentValue)
        return headSegmentOperator.get(tailSegments)
    }

    override fun insert(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        if (segments.isEmpty()) return HasValue(ValueOperator.from(value))
        val headSegment = segments.takeNextAs<PathSegment.Index>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)

        return if (headSegment.index == ALL_ELEMENTS) {
            applyToAllElements(tailSegments) { op, tail -> op.insert(tail, value) }
        } else {
            modifyAtIndex(segments = segments, allowMissing = true) { op, tail -> op.insert(tail, value) }
        }
    }

    override fun update(segments: List<PathSegment>, value: Value): ReturnValue<RootMutableJsonOperator<out Value>> {
        if (segments.isEmpty()) return HasValue(ValueOperator.from(value))
        val headSegment = segments.takeNextAs<PathSegment.Index>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)

        return if (headSegment.index == ALL_ELEMENTS) {
            applyToAllElements(tailSegments) { op, tail -> op.update(tail, value) }
        } else {
            modifyAtIndex(segments = segments, allowMissing = false) { op, tail -> op.update(tail, value) }
        }
    }

    override fun delete(segments: List<PathSegment>): ReturnValue<Optional<JsonPointerOperator<JSONArrayValue, OperatorCapability.Mutable>>> {
        if (segments.isEmpty()) return HasValue(Optional.None)
        val headSegment = segments.takeNextAs<PathSegment.Index>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)

        if (headSegment.index == ALL_ELEMENTS) {
            return this.value.mapNotNull {element ->
                ValueOperator.from(element).delete(tailSegments).realise(
                    hasValue = { it, _ -> it.getOrNull()?.finalize() },
                    orFailure = { it.cast() },
                    orException = { it.cast() },
                )
            }.listFold().ifHasValue { rUpdatedValues ->
                if (rUpdatedValues.value.isEmpty()) return@ifHasValue HasValue(Optional.None)
                HasValue(Optional.Some(copy(value = rUpdatedValues.value)))
            }
        }

        if (headSegment.index < 0 || headSegment.index >= value.size) return HasFailure("Index ${headSegment.index} out of bounds", headSegment.parsedPath)
        if (tailSegments.isEmpty()) {
            val newList = value.filterIndexed { i, _ -> i != headSegment.index }.takeUnless { it.isEmpty() }
            return newList?.let { HasValue(Optional.Some(copy(value = it))) } ?: HasValue(Optional.None)
        }

        val operator = ValueOperator.from(value[headSegment.index])
        return operator.delete(tailSegments).ifHasValue { returnValue ->
            returnValue.value.flatMapReturnValue { operatorValue ->
                operatorValue.finalize().ifHasValue {
                    HasValue(Optional.Some(copy(value = value.toMutableList().apply { set(headSegment.index, it.value) })))
                }
            }
        }
    }

    override fun finalize(): ReturnValue<JSONArrayValue> = HasValue(JSONArrayValue(value))

    private fun applyToAllElements(
        tailSegments: List<PathSegment>,
        operation: (RootMutableJsonOperator<out Value>, List<PathSegment>) -> ReturnValue<RootMutableJsonOperator<out Value>>,
    ): ReturnValue<RootMutableJsonOperator<out Value>> {
        return value.map { element ->
            val operator = ValueOperator.from(element)
            operation(operator, tailSegments).ifHasValue { resultOperator ->
                resultOperator.value.finalize()
            }.realise(
                hasValue = { it, _ -> HasValue(it) },
                orException = { it.cast() },
                orFailure = { it.cast() },
            )
        }.listFold().ifHasValue { rUpdatedValues ->
            HasValue(copy(value = rUpdatedValues.value))
        }
    }

    private fun modifyAtIndex(
        allowMissing: Boolean,
        segments: List<PathSegment>,
        operation: (RootMutableJsonOperator<out Value>, List<PathSegment>) -> ReturnValue<RootMutableJsonOperator<out Value>>,
    ): ReturnValue<RootMutableJsonOperator<out Value>> {
        val headSegment = segments.takeNextAs<PathSegment.Index>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)
        val trueHeadSegment = when (headSegment.index) {
            APPEND -> PathSegment.Index(value.size.dec(), headSegment.parsedPath)
            PREPEND -> PathSegment.Index(-1, headSegment.parsedPath)
            else -> headSegment
        }
        return modifyAtIndex(trueHeadSegment, allowMissing, tailSegments, operation)
    }

    private fun modifyAtIndex(
        headSegment: PathSegment.Index,
        allowMissing: Boolean,
        tailSegments: List<PathSegment>,
        operation: (RootMutableJsonOperator<out Value>, List<PathSegment>) -> ReturnValue<RootMutableJsonOperator<out Value>>,
    ): ReturnValue<RootMutableJsonOperator<out Value>> {
        if (headSegment.index < 0) {
            if (!allowMissing) return HasFailure("Index ${headSegment.index} out of bounds", headSegment.parsedPath)
            return operation(tailSegments.nextDefaultOperator(), tailSegments).ifHasValue { returnValue ->
                returnValue.value.finalize().ifHasValue { rValue ->
                    HasValue(copy(value = listOf(rValue.value) + value))
                }
            }
        }

        if (headSegment.index >= value.size) {
            if (!allowMissing || headSegment.index != value.size) return HasFailure("Index ${headSegment.index} out of bounds", headSegment.parsedPath)
            return operation(tailSegments.nextDefaultOperator(), tailSegments).ifHasValue { returnValue ->
                returnValue.value.finalize().ifHasValue { rValue ->
                    HasValue(copy(value = value + rValue.value))
                }
            }
        }

        val operator = ValueOperator.from(value[headSegment.index])
        return operation(operator, tailSegments).ifHasValue { returnValue ->
            returnValue.value.finalize().ifHasValue { rValue ->
                HasValue(copy(value = value.toMutableList().apply { set(headSegment.index, rValue.value) }))
            }
        }
    }

    companion object {
        const val ALL_ELEMENTS: Int = -1
        const val APPEND: Int = -2
        const val PREPEND: Int = -3

        fun from(value: JSONArrayValue): ArrayValueOperator = ArrayValueOperator(value.list)
    }
}
